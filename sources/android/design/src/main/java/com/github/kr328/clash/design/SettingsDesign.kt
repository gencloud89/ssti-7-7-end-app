package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.content.edit
import com.github.kr328.clash.design.databinding.DesignSettingsBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class SettingsDesign(context: Context) : Design<SettingsDesign.Request>(context) {
    enum class Request {
        OpenHome,
        OpenProxy,
        OpenAccount,
    }

    private val binding = DesignSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    private val prefs = context.getSharedPreferences("sennet_settings_ui", Context.MODE_PRIVATE)

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.autoConnectSwitch.isChecked = prefs.getBoolean("auto_connect", true)
        binding.killSwitch.isChecked = prefs.getBoolean("kill_switch", true)
        binding.adBlockSwitch.isChecked = prefs.getBoolean("ad_block", true)
        binding.darkModeSwitch.isChecked = prefs.getBoolean("dark_mode", false)

        binding.autoConnectSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("auto_connect", checked) }
        }
        binding.killSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("kill_switch", checked) }
        }
        binding.adBlockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("ad_block", checked) }
        }
        binding.darkModeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("dark_mode", checked) }
        }

        binding.homeButton.setOnClickListener { requests.trySend(Request.OpenHome) }
        binding.proxyButton.setOnClickListener { requests.trySend(Request.OpenProxy) }
        binding.accountButton.setOnClickListener { requests.trySend(Request.OpenAccount) }
    }
}
