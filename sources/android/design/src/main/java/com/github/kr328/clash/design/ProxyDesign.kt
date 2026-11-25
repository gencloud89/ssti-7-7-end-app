package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.SennetProxyAdapter
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class ProxyDesign(context: Context) : Design<ProxyDesign.Request>(context) {
    enum class Filter {
        RECOMMENDED,
        ALL,
        FAVORITES,
        RECENT,
    }

    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()
        object Refresh : Request()
        object UpdateSubscription : Request()
        object OpenHome : Request()
        object OpenAccount : Request()
        object OpenSettings : Request()
        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Select(val name: String) : Request()
    }

    private val binding = DesignProxyBinding.inflate(context.layoutInflater, context.root, false)
    private val prefs = context.getSharedPreferences("sennet_proxy_ui", Context.MODE_PRIVATE)
    private val adapter = SennetProxyAdapter(
        onSelect = { requests.trySend(Request.Select(it)) },
        onToggleFavorite = { toggleFavorite(it) },
    )

    private var activeFilter = Filter.ALL
    private var searchQuery = ""
    private var allProxies: List<Proxy> = emptyList()
    private var selectedName: String = ""

    override val root: View = binding.root

    init {
        binding.self = this
        binding.proxyList.layoutManager = LinearLayoutManager(context)
        binding.proxyList.adapter = adapter
        binding.proxyList.setHasFixedSize(true)
        binding.proxyList.itemAnimator = null
        binding.proxyList.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        binding.tabRecommended.setOnClickListener { setFilter(Filter.RECOMMENDED) }
        binding.tabAll.setOnClickListener { setFilter(Filter.ALL) }
        binding.tabFavorites.setOnClickListener { setFilter(Filter.FAVORITES) }
        binding.tabRecent.setOnClickListener { setFilter(Filter.RECENT) }
        binding.refreshButton.setOnClickListener { requests.trySend(Request.Refresh) }
        binding.updateSubscriptionButton.setOnClickListener { requests.trySend(Request.UpdateSubscription) }
        binding.homeButton.setOnClickListener { requests.trySend(Request.OpenHome) }
        binding.accountButton.setOnClickListener { requests.trySend(Request.OpenAccount) }
        binding.settingsButton.setOnClickListener { requests.trySend(Request.OpenSettings) }
        binding.searchInput.doAfterTextChanged {
            searchQuery = it?.toString()?.trim().orEmpty()
            render()
        }

        updateFilterChips()
    }

    suspend fun showProxies(proxies: List<Proxy>, currentSelection: String) = withContext(Dispatchers.Main) {
        allProxies = proxies
        selectedName = currentSelection
        render()
    }

    suspend fun markSelected(name: String) = withContext(Dispatchers.Main) {
        selectedName = name
        recordRecent(name)
        render()
    }

    private fun setFilter(filter: Filter) {
        activeFilter = filter
        updateFilterChips()
        render()
    }

    private fun render() {
        val favorites = favoriteSet()
        val recents = recentList()

        val base = when (activeFilter) {
            Filter.RECOMMENDED -> recommendedProxies(allProxies)
            Filter.ALL -> allProxies
            Filter.FAVORITES -> allProxies.filter { favorites.contains(it.name) }
            Filter.RECENT -> recents.mapNotNull { recentName -> allProxies.firstOrNull { it.name == recentName } }
        }

        val filtered = if (searchQuery.isBlank()) {
            base
        } else {
            base.filter {
                val haystack = "${it.name} ${it.subtitle}".lowercase(Locale.getDefault())
                haystack.contains(searchQuery.lowercase(Locale.getDefault()))
            }
        }

        adapter.submitItems(filtered.map { proxy ->
            SennetProxyAdapter.Item(
                name = proxy.name,
                title = formatTitle(proxy.name),
                subtitle = formatSubtitle(proxy),
                delayText = formatDelay(proxy.delay),
                delayColor = delayColor(proxy.delay),
                flag = flagFor(proxy.name),
                favorite = favorites.contains(proxy.name),
                selected = proxy.name == selectedName,
            )
        })

        binding.emptyView.isVisible = filtered.isEmpty()
        binding.proxyList.isVisible = filtered.isNotEmpty()
    }

    private fun recommendedProxies(proxies: List<Proxy>): List<Proxy> {
        if (proxies.isEmpty()) return proxies

        val selected = proxies.firstOrNull { it.name == selectedName }
        val remainder = proxies
            .filterNot { it.name == selectedName }
            .sortedWith(compareBy<Proxy> { if (it.delay <= 0) Int.MAX_VALUE else it.delay }.thenBy { it.name })

        return buildList {
            if (selected != null) add(selected)
            addAll(remainder.take(8))
        }.distinctBy { it.name }
    }

    private fun updateFilterChips() {
        applyChipState(binding.tabRecommended, activeFilter == Filter.RECOMMENDED)
        applyChipState(binding.tabAll, activeFilter == Filter.ALL)
        applyChipState(binding.tabFavorites, activeFilter == Filter.FAVORITES)
        applyChipState(binding.tabRecent, activeFilter == Filter.RECENT)
    }

    private fun applyChipState(view: TextView, active: Boolean) {
        view.setBackgroundResource(
            if (active) R.drawable.bg_sennet_chip_active else R.drawable.bg_sennet_chip_inactive
        )
        view.setTextColor(
            if (active) ContextCompat.getColor(context, R.color.sennet_text_on_accent)
            else ContextCompat.getColor(context, R.color.sennet_text_secondary)
        )
        view.setTypeface(view.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun toggleFavorite(name: String) {
        val favorites = favoriteSet().toMutableSet()
        if (!favorites.add(name)) {
            favorites.remove(name)
        }
        prefs.edit {
            putStringSet(KEY_FAVORITES, favorites)
        }
        render()
    }

    private fun favoriteSet(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    private fun recordRecent(name: String) {
        val updated = recentList().toMutableList().apply {
            remove(name)
            add(0, name)
            while (size > 12) removeLast()
        }
        prefs.edit { putString(KEY_RECENT, updated.joinToString(SEPARATOR)) }
    }

    private fun recentList(): List<String> = prefs.getString(KEY_RECENT, "").orEmpty()
        .split(SEPARATOR)
        .filter { it.isNotBlank() }

    private fun formatTitle(name: String): String = name.replace(Regex("\\s*\\|\\s*"), " \u00B7 ").trim()

    private fun formatSubtitle(proxy: Proxy): String {
        val subtitle = proxy.subtitle.trim()
        if (subtitle.isNotBlank() && subtitle != proxy.name) return subtitle
        return proxy.name.substringAfter(" - ", missingDelimiterValue = proxy.type.name).trim()
    }

    private fun formatDelay(delay: Int): String = when {
        delay in 1..Short.MAX_VALUE -> "$delay ms"
        delay == 0 -> context.getString(R.string.sennet_proxy_status_timeout)
        else -> "--"
    }

    private fun delayColor(delay: Int): Int = when {
        delay <= 0 -> ContextCompat.getColor(context, R.color.sennet_text_tertiary)
        delay < 70 -> ContextCompat.getColor(context, R.color.sennet_success)
        delay < 160 -> ContextCompat.getColor(context, R.color.sennet_success)
        delay < 300 -> ContextCompat.getColor(context, R.color.sennet_warning)
        else -> ContextCompat.getColor(context, R.color.sennet_danger)
    }

    private fun flagFor(name: String): String {
        val normalized = name.lowercase(Locale.getDefault())
        val countryCode = when {
            normalized.contains("vietnam") || normalized.contains("viet nam") || normalized.contains("-vn") -> "VN"
            normalized.contains("singapore") || normalized.contains("-sg") -> "SG"
            normalized.contains("japan") || normalized.contains("-jp") -> "JP"
            normalized.contains("hong") || normalized.contains("-hk") -> "HK"
            normalized.contains("korea") || normalized.contains("-kr") -> "KR"
            normalized.contains("united states") || normalized.contains("usa") || normalized.contains("-us") -> "US"
            normalized.contains("germany") || normalized.contains("-de") -> "DE"
            else -> return "\uD83C\uDF10"
        }

        return buildString {
            countryCode.forEach { append(Character.toChars(it.code + 127397)) }
        }
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENT = "recent"
        private const val SEPARATOR = "\n"
    }
}
