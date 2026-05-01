package com.piashmsuf.manga.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.piashmsuf.manga.databinding.ItemMangaBinding
import com.piashmsuf.manga.model.MangaItem

class LibraryAdapter(
    private val onClick: (MangaItem) -> Unit,
) : ListAdapter<MangaItem, LibraryAdapter.VH>(DIFF) {

    fun submit(list: List<MangaItem>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemMangaBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemMangaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MangaItem) {
            binding.title.text = item.title
            val subtitle = when (item.kind) {
                MangaItem.Kind.FOLDER -> "${item.pageCount} pages"
                MangaItem.Kind.ARCHIVE -> "Archive"
            }
            binding.subtitle.text = subtitle
            if (item.coverUri != null) {
                Glide.with(binding.cover).load(item.coverUri).centerCrop().into(binding.cover)
            } else {
                Glide.with(binding.cover).clear(binding.cover)
                binding.cover.setImageResource(android.R.color.darker_gray)
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
