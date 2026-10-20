package top.rootu.dddplayer.ui

import android.Manifest
import android.app.Dialog
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
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.DlnaEntry
import top.rootu.dddplayer.logic.DlnaLogic
import top.rootu.dddplayer.logic.DlnaServer
import top.rootu.dddplayer.logic.FileBrowserLogic
import top.rootu.dddplayer.logic.FileEntry
import top.rootu.dddplayer.logic.HomeLogic
import top.rootu.dddplayer.model.HistoryEntry
import top.rootu.dddplayer.model.HistorySource
import top.rootu.dddplayer.model.SavedPlaylist
import top.rootu.dddplayer.player.DlnaClient
import top.rootu.dddplayer.utils.IntentUtils
import top.rootu.dddplayer.utils.StorageRoot
import top.rootu.dddplayer.utils.StorageRoots
import java.io.File
import java.util.UUID

/**
 * Стартовый экран и встроенный браузер. На главном экране: "Продолжить просмотр" (история по
 * источникам), плейлисты по ссылке, хранилища (внутренняя память, SD-карты, USB-диски) и медиасерверы DLNA.
 * Локальные видео плеер получает обычным путем (file://), поэтому перемотка работает всегда,
 * а субтитры рядом с видео находятся автоматически. Видео из папки открываются плейлистом.
 */
class FileBrowserActivity : AppCompatActivity() {

    private enum class RowType {
        HEADER, HISTORY, HISTORY_TOGGLE, SAVED_PLAYLIST, ADD_PLAYLIST, ACCESS, STORAGE,
        DLNA_SERVER, DLNA_SEARCH, PARENT, DIR, VIDEO, PLAYLIST, DLNA_FOLDER, DLNA_VIDEO
    }

    private data class Row(
        val type: RowType,
        val title: String,
        val details: String,
        val path: String,
        val data: Any? = null
    )

    /** Где мы: главный экран, локальная папка или папка медиасервера. */
    private sealed class Location {
        object Home : Location()
        data class Local(val path: String) : Location()
        data class Dlna(val server: DlnaServer, val objectId: String, val title: String, val parent: Dlna?) : Location()
    }

    private lateinit var repository: SettingsRepository
    private lateinit var dlnaClient: DlnaClient
    private lateinit var header: TextView
    private lateinit var pathView: TextView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: RecyclerView
    private val adapter = FileAdapter()

    private var roots: List<StorageRoot> = emptyList()
    private var location: Location = Location.Home
    private var rows: List<Row> = emptyList()
    private var loadJob: Job? = null
    // Ждем возврата из экрана "Доступ ко всем файлам", чтобы обновить список
    private var waitingForAllFilesAccess = false
    // История развернута целиком (иначе первые HISTORY_PREVIEW записей)
    private var historyExpanded = false
    private var started = false

    // Медиасерверы DLNA: ищутся один раз при открытии экрана (и по "Искать снова")
    private var dlnaServers: List<DlnaServer> = emptyList()
    private var dlnaSearching = false

