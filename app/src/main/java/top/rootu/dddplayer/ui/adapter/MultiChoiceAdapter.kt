package top.rootu.dddplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.R

/** Список с флажками: клик переключает пункт, диалог не закрывается. */
class MultiChoiceAdapter(
    private val items: List<String>,
    private val checked: BooleanArray,
    private val onChanged: (BooleanArray) -> Unit
) : RecyclerView.Adapter<MultiChoiceAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: CheckedTextView = view.findViewById(R.id.text1)
        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    checked[position] = !checked[position]
                    textView.isChecked = checked[position]
                    onChanged(checked.copyOf())
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dialog_multi_choice, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = items[position]
        holder.textView.isChecked = checked[position]
    }

    override fun getItemCount() = items.size
}
