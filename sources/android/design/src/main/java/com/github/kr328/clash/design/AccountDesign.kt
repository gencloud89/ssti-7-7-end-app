package com.github.kr328.clash.design

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class AccountDesign(context: Context) : Design<AccountDesign.Request>(context) {
    enum class Request {
        Logout,
        OpenHome,
        OpenProxy,
        OpenSettings,
    }

    private val rootView: View = LayoutInflater.from(context)
        .inflate(R.layout.design_account, null, false)

    private val emailText: TextView = rootView.findViewById(R.id.email_text)
    private val planBadgeText: TextView = rootView.findViewById(R.id.plan_text)
    private val packageText: TextView = rootView.findViewById(R.id.traffic_used_text)
    private val expiryValue: TextView = rootView.findViewById(R.id.expiry_value)
    private val trafficProgress: LinearProgressIndicator = rootView.findViewById(R.id.traffic_progress)
    private val trafficPctText: TextView = rootView.findViewById(R.id.traffic_pct_text)
    private val usageLine: TextView = rootView.findViewById(R.id.upload_text)
    private val remainingBadge: TextView = rootView.findViewById(R.id.download_text)
    private val logoutButton: MaterialButton = rootView.findViewById(R.id.logout_button)
    private val homeButton: View = rootView.findViewById(R.id.home_button)
    private val proxyButton: View = rootView.findViewById(R.id.proxy_button)
    private val settingsButton: View = rootView.findViewById(R.id.settings_button)

    override val root: View
        get() = rootView

    init {
        logoutButton.setOnClickListener { requests.trySend(Request.Logout) }
        homeButton.setOnClickListener { requests.trySend(Request.OpenHome) }
        proxyButton.setOnClickListener { requests.trySend(Request.OpenProxy) }
        settingsButton.setOnClickListener { requests.trySend(Request.OpenSettings) }
    }

    suspend fun setUserInfo(
        email: String,
        planName: String,
        expiredAt: Long?,
        upload: Long,
        download: Long,
        total: Long,
    ) = withContext(Dispatchers.Main) {
        val safePlan = planName.ifBlank { "Premium 12 Thang" }
        val used = upload + download
        val pct = if (total > 0) {
            ((used.toDouble() / total) * 100).coerceIn(0.0, 100.0).toInt()
        } else {
            0
        }

        emailText.text = email
        planBadgeText.text = if (safePlan.contains("premium", ignoreCase = true)) {
            context.getString(R.string.sennet_account_premium)
        } else {
            safePlan.uppercase(Locale.getDefault())
        }
        packageText.text = safePlan

        expiryValue.text = if (expiredAt == null) {
            context.getString(R.string.sennet_unlimited)
        } else {
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(expiredAt * 1000))
        }

        remainingBadge.text = expiredAt?.let(::formatRemainingDays)
            ?: context.getString(R.string.sennet_unlimited)

        trafficProgress.progress = pct
        trafficPctText.text = "$pct%"
        usageLine.text = "Da dung ${formatBytes(used)} / ${formatBytes(total)}"
    }

    private fun formatRemainingDays(expiredAtSeconds: Long): String {
        val nowSeconds = System.currentTimeMillis() / 1000
        val days = ceil(((expiredAtSeconds - nowSeconds).coerceAtLeast(0)) / 86400.0).toInt()
        return "Con ${days.coerceAtLeast(0)} ngay"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 GB"
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1) return "%.1f GB".format(Locale.US, gb)
        return "%.0f MB".format(Locale.US, bytes / 1_048_576.0)
    }
}
