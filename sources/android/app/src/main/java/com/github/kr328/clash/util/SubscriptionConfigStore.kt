package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.core.model.Proxy
import java.io.File
import java.util.UUID

object SubscriptionConfigStore {
    private const val PREFS_NAME = "sennet_proxy_selection"

    data class Group(
        val name: String,
        val type: Proxy.Type,
        val proxies: List<String>,
    ) {
        fun displayableProxies(): List<String> {
            val filtered = proxies.filterNot { isInfoNode(it) || isHiddenGroupName(it) }
            return if (filtered.isNotEmpty()) filtered else proxies.filterNot(::isHiddenGroupName)
        }
    }

    data class Endpoint(
        val server: String,
        val port: Int,
    )

    data class Snapshot(
        val groups: List<Group>,
        val endpoints: Map<String, Endpoint>,
    ) {
        fun findGroup(name: String): Group? = groups.firstOrNull { it.name == name }
        fun findEndpoint(name: String): Endpoint? = endpoints[name]

        fun displayGroups(): List<Group> {
            val filtered = groups.filterNot { isHiddenGroupName(it.name) }
            return if (filtered.isNotEmpty()) filtered else groups
        }

        fun defaultGroup(): Group? = displayGroups().firstOrNull()
    }

    fun loadActiveSnapshot(context: Context, uuid: UUID?): Snapshot? {
        if (uuid == null) return null

        val config = context.filesDir
            .resolve("imported")
            .resolve(uuid.toString())
            .resolve("config.yaml")

        if (!config.exists()) return null

        return parseSnapshot(config.readText())
    }

    fun currentSelection(context: Context, group: Group): String? {
        val stored = preferences(context).getString(selectionKey(group.name), null)

        return when {
            stored != null && group.proxies.contains(stored) -> stored
            else -> group.displayableProxies().firstOrNull()
        }
    }

    fun saveSelection(context: Context, groupName: String, proxyName: String) {
        preferences(context).edit().putString(selectionKey(groupName), proxyName).apply()
    }

    fun applySelection(context: Context, uuid: UUID?, groupName: String, proxyName: String) {
        if (uuid == null) return

        saveSelection(context, groupName, proxyName)

        val imported = context.filesDir
            .resolve("imported")
            .resolve(uuid.toString())
            .resolve("config.yaml")

        rewriteGroupSelection(imported, groupName, proxyName)

        val processing = context.filesDir.resolve("processing").resolve("config.yaml")
        rewriteGroupSelection(processing, groupName, proxyName)
    }

    fun buildProxy(name: String, delay: Int = -1): Proxy {
        return Proxy(
            name = name,
            title = name,
            subtitle = buildSubtitle(name),
            type = Proxy.Type.Shadowsocks,
            delay = delay,
        )
    }

    fun isHiddenGroupName(name: String): Boolean {
        return name in setOf(
            "自动选择",
            "故障转移",
            "AUTO",
            "Auto",
            "Auto Select",
            "FAILOVER",
            "Failover",
            "Fallback",
            "GLOBAL",
            "DIRECT",
            "REJECT",
        )
    }

    fun isInfoNode(name: String): Boolean {
        return name.startsWith("\uD83D\uDC64") ||
            name.startsWith("\uD83D\uDCDD") ||
            name.startsWith("\uD83D\uDCE8") ||
            name.startsWith("\u23F3") ||
            name.startsWith("Reset data")
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun selectionKey(groupName: String): String = "selection_$groupName"

    private fun parseSnapshot(content: String): Snapshot? {
        val groups = ArrayList<Group>()
        val endpoints = LinkedHashMap<String, Endpoint>()
        val sectionNone = 0
        val sectionProxyGroups = 1
        val sectionProxies = 2
        var section = sectionNone
        var currentName: String? = null
        var currentType = Proxy.Type.Selector
        var currentProxies = mutableListOf<String>()
        var readingProxies = false
        var currentProxyName: String? = null
        var currentServer: String? = null
        var currentPort: Int? = null

        fun flush() {
            val name = currentName ?: return
            groups += Group(name, currentType, currentProxies.toList())
            currentName = null
            currentType = Proxy.Type.Selector
            currentProxies = mutableListOf()
            readingProxies = false
        }

        fun flushEndpoint() {
            val name = currentProxyName ?: return
            val server = currentServer ?: return
            val port = currentPort ?: return

            endpoints[name] = Endpoint(server, port)
            currentProxyName = null
            currentServer = null
            currentPort = null
        }

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimEnd()
            val trimmedStart = line.trimStart()

            if (line.isBlank()) continue

            if (line == "proxy-groups:") {
                flushEndpoint()
                flush()
                section = sectionProxyGroups
                continue
            }

            if (line == "proxies:") {
                flushEndpoint()
                flush()
                section = sectionProxies
                continue
            }

            when (section) {
                sectionProxies -> {
                    if (!rawLine.startsWith(" ") && !line.startsWith("- ") && line.isNotBlank()) {
                        flushEndpoint()
                        section = sectionNone
                        continue
                    }

                    if (trimmedStart.startsWith("- name:")) {
                        flushEndpoint()
                        currentProxyName = parseScalar(trimmedStart.substringAfter(':').trim())
                        continue
                    }

                    when {
                        trimmedStart.startsWith("server:") -> {
                            currentServer = parseScalar(trimmedStart.substringAfter(':').trim())
                        }

                        trimmedStart.startsWith("port:") -> {
                            currentPort = trimmedStart.substringAfter(':').trim().toIntOrNull()
                        }
                    }

                    continue
                }

                sectionProxyGroups -> {
                    if (!rawLine.startsWith(" ") && !line.startsWith("- ") && line.isNotBlank()) {
                        flush()
                        section = sectionNone
                        continue
                    }

                    if (trimmedStart.startsWith("- name:")) {
                        flush()
                        currentName = parseScalar(trimmedStart.substringAfter(':').trim())
                        continue
                    }

                    if (currentName == null) continue

                    when {
                        trimmedStart.startsWith("type:") -> {
                            currentType = parseProxyType(trimmedStart.substringAfter(':').trim())
                        }

                        trimmedStart == "proxies:" -> {
                            readingProxies = true
                        }

                        readingProxies && trimmedStart.startsWith("- ") -> {
                            currentProxies += parseScalar(trimmedStart.removePrefix("- ").trim())
                        }

                        readingProxies -> {
                            readingProxies = false
                        }
                    }

                    continue
                }

                sectionNone -> Unit
                else -> Unit
            }
        }

        flush()
        flushEndpoint()

        return groups.takeIf { it.isNotEmpty() }?.let { Snapshot(it, endpoints) }
    }

