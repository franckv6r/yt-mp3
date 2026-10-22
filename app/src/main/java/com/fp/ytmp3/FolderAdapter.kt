package com.fp.ytmp3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val items: List<FolderItem>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    var query: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.fileCheck)
        val cover: ImageView = v.findViewById(R.id.fileCover)
        val name: TextView = v.findViewById(R.id.fileName)
    }

    fun visibleItems(): List<FolderItem> =
        filterByQuery(items, query) { it.name }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount(): Int = visibleItems().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = visibleItems()[position]
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.checked
        holder.name.text = item.name

        val uri = item.uri
        val pad = (10 * holder.itemView.resources.displayMetrics.density).toInt()
        holder.cover.tag = uri.toString()
        holder.cover.setPadding(pad, pad, pad, pad)
        holder.cover.setImageResource(R.drawable.ic_cover_placeholder)
        CoverLoader.load(holder.itemView.context, uri) { bitmap ->
            if (bitmap != null && holder.cover.tag == uri.toString()) {
                holder.cover.setPadding(0, 0, 0, 0)
                holder.cover.setImageBitmap(bitmap)
            }
        }

        holder.itemView.setOnClickListener { holder.check.toggle() }
        holder.check.setOnCheckedChangeListener { _, checked ->
            item.checked = checked
            onChanged()
        }
    }
}
