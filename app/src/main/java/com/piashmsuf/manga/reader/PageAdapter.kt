package com.piashmsuf.manga.reader

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.piashmsuf.manga.databinding.ItemPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PageAdapter(private val source: MangaSource) :
    RecyclerView.Adapter<PageAdapter.VH>() {

    private val overlays = mutableMapOf<Int, Bitmap>()
    private val scope: CoroutineScope = MainScope()

    fun setOverlay(index: Int, overlay: Bitmap) {
        overlays[index] = overlay
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemPageBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount(): Int = source.pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val overlay = overlays[position]
        if (overlay != null) {
            holder.bind(overlay)
            return
        }
        holder.bind(null)
        holder.loadJob?.cancel()
        holder.loadJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { source.decodePage(position) }
            if (bitmap != null && holder.bindingAdapterPosition == position) holder.bind(bitmap)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.loadJob?.cancel()
        holder.loadJob = null
        super.onViewRecycled(holder)
    }

    fun release() {
        scope.cancel()
        overlays.clear()
    }

    class VH(private val binding: ItemPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
        fun bind(bitmap: Bitmap?) {
            if (bitmap == null) {
                binding.image.setImageDrawable(null)
            } else {
                binding.image.setImageBitmap(bitmap)
            }
        }
    }
}
