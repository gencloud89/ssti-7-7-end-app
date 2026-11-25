package com.github.kr328.clash.design.component

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.model.ProxyState
import kotlin.math.absoluteValue
import kotlin.math.max

class ProxyViewState(
    val config: ProxyViewConfig,
    val proxy: Proxy,
    private val parent: ProxyState,
    private val link: ProxyState?
) {
    val paint = Paint()
    val rect = Rect()
    val path = Path()

    var title: String = ""
    var subtitle: String = ""
    var delayText: String = ""
    var background: Int = config.unselectedBackground
    var controls: Int = config.unselectedControl
    var subtitleColor: Int = config.unselectedSubtitle
    var delayColor: Int = config.unselectedSubtitle

    private var delay: Int = 0
    private var selected: Boolean = false
    private var parentNow: String = ""
    private var linkNow: String? = null

    private var lastFrameTime = System.currentTimeMillis()

    fun update(snap: Boolean): Boolean {
        val frameTime = System.currentTimeMillis()
        var invalidate = false

        if (parentNow !== parent.now) {
            parentNow = parent.now
            selected = proxy.name == parent.now
        }

        if (proxy.type.group) {
            title = proxy.name

            if (link == null) {
                subtitle = proxy.type.name
            } else if (linkNow !== link.now) {
                linkNow = link.now
                subtitle = "%s(%s)".format(proxy.type.name, link.now.ifEmpty { "*" })
            }
        } else {
            title = "${flagFor(proxy.name)} ${formatProxyName(proxy.name)}".trim()
            subtitle = if (selected) "Đang chọn" else formatSubtitle(proxy)
        }

        if (delay != proxy.delay) {
            delay = proxy.delay
            delayText = when {
                proxy.delay in 1..Short.MAX_VALUE -> "${proxy.delay} ms"
                proxy.delay == 0 -> "timeout"
                else -> "--"
            }
        }

        controls = if (selected) config.selectedControl else config.unselectedControl
        subtitleColor = if (selected) config.selectedSubtitle else config.unselectedSubtitle
        delayColor = when {
            delay <= 0 -> Color.parseColor("#7F97BA")
            delay < 200 -> Color.parseColor("#3FB950")
            delay < 500 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#F85149")
        }

        if (snap) {
            background = if (selected) config.selectedBackground else config.unselectedBackground
        } else {
            val target = if (selected) config.selectedBackground else config.unselectedBackground

            if (background != target) {
                val sa = Color.alpha(background)
                val sr = Color.red(background)
                val sg = Color.green(background)
                val sb = Color.blue(background)

                val ta = Color.alpha(target)
                val tr = Color.red(target)
                val tg = Color.green(target)
                val tb = Color.blue(target)

                val da = ta - sa
                val dr = tr - sr
                val dg = tg - sg
                val db = tb - sb

                val maxChannel = max(
                    da.absoluteValue,
                    max(dr.absoluteValue, max(dg.absoluteValue, db.absoluteValue))
                )

                val frameOffset = frameTime - lastFrameTime
                val colorOffset = (frameOffset / maxChannel.toFloat().coerceAtLeast(0.001f))
                    .coerceIn(0.0f, 1.0f)

                background = if (colorOffset > 0.999f) {
                    target
                } else {
                    Color.argb(
                        (sa + da * colorOffset).toInt(),
                        (sr + dr * colorOffset).toInt(),
                        (sg + dg * colorOffset).toInt(),
                        (sb + db * colorOffset).toInt()
                    )
                }

                invalidate = true
            }
        }

        lastFrameTime = frameTime
        return invalidate
    }

    private fun formatProxyName(name: String): String {
        val normalized = name.replace(Regex("\\s*\\|\\s*"), " · ")
        return if (" - " in normalized) {
            normalized.substringAfter(" - ").trim()
        } else {
            normalized
        }
    }

    private fun formatSubtitle(proxy: Proxy): String {
        val subtitle = proxy.subtitle.trim()
        return when {
            subtitle.isBlank() -> proxy.type.name
            subtitle == proxy.name -> proxy.type.name
            else -> subtitle
        }
    }

    private fun flagFor(name: String): String {
        val normalized = name.lowercase()
        val countryCode = when {
            normalized.contains("vietnam") ||
                    normalized.contains("việt nam") ||
                    normalized.contains("-vn") ||
                    normalized.contains("-hn") ||
                    normalized.contains("-hp") ||
                    normalized.contains("-dn") ||
                    normalized.contains("-qn") ||
                    normalized.contains("-qb") -> "VN"
            normalized.contains("singapore") || normalized.contains("-sg") -> "SG"
            normalized.contains("japan") || normalized.contains("-jp") -> "JP"
            normalized.contains("hong") || normalized.contains("-hk") -> "HK"
            normalized.contains("korea") || normalized.contains("-kr") -> "KR"
            normalized.contains("usa") || normalized.contains("-us") -> "US"
            else -> return "\uD83C\uDF10"
        }

        return buildString {
            countryCode.forEach { char ->
                append(Character.toChars(char.code + 127397))
            }
        }
    }
}
