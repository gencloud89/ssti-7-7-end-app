package com.github.kr328.clash

import android.os.SystemClock
import com.github.kr328.clash.common.store.AuthStore
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.SennetSubscriptionManager
import com.github.kr328.clash.util.SubscriptionConfigStore
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.UUID

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        title = getString(R.string.sennet_screen_proxy)

        val design = ProxyDesign(this)
        setContentDesign(design)

        var activeProfileId = resolveActiveProfileId()
        var fallbackSnapshot = SubscriptionConfigStore.loadActiveSnapshot(this, activeProfileId)
        var targetGroupName: String? = null
        val delayCache = LinkedHashMap<String, Int>()

        suspend fun queryRuntimeGroupNames(): List<String> {
            return try {
                withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        suspend fun ensureRuntimeGroupsLoaded(): List<String> {
            var runtimeNames = queryRuntimeGroupNames()

            if (runtimeNames.isNotEmpty()) {
                return runtimeNames
            }

            activeProfileId?.let { profileId ->
                runCatching {
                    withProfile {
                        queryByUUID(profileId)
                            ?.takeIf { it.imported }
                            ?.let { setActive(it) }
                    }
                }

                repeat(12) {
                    delay(250)
                    runtimeNames = queryRuntimeGroupNames()

                    if (runtimeNames.isNotEmpty()) {
                        return runtimeNames
                    }
                }
            }

            return runtimeNames
        }

        suspend fun refreshSourceState() {
            activeProfileId = resolveActiveProfileId()
            fallbackSnapshot = SubscriptionConfigStore.loadActiveSnapshot(this@ProxyActivity, activeProfileId)

            if ((fallbackSnapshot == null || fallbackSnapshot?.displayGroups().isNullOrEmpty())) {
                val authData = AuthStore(this@ProxyActivity).authData?.takeIf { it.isNotBlank() }
                if (authData != null) {
                    runCatching {
                        SennetSubscriptionManager.syncSubscription(this@ProxyActivity, authData)
                    }
                    activeProfileId = resolveActiveProfileId()
                    fallbackSnapshot = SubscriptionConfigStore.loadActiveSnapshot(this@ProxyActivity, activeProfileId)
                }
            }

            val runtimeNames = ensureRuntimeGroupsLoaded()
            val displayNames = filterDisplayGroupNames(runtimeNames)
            targetGroupName = if (displayNames.isEmpty() && fallbackSnapshot != null) {
                fallbackSnapshot?.defaultGroup()?.name ?: fallbackSnapshot?.displayGroups()?.firstOrNull()?.name
            } else {
                preferredGroupName(displayNames)
            }
        }

        suspend fun measureSnapshotDelays(group: SubscriptionConfigStore.Group): List<Proxy> {
            val snapshot = fallbackSnapshot ?: return emptyList()

            return withContext(Dispatchers.IO) {
                coroutineScope {
                    group.displayableProxies().map { proxyName ->
                        async {
                            val delay = snapshot.findEndpoint(proxyName)?.let { endpoint ->
                                measureTcpDelay(endpoint.server, endpoint.port)
                            } ?: -1

                            SubscriptionConfigStore.buildProxy(proxyName, delay)
                        }
                    }.awaitAll()
                }
            }
        }

        fun applyCachedDelays(proxies: List<Proxy>): List<Proxy> {
            return proxies.map { proxy ->
                val cached = delayCache[proxy.name] ?: return@map proxy
                if (proxy.delay >= 0) proxy else proxy.copy(delay = cached)
            }
        }

        suspend fun reloadGroup() {
            refreshSourceState()

            val currentGroupName = targetGroupName
            if (currentGroupName.isNullOrBlank()) {
                design.showProxies(emptyList(), "")
                return
            }

            val runtimeGroup = runCatching {
                withClash { queryProxyGroup(currentGroupName, uiStore.proxySort) }
            }.getOrNull()

            val runtimeProxies = runtimeGroup?.let { filterDisplayProxies(it.proxies) }.orEmpty()
            val fallbackGroup = fallbackSnapshot?.findGroup(currentGroupName)
                ?: fallbackSnapshot?.defaultGroup()

            if (runtimeProxies.isEmpty()) {
                if (fallbackGroup != null) {
                    val selected = SubscriptionConfigStore.currentSelection(this, fallbackGroup).orEmpty()
                    val placeholders = fallbackGroup.displayableProxies()
                        .map(SubscriptionConfigStore::buildProxy)
                    design.showProxies(applyCachedDelays(placeholders), selected)
                    return
                }
            }

            design.showProxies(applyCachedDelays(runtimeProxies), runtimeGroup?.now.orEmpty())
        }

        suspend fun awaitDelayRefresh(groupName: String) {
            var latestVisible: List<Proxy> = emptyList()
            val fallbackGroup = fallbackSnapshot?.findGroup(groupName)
                ?: fallbackSnapshot?.defaultGroup()

            repeat(12) {
                val checkedGroup = withClash { queryProxyGroup(groupName, uiStore.proxySort) }
                latestVisible = filterDisplayProxies(checkedGroup.proxies)

                if (latestVisible.any { it.delay >= 0 }) {
                    delayCache.clear()
                    latestVisible.forEach { proxy ->
                        if (proxy.delay >= 0) delayCache[proxy.name] = proxy.delay
                    }
                    design.showProxies(latestVisible, checkedGroup.now)
                    return
                }

                delay(250)
            }

            if (fallbackGroup != null) {
                val selected = SubscriptionConfigStore.currentSelection(this@ProxyActivity, fallbackGroup).orEmpty()
                val measured = measureSnapshotDelays(fallbackGroup)
                if (measured.isNotEmpty()) {
                    delayCache.clear()
                    measured.forEach { proxy ->
                        if (proxy.delay >= 0) delayCache[proxy.name] = proxy.delay
                    }
                    design.showProxies(measured, selected)
                    return
                }
            }

            reloadGroup()
        }

        reloadGroup()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded,
                        Event.ProfileChanged -> {
                            launch { reloadGroup() }
                        }

                        else -> Unit
                    }
                }

                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReloadAll -> launch { reloadGroup() }
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)
                            finish()
                        }
                        ProxyDesign.Request.Refresh -> {
                            launch {
                                val currentGroupName = targetGroupName
                                if (!currentGroupName.isNullOrBlank()) {
                                    val fallbackGroup = fallbackSnapshot?.findGroup(currentGroupName)
                                        ?: fallbackSnapshot?.defaultGroup()

                                    if (fallbackGroup != null) {
                                        val selected = SubscriptionConfigStore.currentSelection(this@ProxyActivity, fallbackGroup).orEmpty()
                                        val measured = measureSnapshotDelays(fallbackGroup)
                                        if (measured.isNotEmpty()) {
                                            delayCache.clear()
                                            measured.forEach { proxy ->
                                                if (proxy.delay >= 0) delayCache[proxy.name] = proxy.delay
                                            }
                                            design.showProxies(measured, selected)
                                            return@launch
                                        }
                                    }

                                    val runtimeGroupExists = runCatching {
                                        withClash { queryProxyGroup(currentGroupName, uiStore.proxySort) }
                                    }.getOrNull() != null

                                    if (runtimeGroupExists) {
                                        runCatching {
                                            withClash { healthCheck(currentGroupName) }
                                        }.onSuccess {
                                            awaitDelayRefresh(currentGroupName)
                                        }.onFailure {
                                            reloadGroup()
                                        }
                                    } else {
                                        val fallbackGroup = fallbackSnapshot?.findGroup(currentGroupName)
                                            ?: fallbackSnapshot?.defaultGroup()

                                        if (fallbackGroup != null) {
                                            val selected = SubscriptionConfigStore.currentSelection(this@ProxyActivity, fallbackGroup).orEmpty()
                                            val measured = measureSnapshotDelays(fallbackGroup)
                                            delayCache.clear()
                                            measured.forEach { proxy ->
                                                if (proxy.delay >= 0) delayCache[proxy.name] = proxy.delay
                                            }
                                            design.showProxies(measured, selected)
                                        } else {
                                            reloadGroup()
                                        }
                                    }
                                    return@launch
                                }

                                reloadGroup()
                            }
                        }

                        ProxyDesign.Request.UpdateSubscription -> {
                            launch {
                                try {
                                    val authData = AuthStore(this@ProxyActivity).authData
                                        ?.takeIf { it.isNotBlank() }
                                        ?: throw IllegalStateException(getString(R.string.sennet_login_invalid_credentials))

                                    SennetSubscriptionManager.syncSubscription(this@ProxyActivity, authData)
                                    delayCache.clear()
                                    reloadGroup()
                                } catch (e: Exception) {
                                    design.showExceptionToast(e)
                                }
                            }
                        }

                        ProxyDesign.Request.OpenHome -> {
                            launchTab(MainActivity::class.intent)
                        }

                        ProxyDesign.Request.OpenAccount -> {
                            launchTab(AccountActivity::class.intent)
                        }

                        ProxyDesign.Request.OpenSettings -> {
                            launchTab(SettingsActivity::class.intent)
                        }

                        is ProxyDesign.Request.PatchMode -> {
                            // UI no longer exposes mode switching, but keep compatibility with old menu wiring.
                            launch { reloadGroup() }
                        }

                        is ProxyDesign.Request.Select -> {
                            val currentGroupName = targetGroupName
                            if (currentGroupName.isNullOrBlank()) {
                                return@onReceive
                            }

                            val runtimeGroupAvailable = runCatching {
                                withClash { queryProxyGroup(currentGroupName, uiStore.proxySort) }
                            }.getOrNull()?.proxies?.any { !it.type.group } == true

                            if (!runtimeGroupAvailable) {
                                SubscriptionConfigStore.applySelection(
                                    this@ProxyActivity,
                                    activeProfileId,
                                    currentGroupName,
                                    it.name,
                                )
                            } else {
                                withClash { patchSelector(currentGroupName, it.name) }
                            }

                            design.markSelected(it.name)
                        }
                    }
                }
            }
        }
    }

    private fun preferredGroupName(names: List<String>): String? {
        return listOf("SENVIET", "Proxy", "GLOBAL", "AUTO")
            .firstOrNull { names.contains(it) }
            ?: names.firstOrNull()
    }

    private fun filterDisplayGroupNames(names: List<String>): List<String> {
        val filtered = names.filterNot(SubscriptionConfigStore::isHiddenGroupName)
        return if (filtered.isNotEmpty()) filtered else names
    }

    private fun filterDisplayProxies(proxies: List<Proxy>): List<Proxy> {
        val filtered = proxies.filter { proxy ->
            !proxy.type.group &&
                !SubscriptionConfigStore.isInfoNode(proxy.name) &&
                !SubscriptionConfigStore.isHiddenGroupName(proxy.name)
        }

        if (filtered.isNotEmpty()) {
            return filtered
        }

        return proxies.filterNot { it.type.group }
    }

    private suspend fun resolveActiveProfileId(): UUID? {
        val stored = ServiceStore(this).activeProfile
        if (stored != null && SubscriptionConfigStore.loadActiveSnapshot(this, stored) != null) {
            return stored
        }

        return withProfile { queryActive() }?.uuid ?: stored
    }

    private fun measureTcpDelay(server: String, port: Int): Int {
        return runCatching {
            val startedAt = SystemClock.elapsedRealtime()

            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, port), 1500)
            }

            (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtLeast(1)
        }.getOrElse {
            0
        }
    }
}