    private val sizeUnits by lazy { resources.getStringArray(R.array.file_size_units).toList() }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.files_permission_denied, Toast.LENGTH_LONG).show()
        openInitial()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)
        repository = SettingsRepository.getInstance(this)
        dlnaClient = DlnaClient(this)

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

        // Назад - на уровень выше, с главного экрана - выход
        onBackPressedDispatcher.addCallback(this) {
            when (val current = location) {
                Location.Home -> finish()
                is Location.Local -> open(parentOf(current.path), focusPath = current.path)
                is Location.Dlna -> open(current.parent ?: Location.Home, focusPath = dlnaKey(current))
            }
        }

        if (hasReadPermission()) openInitial() else permissionLauncher.launch(readPermission())
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAllFilesAccess) {
            waitingForAllFilesAccess = false
            open(location)
        } else if (started && location == Location.Home) {
            // Возврат из плеера: история обновилась
            open(Location.Home, focusPath = focusedPath())
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
        started = true
        open(Location.Home)
        searchDlna()
    }

    // Путь строки под фокусом, чтобы вернуть на нее фокус после обновления списка
    private fun focusedPath(): String? {
        val view = list.focusedChild ?: return null
        return rows.getOrNull(list.getChildAdapterPosition(view))?.path
    }

    private fun isRoot(path: String) = roots.any { it.path == path }

    // Выше корня хранилища - главный экран
    private fun parentOf(path: String): Location {
        if (isRoot(path)) return Location.Home
        val parent = File(path).parent?.takeIf { parent -> roots.any { parent.startsWith(it.path) } }
        return if (parent != null) Location.Local(parent) else Location.Home
    }

    private fun dlnaKey(location: Location.Dlna) = "dlna:${location.server.controlUrl}#${location.objectId}"

    private fun open(target: Location, focusPath: String? = null) {
        location = target
        if (target == Location.Home) roots = StorageRoots.list(this)
        header.text = when (target) {
            Location.Home -> getString(R.string.app_name)
            is Location.Local -> roots.firstOrNull { it.path == target.path }?.title ?: File(target.path).name
            is Location.Dlna -> target.title
        }
        pathView.text = when (target) {
            Location.Home -> ""
            is Location.Local -> target.path
            is Location.Dlna -> target.server.name
        }
        pathView.isVisible = target != Location.Home
        progress.isVisible = true
        emptyView.isVisible = false

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val newRows = when (target) {
                Location.Home -> withContext(Dispatchers.IO) { rootRows() }
                is Location.Local -> withContext(Dispatchers.IO) { dirRows(target.path) }
                is Location.Dlna -> dlnaRows(target)
            }
            // Пользователь мог уйти в другую папку, пока читали эту
            if (location != target) return@launch
            progress.isVisible = false
            rows = newRows ?: emptyList()
            adapter.notifyDataSetChanged()
            val hasContent = rows.any { it.type != RowType.PARENT }
            emptyView.isVisible = !hasContent
            emptyView.setText(if (newRows == null) R.string.files_no_access else R.string.files_empty)
            focusRow(rows.indexOfFirst { it.path == focusPath && it.type != RowType.HEADER }.takeIf { it >= 0 }
                ?: rows.indexOfFirst { it.type != RowType.PARENT && it.type != RowType.HEADER }.takeIf { it >= 0 } ?: 0)
        }
    }

    /** Главный экран: продолжить просмотр, плейлисты по ссылке, устройства, сеть. */
    private fun rootRows(): List<Row> {
        val result = mutableListOf<Row>()

        val history = repository.getWatchHistory()
        if (history.isNotEmpty()) {
            result.add(Row(RowType.HEADER, getString(R.string.home_continue), "", ""))
            val visible = if (historyExpanded) history else history.take(HomeLogic.HISTORY_PREVIEW)
            visible.forEach { entry ->
                val progress = HomeLogic.progressLabel(entry.positionMs, entry.durationMs)
                val details = listOf(entry.sourceName, progress).filter { it.isNotEmpty() }.joinToString(" · ")
                result.add(Row(RowType.HISTORY, entry.title, details, entry.key, entry))
            }
            if (history.size > HomeLogic.HISTORY_PREVIEW) {
                val title = if (historyExpanded) getString(R.string.home_history_collapse)
                else getString(R.string.home_history_all, history.size)
                result.add(Row(RowType.HISTORY_TOGGLE, title, "", "history_toggle"))
            }
        }

        result.add(Row(RowType.HEADER, getString(R.string.home_playlists), "", ""))
        repository.getSavedPlaylists().forEach { playlist ->
            result.add(Row(RowType.SAVED_PLAYLIST, playlist.name, playlist.url, "playlist:${playlist.id}", playlist))
        }
        result.add(Row(RowType.ADD_PLAYLIST, getString(R.string.home_playlist_add), getString(R.string.home_playlist_add_desc), "playlist_add"))

        result.add(Row(RowType.HEADER, getString(R.string.home_devices), "", ""))
        if (!hasAllFilesAccess()) {
            result.add(Row(RowType.ACCESS, getString(R.string.files_all_access_title), getString(R.string.files_all_access_desc), "access"))
        }
        roots.forEach { result.add(Row(RowType.STORAGE, it.title, it.path, it.path)) }

        result.add(Row(RowType.HEADER, getString(R.string.home_network), "", ""))
        dlnaServers.forEach { server ->
            result.add(Row(RowType.DLNA_SERVER, server.name, getString(R.string.home_dlna_server), "dlna_server:${server.controlUrl}", server))
        }
        val searchTitle = when {
            dlnaSearching -> getString(R.string.home_dlna_searching)
            dlnaServers.isEmpty() -> getString(R.string.home_dlna_not_found)
            else -> getString(R.string.home_dlna_search_again)
        }
        result.add(Row(RowType.DLNA_SEARCH, searchTitle, getString(R.string.home_dlna_search_desc), "dlna_search"))
        return result
    }

    private fun searchDlna() {
        if (dlnaSearching) return
        dlnaSearching = true
        if (location == Location.Home) open(Location.Home, focusPath = focusedPath())
        lifecycleScope.launch {
            dlnaServers = dlnaClient.discover()
            dlnaSearching = false
            if (location == Location.Home) open(Location.Home, focusPath = focusedPath())
        }
    }

    /** Содержимое папки или null, если ее не прочитать. */
    private fun dirRows(path: String): List<Row>? {
        val files = File(path).listFiles() ?: return null
        val entries = FileBrowserLogic.visibleEntries(
            files.map { FileEntry(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
        )
        val parent = parentOf(path)
        val parentTitle = (parent as? Location.Local)?.path ?: getString(R.string.app_name)
        val result = mutableListOf(Row(RowType.PARENT, "..", parentTitle, (parent as? Location.Local)?.path.orEmpty()))
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

    /** Содержимое папки медиасервера: сначала папки, потом видео, по имени с учетом чисел. */
    private suspend fun dlnaRows(target: Location.Dlna): List<Row>? {
        val entries = dlnaClient.browse(target.server, target.objectId) ?: return null
        val parentTitle = target.parent?.title ?: getString(R.string.app_name)
        val result = mutableListOf(Row(RowType.PARENT, "..", parentTitle, ""))
        val sorted = entries.sortedWith(compareBy<DlnaEntry> { !it.isContainer }.then { a, b ->
            FileBrowserLogic.naturalCompare(a.title, b.title)
        })
        sorted.forEach { entry ->
            if (entry.isContainer) {
                val child = Location.Dlna(target.server, entry.id, entry.title, target)
                result.add(Row(RowType.DLNA_FOLDER, entry.title, getString(R.string.files_folder), dlnaKey(child), entry))
            } else {
                val size = if (entry.size > 0) FileBrowserLogic.formatSize(entry.size, sizeUnits) else ""
                result.add(Row(RowType.DLNA_VIDEO, entry.title, size, entry.url.orEmpty(), entry))
            }
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
            RowType.HEADER -> Unit
            RowType.HISTORY -> resume(row.data as HistoryEntry)
            RowType.HISTORY_TOGGLE -> {
                historyExpanded = !historyExpanded
                open(Location.Home, focusPath = row.path)
            }
            RowType.SAVED_PLAYLIST -> (row.data as SavedPlaylist).let {
                playUrl(it.url, HistorySource(HistoryEntry.KIND_PLAYLIST, it.url, it.name))
            }
            RowType.ADD_PLAYLIST -> showPlaylistDialog(null)
            RowType.ACCESS -> requestAllFilesAccess()
            RowType.STORAGE, RowType.DIR -> open(Location.Local(row.path))
            RowType.DLNA_SERVER -> (row.data as DlnaServer).let {
                open(Location.Dlna(it, DlnaLogic.ROOT_ID, it.name, null))
            }
            RowType.DLNA_SEARCH -> searchDlna()
            RowType.PARENT -> when (val current = location) {
                is Location.Local -> open(parentOf(current.path), focusPath = current.path)
                is Location.Dlna -> open(current.parent ?: Location.Home, focusPath = dlnaKey(current))
                Location.Home -> Unit
            }
            RowType.DLNA_FOLDER -> (location as? Location.Dlna)?.let { current ->
                val entry = row.data as DlnaEntry
                open(Location.Dlna(current.server, entry.id, entry.title, current))
            }
            RowType.VIDEO -> playFolder(row)
            RowType.DLNA_VIDEO -> playDlnaFolder(row)
            RowType.PLAYLIST -> {
                val uri = Uri.fromFile(File(row.path))
                play(listOf(uri), listOf(row.title), 0, HistorySource(HistoryEntry.KIND_PLAYLIST, uri.toString(), row.title))
            }
        }
    }

    // Долгое нажатие: изменить плейлист или убрать запись из истории
    private fun onRowLongClick(row: Row): Boolean {
        when (row.type) {
            RowType.SAVED_PLAYLIST -> showPlaylistDialog(row.data as SavedPlaylist)
            RowType.HISTORY -> {
                val entry = row.data as HistoryEntry
                ChoiceDialog.show(this, entry.title, listOf(getString(R.string.home_history_remove)), -1) {
                    repository.removeWatchHistory(entry.key)
                    open(Location.Home)
                }
            }
            else -> return false
        }
        return true
    }

    // ==================== ВОСПРОИЗВЕДЕНИЕ ====================

    // Видео папки открываются плейлистом с выбранного: следующая серия запускается сама
    private fun playFolder(selected: Row) {
        val dir = (location as? Location.Local)?.path ?: return
        val videos = rows.filter { it.type == RowType.VIDEO }
        play(
            videos.map { Uri.fromFile(File(it.path)) },
            videos.map { it.title },
            videos.indexOf(selected).coerceAtLeast(0),
            HistorySource(HistoryEntry.KIND_FOLDER, dir, File(dir).name)
        )
    }

    private fun playDlnaFolder(selected: Row) {
        val current = location as? Location.Dlna ?: return
        val videos = rows.filter { it.type == RowType.DLNA_VIDEO }.map { it.data as DlnaEntry }
        playDlna(videos, videos.indexOf(selected.data as DlnaEntry).coerceAtLeast(0), current.server, current.objectId, current.title)
    }

    private fun playDlna(videos: List<DlnaEntry>, startIndex: Int, server: DlnaServer, objectId: String, title: String) {
        val source = HistorySource(HistoryEntry.KIND_DLNA, HomeLogic.dlnaSource(server.location, objectId), title)
        val intent = playerIntent(videos.map { Uri.parse(it.url) }, videos.map { it.title }, startIndex, source, titlesAreNames = true)
        // Субтитры, которые отдает сервер (например, .srt рядом с видео у MiniDLNA)
        if (videos.any { it.subtitleUrls.isNotEmpty() }) {
            intent.putParcelableArrayListExtra("video_list.subtitles", ArrayList(videos.map { video ->
                Bundle().apply {
                    putParcelableArray("uris", video.subtitleUrls.map { Uri.parse(it) }.toTypedArray())
                    putStringArray("names", video.subtitleUrls.map { Uri.parse(it).lastPathSegment.orEmpty() }.toTypedArray())
                }
            }))
        }
        startActivity(intent)
    }

    /** Продолжить просмотр: папка снова открывается плейлистом с последнего видео, плейлист - по ссылке. */
    private fun resume(entry: HistoryEntry) {
        val source = HistorySource(entry.kind, entry.source, entry.sourceName)
        when (entry.kind) {
            HistoryEntry.KIND_FOLDER -> {
                val files = File(entry.source).listFiles()
                val videos = files?.let { list ->
                    FileBrowserLogic.visibleEntries(list.map { FileEntry(it.name, it.absolutePath, it.isDirectory) }).filter { it.isVideo }
                }.orEmpty()
                val uris = videos.map { Uri.fromFile(File(it.path)) }
                val index = uris.indexOfFirst { it.toString() == entry.uri }
                if (index < 0) {
                    Toast.makeText(this, R.string.home_unavailable, Toast.LENGTH_LONG).show()
                    return
                }
                play(uris, videos.map { it.name }, index, source)
            }
            HistoryEntry.KIND_DLNA -> resumeDlna(entry)
            else -> playUrl(entry.source, source)
        }
    }

    // DLNA: заново читаем папку сервера (адрес сервера в сети мог смениться) и ищем видео по ссылке или имени
    private fun resumeDlna(entry: HistoryEntry) {
        val (serverLocation, objectId) = HomeLogic.parseDlnaSource(entry.source) ?: return
        progress.isVisible = true
        lifecycleScope.launch {
            val server = dlnaClient.describe(serverLocation)
            val videos = server?.let { dlnaClient.browse(it, objectId) }?.filter { !it.isContainer }
                ?.sortedWith { a, b -> FileBrowserLogic.naturalCompare(a.title, b.title) }
            progress.isVisible = false
            val index = videos?.indexOfFirst { it.url == entry.uri }?.takeIf { it >= 0 }
                ?: videos?.indexOfFirst { it.title == entry.title }?.takeIf { it >= 0 }
            if (server == null || videos == null || index == null) {
                Toast.makeText(this@FileBrowserActivity, R.string.home_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            playDlna(videos, index, server, objectId, entry.sourceName)
        }
    }

    // Плейлист по ссылке: содержимое проверяется как M3U даже без расширения (get.php?...)
    private fun playUrl(url: String, source: HistorySource) {
        val intent = playerIntent(listOf(Uri.parse(url)), listOf(source.name), 0, source, titlesAreNames = true)
            .putExtra(IntentUtils.EXTRA_FORCE_M3U, true)
        startActivity(intent)
    }

    private fun play(uris: List<Uri>, fileNames: List<String>, startIndex: Int, source: HistorySource) {
        startActivity(playerIntent(uris, fileNames, startIndex, source))
    }

    /**
     * @param titlesAreNames названия уже без расширения (DLNA, плейлист), иначе это имена файлов
     */
    private fun playerIntent(
        uris: List<Uri>,
        names: List<String>,
        startIndex: Int,
        source: HistorySource,
        titlesAreNames: Boolean = false
    ): Intent =
        // Ссылки передаем в extras, а не в data: file:// в data система запрещает (FileUriExposedException)
        Intent(this, PlayerActivity::class.java).apply {
            putParcelableArrayListExtra("video_list", ArrayList(uris))
            putExtra("video_list.name", names.map { if (titlesAreNames) it else it.substringBeforeLast('.') }.toTypedArray())
            if (!titlesAreNames) putExtra("video_list.filename", names.toTypedArray())
            putExtra("start_index", startIndex)
            putExtra(IntentUtils.EXTRA_HISTORY_KIND, source.kind)
            putExtra(IntentUtils.EXTRA_HISTORY_SOURCE, source.source)
            putExtra(IntentUtils.EXTRA_HISTORY_NAME, source.name)
            // Если плеер уже открыт под этим экраном, возвращаемся в него, а не открываем второй
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    // ==================== ПЛЕЙЛИСТЫ ПО ССЫЛКЕ ====================

    // Добавление (playlist = null) или изменение плейлиста по ссылке
    private fun showPlaylistDialog(playlist: SavedPlaylist?) {
        val dialog = Dialog(this, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_saved_playlist)
        val title = dialog.findViewById<TextView>(R.id.dialog_title)
        val editName = dialog.findViewById<EditText>(R.id.edit_name)
        val editUrl = dialog.findViewById<EditText>(R.id.edit_url)
        val btnDelete = dialog.findViewById<Button>(R.id.btn_delete)

        title.setText(if (playlist == null) R.string.home_playlist_add else R.string.home_playlist_edit)
        editName.setText(playlist?.name.orEmpty())
        editUrl.setText(playlist?.url.orEmpty())
        btnDelete.isVisible = playlist != null

        btnDelete.setOnClickListener {
            playlist?.let { repository.removeSavedPlaylist(it.id) }
            dialog.dismiss()
            open(Location.Home)
        }
        dialog.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btn_save).setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.home_playlist_url_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = editName.text.toString().trim().ifEmpty { HomeLogic.defaultPlaylistName(url) }
            val id = playlist?.id ?: UUID.randomUUID().toString()
            repository.putSavedPlaylist(SavedPlaylist(id, name, url))
            dialog.dismiss()
            open(Location.Home, focusPath = "playlist:$id")
        }
        dialog.show()
        (if (playlist == null) editUrl else editName).requestFocus()
    }

    // ==================== СПИСОК ====================

    private inner class FileAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.file_icon)
            val title: TextView = view.findViewById(R.id.file_title)
            val details: TextView = view.findViewById(R.id.file_details)
        }

        inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.header_title)
        }

        override fun getItemViewType(position: Int): Int = if (rows[position].type == RowType.HEADER) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) HeaderHolder(inflater.inflate(R.layout.item_file_header, parent, false))
            else Holder(inflater.inflate(R.layout.item_file_entry, parent, false))
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (holder is HeaderHolder) {
                holder.title.text = row.title
                return
            }
            holder as Holder
            holder.title.text = row.title
            holder.details.text = row.details
            holder.details.isVisible = row.details.isNotEmpty()
            holder.icon.setImageResource(
                when (row.type) {
                    RowType.HEADER, RowType.HISTORY -> R.drawable.ic_play_arrow
                    RowType.HISTORY_TOGGLE, RowType.SAVED_PLAYLIST, RowType.PLAYLIST -> R.drawable.ic_playlist_play
                    RowType.ADD_PLAYLIST -> R.drawable.ic_add
                    RowType.ACCESS -> R.drawable.ic_lock_open
                    RowType.STORAGE -> R.drawable.ic_storage
                    RowType.DLNA_SERVER -> R.drawable.ic_dlna
                    RowType.DLNA_SEARCH -> R.drawable.ic_update
                    RowType.PARENT -> R.drawable.ic_arrow_left
                    RowType.DIR, RowType.DLNA_FOLDER -> R.drawable.ic_folder
                    RowType.VIDEO, RowType.DLNA_VIDEO -> R.drawable.ic_video_file
                }
            )
            holder.itemView.setOnClickListener { onRowClick(row) }
            holder.itemView.setOnLongClickListener { onRowLongClick(row) }
        }
    }
}
