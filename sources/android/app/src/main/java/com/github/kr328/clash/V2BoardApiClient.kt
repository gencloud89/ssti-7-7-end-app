package com.github.kr328.clash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object V2BoardApiClient {
    private const val FALLBACK_BASE_URL = "https://s1.example.com"
    private const val REMOTE_CONFIG_URL = "https://buildapp.htd892.com/remote-config/ssti-7-7-end.json"
    private const val USER_AGENT = "SSTI-7-7-END/1.0 clash-compatible"

    @Volatile
    private var cachedBaseUrl: String = FALLBACK_BASE_URL

    @Volatile
    private var lastRemoteConfigSyncAt: Long = 0

    data class LoginResult(val authData: String)

    data class SessionBundle(
        val userInfo: UserInfo,
        val subscribeUrl: String,
    )

    data class UserInfo(
        val email: String,
        val expiredAt: Long?,
        val upload: Long,
        val download: Long,
        val total: Long,
        val planName: String,
        val resetDay: Int?,
    )

    private data class RemoteConfig(
        val panelUrl: String,
    )

    class UnauthorizedException : IOException("Token expired or invalid (HTTP 403)")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${resolveBaseUrl()}/api/v1/passport/auth/login")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        when (response.code) {
            200 -> {
                val json = JSONObject(response.body!!.string())
                LoginResult(json.getJSONObject("data").getString("auth_data"))
            }
            403, 422 -> throw UnauthorizedException()
            else -> throw IOException("HTTP ${response.code}")
        }
    }

    suspend fun getSubscribeUrl(authData: String): String = withContext(Dispatchers.IO) {
        fetchSubscribeData(authData).getString("subscribe_url")
    }

    suspend fun getUserInfo(authData: String): UserInfo = withContext(Dispatchers.IO) {
        val data = fetchSubscribeData(authData)
        parseUserInfo(data)
    }

    suspend fun getSessionBundle(authData: String): SessionBundle = withContext(Dispatchers.IO) {
        val data = fetchSubscribeData(authData)
        SessionBundle(
            userInfo = parseUserInfo(data),
            subscribeUrl = data.getString("subscribe_url"),
        )
    }

    suspend fun fetchSubscriptionContent(authData: String, subscribeUrl: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(subscribeUrl)
            .get()
            .header("Authorization", authData)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        when (response.code) {
            200 -> response.body?.string().orEmpty()
            403 -> throw UnauthorizedException()
            else -> throw IOException("HTTP ${response.code}")
        }
    }

    private suspend fun fetchSubscribeData(authData: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${resolveBaseUrl()}/api/v1/user/getSubscribe")
            .get()
            .header("Authorization", authData)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        when (response.code) {
            200 -> JSONObject(response.body!!.string()).getJSONObject("data")
            403 -> throw UnauthorizedException()
            else -> throw IOException("HTTP ${response.code}")
        }
    }

    private suspend fun resolveBaseUrl(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastRemoteConfigSyncAt < 300_000 && cachedBaseUrl.isNotBlank()) {
            return@withContext cachedBaseUrl
        }

        runCatching { fetchRemoteConfig() }
            .onSuccess { config ->
                cachedBaseUrl = normalizeBaseUrl(config.panelUrl)
                lastRemoteConfigSyncAt = now
            }
            .onFailure {
                if (cachedBaseUrl.isBlank()) {
                    cachedBaseUrl = normalizeBaseUrl(FALLBACK_BASE_URL)
                }
            }

        cachedBaseUrl
    }

    private fun fetchRemoteConfig(): RemoteConfig {
        val request = Request.Builder()
            .url(REMOTE_CONFIG_URL)
            .get()
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Remote config HTTP ${response.code}")
        }

        val body = response.body?.string().orEmpty()
        val json = JSONObject(body)
        val panelUrl = json.optString("panel_url").trim()
        if (panelUrl.isBlank()) {
            throw IOException("Remote config missing panel_url")
        }

        return RemoteConfig(panelUrl = panelUrl)
    }

    private fun normalizeBaseUrl(url: String): String {
        return url.trim().trimEnd('/')
    }

    private fun parseUserInfo(data: JSONObject): UserInfo {
        return UserInfo(
            email = data.getString("email"),
            expiredAt = if (data.isNull("expired_at")) null else data.getLong("expired_at"),
            upload = data.optLong("u", 0),
            download = data.optLong("d", 0),
            total = data.optLong("transfer_enable", 0),
            planName = data.optJSONObject("plan")?.optString("name", "Unknown") ?: "Unknown",
            resetDay = if (data.isNull("reset_day")) null else data.optInt("reset_day"),
        )
    }
}
