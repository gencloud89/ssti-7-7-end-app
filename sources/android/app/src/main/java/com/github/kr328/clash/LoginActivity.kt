package com.github.kr328.clash

import com.github.kr328.clash.common.store.AuthStore
import com.github.kr328.clash.design.LoginDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.SennetSubscriptionManager
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class LoginActivity : BaseActivity<LoginDesign>() {
    override suspend fun main() {
        val design = LoginDesign(this)
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { /* no-op */ }
                design.requests.onReceive { request ->
                    when (request) {
                        LoginDesign.Request.Login -> handleLogin(design)
                    }
                }
            }
        }
    }

    private suspend fun handleLogin(design: LoginDesign) {
        val creds = design.getCredentials() ?: return
        design.clearError()
        design.setLoading(true)
        design.hideKeyboard()

        try {
            val result = V2BoardApiClient.login(creds.email, creds.password)
            val session = SennetSubscriptionManager.syncSubscription(this, result.authData)

            val authStore = AuthStore(this)
            authStore.authData = result.authData
            authStore.authEmail = creds.email
            authStore.expiredAt = session.userInfo.expiredAt
            authStore.trafficUpload = session.userInfo.upload
            authStore.trafficDownload = session.userInfo.download
            authStore.trafficTotal = session.userInfo.total
            authStore.planName = session.userInfo.planName

            startActivity(MainActivity::class.java.let {
                android.content.Intent(this, it).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            })
            finish()
        } catch (e: V2BoardApiClient.UnauthorizedException) {
            design.setLoading(false)
            design.showError(getString(R.string.sennet_login_invalid_credentials))
        } catch (e: Exception) {
            design.setLoading(false)
            design.showError(
                e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.sennet_login_generic_error),
            )
        }
    }

    override fun shouldDisplayHomeAsUpEnabled(): Boolean = false
}
