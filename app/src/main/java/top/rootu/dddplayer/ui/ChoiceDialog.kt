package top.rootu.dddplayer.ui

import android.app.Dialog
import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.ui.adapter.ChoiceAdapter

/**
 * Диалог выбора одного значения из списка (экраны настроек).
 * @param currentIndex отмеченный пункт, -1 - без отметки (фокус на первом пункте)
 */
object ChoiceDialog {

    fun show(context: Context, title: String, items: List<String>, currentIndex: Int, onSelected: (Int) -> Unit): Dialog {
        val dialog = Dialog(context, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)
        dialog.findViewById<TextView>(R.id.dialog_title).text = title

        val recycler = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = ChoiceAdapter(items, currentIndex) { idx ->
            onSelected(idx)
            dialog.dismiss()
        }

        dialog.show()
        val focusIndex = currentIndex.coerceAtLeast(0)
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(focusIndex, 0)
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus() }, 50)
        }
        return dialog
    }
}
