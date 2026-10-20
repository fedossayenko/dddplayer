package top.rootu.dddplayer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.FileBrowserLogic
import top.rootu.dddplayer.logic.FileEntry
import top.rootu.dddplayer.utils.StorageRoot
import top.rootu.dddplayer.utils.StorageRoots
import java.io.File

/**
 * Встроенный файловый браузер: внутренняя память, SD-карты и USB-диски.
 * Плеер получает обычный путь к файлу (file://), поэтому перемотка работает всегда,
 * а субтитры рядом с видео находятся автоматически. Видео из папки открываются плейлистом.
 */
class FileBrowserActivity : AppCompatActivity() {

    private enum class RowType { ACCESS, STORAGE, PARENT, DIR, VIDEO, PLAYLIST }

    private data class Row(val type: RowType, val title: String, val details: String, val path: String)

    private lateinit var repository: SettingsRepository
    private lateinit var header: TextView
    private lateinit var pathView: TextView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: RecyclerView
    private val adapter = FileAdapter()

    private var roots: List<StorageRoot> = emptyList()
    // null - список хранилищ
    private var currentDir: String? = null
    private var rows: List<Row> = emptyList()
    // Ждем возврата из экрана "Доступ ко всем файлам", чтобы обновить список
    private var waitingForAllFilesAccess = false

