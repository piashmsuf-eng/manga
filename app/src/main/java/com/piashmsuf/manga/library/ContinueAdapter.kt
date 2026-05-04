package com.piashmsuf.manga.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.piashmsuf.manga.databinding.ItemContinueBinding
import com.piashmsuf.manga.model.MangaItem

/** Compact horizontal "Continue reading" tile. */
class ContinueAdapter(
    private val onClick: (MangaItem) -> Unit,
) : ListAdapter<MangaItem, ContinueAdapter.VH>(DIFF) {

    fun submit(list: List<MangaItem>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemContinueBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemContinueBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MangaItem) {
            binding.title.text = item.title
            if (item.coverUri != null) {
                Glide.with(binding.cover).load(item.coverUri).centerCrop().into(binding.cover)
            } else {
                Glide.with(binding.cover).clear(binding.cover)
                binding.cover.setImageResource(android.R.color.darker_gray)
            }
            val total = item.pageCount
            val page = item.lastReadPage ?: 0
            if (total > 0) {
                binding.progressBar.max = total - 1
                binding.progressBar.progress = page.coerceAtMost(total - 1)
            } else {
                binding.progressBar.max = 1
                binding.progressBar.progress = 0
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MangaItem>() {
            override fun areItemsTheSame(oldItem: MangaItem, newItem: MangaItem) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(oldItem: MangaItem, newItem: MangaItem) = oldItem == newItem
        }
    }
}
