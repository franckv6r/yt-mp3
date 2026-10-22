package com.fp.ytmp3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val items: List<TrackItem>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    var query: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.trackCheck)
        val cover: ImageView = v.findViewById(R.id.trackCover)
        val title: TextView = v.findViewById(R.id.trackTitle)
        val status: TextView = v.findViewById(R.id.trackStatus)
    }

    fun visibleItems(): List<TrackItem> =
        filterByQuery(items, query) { it.title }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun getItemCount(): Int = visibleItems().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = visibleItems()[position]
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.checked
        holder.title.text = item.title
        holder.status.text = holder.itemView.context.getString(
            if (item.present) R.string.status_present else R.string.status_to_download
        )
        holder.status.setTextColor(
            if (item.present) 0xFF9AA7B4.toInt() else 0xFF7AA2F7.toInt()
        )

        val uri = item.localUri
        val pad = (10 * holder.itemView.resources.displayMetrics.density).toInt()
        holder.cover.tag = uri?.toString()
        holder.cover.setPadding(pad, pad, pad, pad)
        holder.cover.setImageResource(R.drawable.ic_cover_placeholder)
        if (uri != null) {
            CoverLoader.load(holder.itemView.context, uri) { bitmap ->
                if (bitmap != null && holder.cover.tag == uri.toString()) {
                    holder.cover.setPadding(0, 0, 0, 0)
                    holder.cover.setImageBitmap(bitmap)
                }
            }
        }

        holder.itemView.setOnClickListener { holder.check.toggle() }
        holder.check.setOnCheckedChangeListener { _, checked ->
            item.checked = checked
            onChanged()
        }
    }
}
