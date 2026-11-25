package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
        OpenAccount,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private var currentProxyText = ""
    private var uploadSpeedText = ""
    private var downloadSpeedText = ""

    override val root: View
        get() = binding.root

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            if (binding.clashRunning != running) {
                binding.clashRunning = running
            }
            binding.statusColor = if (running) {
                ContextCompat.getColor(context, R.color.sennet_success)
            } else {
                ContextCompat.getColor(context, R.color.sennet_text_tertiary)
            }
            binding.powerStrokeColor = if (running) {
                ContextCompat.getColor(context, R.color.sennet_border_focus)
            } else {
                ContextCompat.getColor(context, R.color.sennet_border_soft)
            }
            binding.powerIconTint = if (running) {
                ContextCompat.getColor(context, R.color.sennet_text_on_accent)
            } else {
                ContextCompat.getColor(context, R.color.sennet_text_tertiary)
            }
        }
    }

    suspend fun setCurrentProxy(name: String) {
        withContext(Dispatchers.Main) {
            if (currentProxyText != name) {
                currentProxyText = name
                binding.currentProxy = name
            }
        }
    }

    suspend fun setUploadSpeed(value: String) {
        withContext(Dispatchers.Main) {
            if (uploadSpeedText != value) {
                uploadSpeedText = value
                binding.uploadSpeed = value
            }
        }
    }

    suspend fun setDownloadSpeed(value: String) {
        withContext(Dispatchers.Main) {
            if (downloadSpeedText != value) {
                downloadSpeedText = value
                binding.downloadSpeed = value
            }
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val aboutBinding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(aboutBinding.root)
                .show()
        }
    }

    init {
        binding.self = this
        currentProxyText = context.getString(R.string.not_selected)
        uploadSpeedText = "0 KB/s"
        downloadSpeedText = "0 KB/s"
        binding.currentProxy = currentProxyText
        binding.uploadSpeed = uploadSpeedText
        binding.downloadSpeed = downloadSpeedText
        binding.colorClashStarted = ContextCompat.getColor(context, R.color.sennet_accent)
        binding.colorClashStopped = ContextCompat.getColor(context, R.color.sennet_surface_card_alt)
        binding.statusColor = ContextCompat.getColor(context, R.color.sennet_text_tertiary)
        binding.powerStrokeColor = ContextCompat.getColor(context, R.color.sennet_border_soft)
        binding.powerIconTint = ContextCompat.getColor(context, R.color.sennet_text_tertiary)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
