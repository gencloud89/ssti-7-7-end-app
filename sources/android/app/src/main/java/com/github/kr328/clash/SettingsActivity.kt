package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.design.R
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class SettingsActivity : BaseActivity<SettingsDesign>() {
    override suspend fun main() {
        title = getString(R.string.sennet_screen_settings)
        val design = SettingsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // no-op
                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.OpenHome ->
                            launchTab(MainActivity::class.intent)
                        SettingsDesign.Request.OpenProxy ->
                            launchTab(ProxyActivity::class.intent)
                        SettingsDesign.Request.OpenAccount ->
                            launchTab(AccountActivity::class.intent)
                    }
                }
            }
        }
    }
}
