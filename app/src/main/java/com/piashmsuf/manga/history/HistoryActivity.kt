package com.piashmsuf.manga.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.piashmsuf.manga.R
import com.piashmsuf.manga.databinding.ActivityHistoryBinding
import com.piashmsuf.manga.databinding.ItemHistoryBinding
import com.piashmsuf.manga.util.PillHistoryStore
import java.text.DateFormat
import java.util.Date

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: PillHistoryStore
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_history)
        store = PillHistoryStore(this)

        adapter = HistoryAdapter(::copyEntry)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnClear.setOnClickListener {
            store.clear()
            refresh()
            Snackbar.make(binding.root, R.string.clear_history, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun refresh() {
        val items = store.all()
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun copyEntry(entry: PillHistoryStore.Entry) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", entry.translated))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}

class HistoryAdapter(
    private val onCopy: (PillHistoryStore.Entry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<PillHistoryStore.Entry>()
    private val dateFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submit(list: List<PillHistoryStore.Entry>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: PillHistoryStore.Entry) {
            binding.original.text = entry.original
            binding.translated.text = entry.translated
            binding.timestamp.text = dateFormatter.format(Date(entry.timestamp))
            binding.langs.text = "${entry.sourceLang} → ${entry.targetLang}"
            binding.btnCopy.setOnClickListener { onCopy(entry) }
        }
    }
}