    private val sizeUnits by lazy { resources.getStringArray(R.array.file_size_units).toList() }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.files_permission_denied, Toast.LENGTH_LONG).show()
        openInitial()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)
        repository = SettingsRepository.getInstance(this)

        header = findViewById(R.id.files_header)
        pathView = findViewById(R.id.files_path)
        emptyView = findViewById(R.id.files_empty)
        progress = findViewById(R.id.files_progress)
        list = findViewById(R.id.files_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.btn_files_settings).setOnClickListener {
            startActivity(Intent(this, GlobalSettingsActivity::class.java))
        }

        // Назад - на уровень выше, из списка хранилищ - выход
        onBackPressedDispatcher.addCallback(this) {
            val dir = currentDir
            if (dir == null) finish() else openDir(parentOf(dir), focusPath = dir)
        }

        if (hasReadPermission()) openInitial() else permissionLauncher.launch(readPermission())
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAllFilesAccess) {
            waitingForAllFilesAccess = false
            openDir(currentDir)
        }
    }

    // ==================== РАЗРЕШЕНИЯ ====================

    private fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun hasReadPermission(): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) ||
                ContextCompat.checkSelfPermission(this, readPermission()) == PackageManager.PERMISSION_GRANTED

    // Android 11+: без "доступа ко всем файлам" система показывает только медиафайлы, субтитры не видны
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                waitingForAllFilesAccess = true
                return
            } catch (_: ActivityNotFoundException) {
                // На части ТВ-прошивок такого экрана нет - пробуем следующий
            }
        }
        Toast.makeText(this, R.string.files_all_access_unavailable, Toast.LENGTH_LONG).show()
    }

    // ==================== НАВИГАЦИЯ ====================

    private fun openInitial() {
        roots = StorageRoots.list(this)
        // Открываем последнюю папку, если она еще доступна (например, USB-диск не отключен)
        val last = repository.getFileBrowserLastDir()?.takeIf { File(it).let { f -> f.isDirectory && f.canRead() } }
        openDir(last)
    }

    private fun isRoot(path: String) = roots.any { it.path == path }

    // Выше корня хранилища - список хранилищ
    private fun parentOf(path: String): String? =
        if (isRoot(path)) null else File(path).parent?.takeIf { parent -> roots.any { parent.startsWith(it.path) } }

    private fun openDir(path: String?, focusPath: String? = null) {
        currentDir = path
        repository.setFileBrowserLastDir(path)
        header.text = when {
            path == null -> getString(R.string.files_title)
            else -> roots.firstOrNull { it.path == path }?.title ?: File(path).name
        }
        pathView.text = path.orEmpty()
        pathView.isVisible = path != null
        progress.isVisible = true
        emptyView.isVisible = false

        lifecycleScope.launch {
            val newRows = withContext(Dispatchers.IO) { if (path == null) rootRows() else dirRows(path) }
            // Пользователь мог уйти в другую папку, пока читали эту
            if (currentDir != path) return@launch
            progress.isVisible = false
            rows = newRows ?: emptyList()
            adapter.notifyDataSetChanged()
            val hasContent = rows.any { it.type != RowType.PARENT }
            emptyView.isVisible = !hasContent
            emptyView.setText(if (newRows == null) R.string.files_no_access else R.string.files_empty)
            focusRow(rows.indexOfFirst { it.path == focusPath }.takeIf { it >= 0 }
                ?: rows.indexOfFirst { it.type != RowType.PARENT }.takeIf { it >= 0 } ?: 0)
        }
    }

    private fun rootRows(): List<Row> {
        val result = mutableListOf<Row>()
        if (!hasAllFilesAccess()) {
            result.add(Row(RowType.ACCESS, getString(R.string.files_all_access_title), getString(R.string.files_all_access_desc), ""))
        }
        roots.forEach { result.add(Row(RowType.STORAGE, it.title, it.path, it.path)) }
        return result
    }

    /** Содержимое папки или null, если ее не прочитать. */
    private fun dirRows(path: String): List<Row>? {
        val files = File(path).listFiles() ?: return null
        val entries = FileBrowserLogic.visibleEntries(
            files.map { FileEntry(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
        )
        val parent = parentOf(path)
        val result = mutableListOf(
            Row(RowType.PARENT, "..", parent ?: getString(R.string.files_title), parent ?: "")
        )
        entries.forEach { entry ->
            result.add(
                when {
                    entry.isDirectory -> Row(RowType.DIR, entry.name, getString(R.string.files_folder), entry.path)
                    entry.isPlaylist -> Row(RowType.PLAYLIST, entry.name, FileBrowserLogic.formatSize(entry.size, sizeUnits), entry.path)
                    else -> Row(RowType.VIDEO, entry.name, FileBrowserLogic.formatSize(entry.size, sizeUnits), entry.path)
                }
            )
        }
        return result
    }

    // Фокус на строку после загрузки списка (для пульта): сначала прокрутка, затем фокус после разметки
    private fun focusRow(index: Int) {
        if (index !in rows.indices) return
        list.scrollToPosition(index)
        list.post {
            list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                ?: list.post { list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    private fun onRowClick(row: Row) {
        when (row.type) {
            RowType.ACCESS -> requestAllFilesAccess()
            RowType.STORAGE, RowType.DIR -> openDir(row.path)
            RowType.PARENT -> openDir(row.path.ifEmpty { null }, focusPath = currentDir)
            RowType.VIDEO -> playFolder(row)
            RowType.PLAYLIST -> play(listOf(row), 0)
        }
    }

    // Видео папки открываются плейлистом с выбранного: следующая серия запускается сама
    private fun playFolder(selected: Row) {
        val videos = rows.filter { it.type == RowType.VIDEO }
        play(videos, videos.indexOf(selected).coerceAtLeast(0))
    }

    private fun play(items: List<Row>, startIndex: Int) {
        // Ссылки передаем в extras, а не в data: file:// в data система запрещает (FileUriExposedException)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putParcelableArrayListExtra("video_list", ArrayList(items.map { Uri.fromFile(File(it.path)) }))
            putExtra("video_list.name", items.map { it.title.substringBeforeLast('.') }.toTypedArray())
            putExtra("video_list.filename", items.map { it.title }.toTypedArray())
            putExtra("start_index", startIndex)
            // Если браузер открыт из плеера, возвращаемся в тот же плеер, а не открываем второй
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // ==================== СПИСОК ====================

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.file_icon)
            val title: TextView = view.findViewById(R.id.file_title)
            val details: TextView = view.findViewById(R.id.file_details)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_file_entry, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.details.text = row.details
            holder.details.isVisible = row.details.isNotEmpty()
            holder.icon.setImageResource(
                when (row.type) {
                    RowType.ACCESS -> R.drawable.ic_lock_open
                    RowType.STORAGE -> R.drawable.ic_storage
                    RowType.PARENT -> R.drawable.ic_arrow_left
                    RowType.DIR -> R.drawable.ic_folder
                    RowType.VIDEO -> R.drawable.ic_video_file
                    RowType.PLAYLIST -> R.drawable.ic_playlist_play
                }
            )
            holder.itemView.setOnClickListener { onRowClick(row) }
        }
    }
}
