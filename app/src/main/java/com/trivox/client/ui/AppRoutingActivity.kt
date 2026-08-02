package com.trivox.client.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.SettingsRepository
import java.util.concurrent.Executors

class AppRoutingActivity : AppCompatActivity() {
    private data class Item(val label: String, val packageName: String, val system: Boolean)
    private lateinit var allItems: List<Item>
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_app_routing)
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val repository = SettingsRepository(this); val settings = repository.load()
        val spinner = findViewById<Spinner>(R.id.routingMode)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf(
            getString(R.string.routing_all), getString(R.string.routing_allow), getString(R.string.routing_bypass)))
        spinner.setSelection(settings.appRoutingMode.ordinal)
        val showSystem = findViewById<CheckBox>(R.id.showSystem).apply { isChecked = settings.showSystemApps }
        allItems = loadApplications()
        adapter = AppAdapter(packageManager, settings.routedPackages.toMutableSet())
        findViewById<RecyclerView>(R.id.appList).apply { layoutManager = LinearLayoutManager(this@AppRoutingActivity); adapter = this@AppRoutingActivity.adapter }
        fun refresh() { adapter.submit(allItems.filter { showSystem.isChecked || !it.system }) }
        showSystem.setOnCheckedChangeListener { _, _ -> refresh() }; refresh()
        findViewById<View>(R.id.saveButton).setOnClickListener {
            settings.appRoutingMode = AppRoutingMode.entries[spinner.selectedItemPosition]
            settings.routedPackages = adapter.selected.toSet(); settings.showSystemApps = showSystem.isChecked
            repository.save(settings); finish()
        }
    }

    private fun loadApplications(): List<Item> {
        val apps = if (Build.VERSION.SDK_INT >= 33) packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") packageManager.getInstalledApplications(0)
        return apps.asSequence().filter { it.packageName != packageName }
            .map { Item(packageManager.getApplicationLabel(it).toString(), it.packageName, it.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
            .sortedBy { it.label.lowercase() }.toList()
    }

    private class AppAdapter(private val pm: PackageManager, val selected: MutableSet<String>) : RecyclerView.Adapter<AppAdapter.Holder>() {
        private val values = mutableListOf<Item>()
        private val icons = LruCache<String, Drawable>(64)
        private val loader = Executors.newFixedThreadPool(2)
        fun submit(items: List<Item>) { values.clear(); values.addAll(items); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_app, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(values[position])
        override fun getItemCount() = values.size
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { loader.shutdownNow() }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon = view.findViewById<ImageView>(R.id.appIcon)
            private val label = view.findViewById<TextView>(R.id.appLabel)
            private val packageName = view.findViewById<TextView>(R.id.packageName)
            private val checked = view.findViewById<CheckBox>(R.id.selected)
            fun bind(item: Item) {
                itemView.tag = item.packageName; label.text = item.label; packageName.text = item.packageName
                checked.setOnCheckedChangeListener(null); checked.isChecked = item.packageName in selected
                checked.setOnCheckedChangeListener { _, enabled -> if (enabled) selected += item.packageName else selected -= item.packageName }
                itemView.setOnClickListener { checked.isChecked = !checked.isChecked }
                val cached = icons.get(item.packageName); icon.setImageDrawable(cached)
                if (cached == null) loader.execute {
                    val drawable = runCatching { pm.getApplicationIcon(item.packageName) }.getOrNull() ?: return@execute
                    icons.put(item.packageName, drawable); icon.post { if (itemView.tag == item.packageName) icon.setImageDrawable(drawable) }
                }
            }
        }
    }
}
