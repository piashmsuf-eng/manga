package com.piashmsuf.manga.library

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.piashmsuf.manga.R
import com.piashmsuf.manga.databinding.ItemMangaBinding
import com.piashmsuf.manga.model.MangaItem
import java.util.concurrent.TimeUnit

class LibraryAdapter(
    private val onClick: (MangaItem) -> Unit,
    private val onToggleFavorite: (MangaItem) -> Unit,
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
            val ctx = binding.root.context
            binding.title.text = item.title
            binding.subtitle.text = subtitleFor(ctx, item)
            if (item.coverUri != null) {
                Glide.with(binding.cover).load(item.coverUri).centerCrop().into(binding.cover)
            } else {
                Glide.with(binding.cover).clear(binding.cover)
                binding.cover.setImageResource(android.R.color.darker_gray)
            }
            binding.favorite.setImageResource(
                if (item.favorite) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            binding.favorite.contentDescription = ctx.getString(
                if (item.favorite) R.string.unfavorite else R.string.favorite
            )

            val lastPage = item.lastReadPage
            val total = item.pageCount
            if (lastPage != null && lastPage > 0 && total > 0) {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = total - 1
                binding.progressBar.progress = lastPage.coerceAtMost(total - 1)
            } else {
                binding.progressBar.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.favorite.setOnClickListener { onToggleFavorite(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MangaItem>() {
            override fun areItemsTheSame(oldItem: MangaItem, newItem: MangaItem) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(oldItem: MangaItem, newItem: MangaItem) = oldItem == newItem
        }

        fun subtitleFor(ctx: Context, item: MangaItem): String {
            val base = when (item.kind) {
                MangaItem.Kind.FOLDER -> "${item.pageCount} pages"
                MangaItem.Kind.ARCHIVE -> "Archive"
            }
            val ts = item.lastReadAt
            if (ts <= 0L) return base
            val diff = System.currentTimeMillis() - ts
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            val rel = when {
                mins < 1 -> ctx.getString(R.string.last_read_just_now)
                mins < 60 -> ctx.getString(R.string.last_read_minutes, mins.toInt())
                hours < 24 -> ctx.getString(R.string.last_read_hours, hours.toInt())
                else -> ctx.getString(R.string.last_read_days, days.toInt())
            }
            return "$base · $rel"
        }
    }
}