    private fun parseProxyType(value: String): Proxy.Type {
        return when (value.lowercase()) {
            "select" -> Proxy.Type.Selector
            "fallback" -> Proxy.Type.Fallback
            "url-test" -> Proxy.Type.URLTest
            "load-balance" -> Proxy.Type.LoadBalance
            "relay" -> Proxy.Type.Relay
            else -> Proxy.Type.Unknown
        }
    }

    private fun parseScalar(value: String): String {
        return value
            .removeSurrounding("'")
            .removeSurrounding("\"")
            .trim()
    }

    private fun rewriteGroupSelection(file: File, groupName: String, proxyName: String) {
        if (!file.exists()) return

        val lines = file.readLines().toMutableList()
        var inProxyGroups = false
        var currentGroup: String? = null
        var readingProxies = false
        var proxyStart = -1
        var proxyEnd = -1
        var selectedLine = -1

        for (index in lines.indices) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()
            val trimmedStart = line.trimStart()

            if (!inProxyGroups) {
                if (line == "proxy-groups:") {
                    inProxyGroups = true
                }
                continue
            }

            if (!rawLine.startsWith(" ") && !line.startsWith("- ") && line.isNotBlank()) {
                break
            }

            if (trimmedStart.startsWith("- name:")) {
                if (currentGroup == groupName && proxyStart >= 0) {
                    proxyEnd = index
                    break
                }

                currentGroup = parseScalar(trimmedStart.substringAfter(':').trim())
                readingProxies = false
                proxyStart = -1
                selectedLine = -1
                continue
            }

            if (currentGroup != groupName) continue

            if (trimmedStart == "proxies:") {
                readingProxies = true
                proxyStart = index + 1
                continue
            }

            if (!readingProxies) continue

            if (trimmedStart.startsWith("- ")) {
                if (parseScalar(trimmedStart.removePrefix("- ").trim()) == proxyName) {
                    selectedLine = index
                }
            } else {
                proxyEnd = index
                break
            }
        }

        if (currentGroup == groupName && proxyStart >= 0 && proxyEnd < 0) {
            proxyEnd = lines.size
        }

        if (proxyStart < 0 || proxyEnd <= proxyStart || selectedLine < proxyStart) return

        val proxyLines = lines.subList(proxyStart, proxyEnd).toMutableList()
        val selectionOffset = selectedLine - proxyStart
        if (selectionOffset !in proxyLines.indices || selectionOffset == 0) return

        val selected = proxyLines.removeAt(selectionOffset)
        proxyLines.add(0, selected)

        lines.subList(proxyStart, proxyEnd).clear()
        lines.addAll(proxyStart, proxyLines)

        file.writeText(lines.joinToString("\n"))
    }

    private fun buildSubtitle(name: String): String {
        val normalized = name.uppercase()
        return when {
            normalized.contains("VIETNAM") -> "Việt Nam"
            normalized.contains("SINGAPORE") -> "Singapore"
            normalized.contains("JAPAN") -> "Nhật Bản"
            normalized.contains("KOREA") -> "Hàn Quốc"
            normalized.contains("HONG") -> "Hồng Kông"
            normalized.contains("USA") -> "Hoa Kỳ"
            else -> "Subscription"
        }
    }
}
