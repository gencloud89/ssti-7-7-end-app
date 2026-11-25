package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.store.AuthStore
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.SubscriptionConfigStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.UUID

class MainActivity : BaseActivity<MainDesign>() {
    private var lastTrafficSnapshot: Long? = null
    private var lastTrafficAt = 0L
    private var cachedProxyName = ""
    private var lastProxyRefreshAt = 0L

    override suspend fun main() {
        val authStore = AuthStore(this)
        if (!authStore.isLoggedIn()) {
            startActivity(LoginActivity::class.intent)
            finish()
            return
        }

        val design = MainDesign(this)
        setContentDesign(design)
        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop,
                        Event.ClashStart,
                        Event.ProfileLoaded,
                        Event.ProfileChanged -> design.fetch()

                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                stopClashService()
                            } else {
                                design.startClash()
                            }
                        }

                        MainDesign.Request.OpenProxy -> launchTab(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles -> startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders -> startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }

                        MainDesign.Request.OpenSettings -> launchTab(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp -> startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout -> design.showAbout(queryAppVersionName())
                        MainDesign.Request.OpenAccount -> launchTab(AccountActivity::class.intent)
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)
        setCurrentProxy(queryCurrentProxyName(forceRefresh = true))

        if (!clashRunning) {
            lastTrafficSnapshot = null
            lastTrafficAt = 0L
            lastProxyRefreshAt = 0L
            setUploadSpeed("0 KB/s")
            setDownloadSpeed("0 KB/s")
            return
        }

        fetchTraffic()
    }

    private suspend fun MainDesign.fetchTraffic() {
        val packed = try {
            withClash { queryTrafficTotal() }
        } catch (_: Exception) {
            setClashRunning(clashRunning)
            if (!clashRunning) {
                lastTrafficSnapshot = null
                lastTrafficAt = 0L
                setUploadSpeed("0 KB/s")
                setDownloadSpeed("0 KB/s")
            }
            return
        }
        val now = System.currentTimeMillis()

        val upload = decodeTrafficPart(packed ushr 32)
        val download = decodeTrafficPart(packed and 0xFFFFFFFFL)

        val previous = lastTrafficSnapshot
        val elapsedSeconds = ((now - lastTrafficAt).coerceAtLeast(1L)) / 1000.0

        val uploadSpeed = if (previous != null && lastTrafficAt > 0L) {
            ((upload - decodeTrafficPart(previous ushr 32)).coerceAtLeast(0L) / elapsedSeconds).toLong()
        } else {
            0L
        }

        val downloadSpeed = if (previous != null && lastTrafficAt > 0L) {
            ((download - decodeTrafficPart(previous and 0xFFFFFFFFL)).coerceAtLeast(0L) / elapsedSeconds).toLong()
        } else {
            0L
        }

        lastTrafficSnapshot = packed
        lastTrafficAt = now

        setUploadSpeed(formatSpeed(uploadSpeed))
        setDownloadSpeed(formatSpeed(downloadSpeed))

        if (now - lastProxyRefreshAt >= TimeUnit.SECONDS.toMillis(8) || cachedProxyName.isBlank()) {
            setCurrentProxy(queryCurrentProxyName(forceRefresh = true))
        }
    }

    private suspend fun queryCurrentProxyName(forceRefresh: Boolean): String {
        if (!forceRefresh && cachedProxyName.isNotBlank()) {
            return cachedProxyName
        }

        val fromClash: String? = try {
            withClash {
                val groups = queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                val preferred = listOf("SENVIET", "Proxy")
                    .firstOrNull { groups.contains(it) }
                    ?: groups.firstOrNull()

                if (preferred == null) {
                    null
                } else {
                    formatProxyName(queryProxyGroup(preferred, uiStore.proxySort).now)
                }
            }
        } catch (_: Exception) {
            null
        }

        val resolved = fromClash ?: contextFallbackProxyName()
        cachedProxyName = resolved
        lastProxyRefreshAt = System.currentTimeMillis()
        return resolved
    }

    private suspend fun contextFallbackProxyName(): String {
        val activeProfile = withProfile { queryActive() }
        val activeProfileId = activeProfile?.uuid ?: resolveActiveProfileId()
        val snapshot = SubscriptionConfigStore.loadActiveSnapshot(this, activeProfileId)
        val selectedProxy = snapshot
            ?.defaultGroup()
            ?.let { SubscriptionConfigStore.currentSelection(this, it) }

        return formatProxyName(selectedProxy ?: activeProfile?.name ?: getString(R.string.not_selected))
    }

    private fun formatProxyName(name: String): String {
        val trimmed = name.trim()
        val normalizedKey = trimmed.lowercase()

        return when {
            trimmed.isBlank() -> getString(R.string.not_selected)
            trimmed == "è‡ªåŠ¨é€‰æ‹©" || normalizedKey == "auto" || normalizedKey == "auto select" -> {
                getString(R.string.sennet_auto_select)
            }

            trimmed == "æ•…éšœè½¬ç§»" || normalizedKey == "fallback" || normalizedKey == "failover" -> {
                getString(R.string.sennet_failover)
            }

            else -> {
                val normalized = trimmed.replace(Regex("\\s*\\|\\s*"), " \u00B7 ")
                if (" - " in normalized) {
                    normalized.substringAfter(" - ").trim()
                } else {
                    normalized
                }
            }
        }
    }

    private fun decodeTrafficPart(value: Long): Long {
        val type = (value ushr 30) and 0x3
        val data = value and 0x3FFFFFFF

        return when (type) {
            0L -> data
            1L -> data * 1024
            2L -> data * 1024 * 1024
            3L -> data * 1024 * 1024 * 1024
            else -> 0L
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format("%.1f MB/s", bytesPerSecond / 1024f / 1024f)
        }
        if (bytesPerSecond >= 1024) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024f)
        }
        return "$bytesPerSecond B/s"
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }
            return
        }

        SubscriptionConfigStore.loadActiveSnapshot(this@MainActivity, active.uuid)
            ?.defaultGroup()
            ?.let { group ->
                SubscriptionConfigStore.currentSelection(this@MainActivity, group)?.let { proxy ->
                    SubscriptionConfigStore.applySelection(this@MainActivity, active.uuid, group.name, proxy)
                }
            }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest,
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                }
            }
        } catch (_: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName +
                "\n" +
                Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    private suspend fun resolveActiveProfileId(): UUID? {
        val stored = ServiceStore(this).activeProfile
        if (stored != null && SubscriptionConfigStore.loadActiveSnapshot(this, stored) != null) {
            return stored
        }

        return withProfile { queryActive() }?.uuid ?: stored
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
