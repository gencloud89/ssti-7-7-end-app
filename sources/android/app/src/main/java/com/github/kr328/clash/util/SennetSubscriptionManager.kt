package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.V2BoardApiClient
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.delay
import java.util.UUID

object SennetSubscriptionManager {
    private const val PROFILE_NAME = "SENNET VPN"

    suspend fun syncSubscription(context: Context, authData: String): V2BoardApiClient.SessionBundle {
        val session = V2BoardApiClient.getSessionBundle(authData)
        val configContent = V2BoardApiClient.fetchSubscriptionContent(authData, session.subscribeUrl)

        withProfile {
            val existing = queryAll()
                .firstOrNull { it.name == PROFILE_NAME && it.type == Profile.Type.File }
                ?: queryAll().firstOrNull { it.name == PROFILE_NAME }

            val profileUuid = when {
                existing == null -> create(Profile.Type.File, PROFILE_NAME)
                existing.type == Profile.Type.File -> existing.uuid
                else -> {
                    delete(existing.uuid)
                    create(Profile.Type.File, PROFILE_NAME)
                }
            }

            patch(profileUuid, PROFILE_NAME, "", 0L)

            val pendingBase = context.pendingDir.resolve(profileUuid.toString()).apply {
                mkdirs()
            }
            pendingBase.resolve("providers").mkdirs()
            pendingBase.resolve("config.yaml").writeText(configContent)

            commit(profileUuid)
            ensureImportedSnapshot(context, profileUuid)

            val imported = queryByUUID(profileUuid)
                ?: throw IllegalStateException(context.getString(R.string.sennet_profile_create_failed))

            if (!imported.imported) {
                throw IllegalStateException(context.getString(R.string.sennet_profile_create_failed))
            }

            setActive(imported)
        }

        return session
    }

    private suspend fun IProfileManager.ensureImportedSnapshot(context: Context, profileUuid: UUID) {
        val importedBase = context.importedDir.resolve(profileUuid.toString())
        val importedConfig = importedBase.resolve("config.yaml")

        repeat(8) {
            if (importedConfig.exists() && importedConfig.length() > 0L) {
                return
            }

            delay(150)
        }

        val pendingBase = context.pendingDir.resolve(profileUuid.toString())
        val pendingConfig = pendingBase.resolve("config.yaml")
        if (!pendingConfig.exists() || pendingConfig.length() == 0L) {
            return
        }

        importedBase.deleteRecursively()
        pendingBase.copyRecursively(importedBase, overwrite = true)
        release(profileUuid)
    }
}
