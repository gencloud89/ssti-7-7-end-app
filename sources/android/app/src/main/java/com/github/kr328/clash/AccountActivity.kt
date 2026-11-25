package com.github.kr328.clash

import com.github.kr328.clash.common.store.AuthStore
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.AccountDesign
import com.github.kr328.clash.design.R
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class AccountActivity : BaseActivity<AccountDesign>() {
    companion object {
        private const val DEBUG_PREVIEW_TOKEN = "debug-preview-token"
    }

    override suspend fun main() {
        title = getString(R.string.sennet_account)
        val design = AccountDesign(this)
        setContentDesign(design)

        val authStore = AuthStore(this)

        design.setUserInfo(
            email = authStore.authEmail ?: "",
            planName = authStore.planName,
            expiredAt = authStore.expiredAt,
            upload = authStore.trafficUpload,
            download = authStore.trafficDownload,
            total = authStore.trafficTotal,
        )

        launch {
            try {
                val authData = authStore.authData
                if (authData != null && authData != DEBUG_PREVIEW_TOKEN) {
                    val userInfo = V2BoardApiClient.getUserInfo(authData)
                    authStore.expiredAt = userInfo.expiredAt
                    authStore.trafficUpload = userInfo.upload
                    authStore.trafficDownload = userInfo.download
                    authStore.trafficTotal = userInfo.total
                    authStore.planName = userInfo.planName

                    design.setUserInfo(
                        email = userInfo.email,
                        planName = userInfo.planName,
                        expiredAt = userInfo.expiredAt,
                        upload = userInfo.upload,
                        download = userInfo.download,
                        total = userInfo.total,
                    )
                }
            } catch (_: V2BoardApiClient.UnauthorizedException) {
                if (authStore.authData != DEBUG_PREVIEW_TOKEN) {
                    authStore.clear()
                    startActivity(LoginActivity::class.intent)
                    finish()
                }
            } catch (_: Exception) {
                // Show cached data when the network refresh fails.
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive { /* no-op */ }
                design.requests.onReceive { request ->
                    when (request) {
                        AccountDesign.Request.Logout -> {
                            authStore.clear()
                            startActivity(LoginActivity::class.intent.apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                        }

                        AccountDesign.Request.OpenHome -> {
                            launchTab(MainActivity::class.intent)
                        }

                        AccountDesign.Request.OpenProxy -> {
                            launchTab(ProxyActivity::class.intent)
                        }

                        AccountDesign.Request.OpenSettings -> {
                            launchTab(SettingsActivity::class.intent)
                        }
                    }
                }
            }
        }
    }
}
