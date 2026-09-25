package com.tvplayer.universal.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tvplayer.universal.browse.Kinds
import com.tvplayer.universal.browse.MediaItem
import com.tvplayer.universal.databinding.ItemFileBinding

class FileAdapter(
    private val onOpen: (MediaItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    var items: List<MediaItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    inner class VH(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener {
                val i = bindingAdapterPosition
                if (i != RecyclerView.NO_POSITION) onOpen(items[i])
            }
            b.root.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.02f else 1.0f
                v.scaleY = if (hasFocus) 1.02f else 1.0f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.b.name.text = item.name
        h.b.glyph.text = when {
            item.isDir -> "▣"
            Kinds.isVideo(item.name) -> "▶"
            else -> "≡"
        }
        h.b.caption.text = if (item.isDir) item.hint else formatSize(item.size)
    }

    private fun formatSize(size: Long): String = when {
        size <= 0 -> ""
        size >= 1L shl 30 -> "%.1f GB".format(size / 1073741824.0)
        size >= 1L shl 20 -> "%.0f MB".format(size / 1048576.0)
        else -> "%d KB".format(size / 1024)
    }
}
