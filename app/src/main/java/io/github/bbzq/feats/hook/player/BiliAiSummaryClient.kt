package io.github.bbzq.feats.hook.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.TimeUnit

internal data class AiSummaryPart(
    val timestampSec: Long,
    val content: String,
)

internal data class AiSummarySection(
    val title: String,
    val timestampSec: Long,
    val parts: List<AiSummaryPart>,
)

internal data class AiSummaryResult(
    val summary: String,
    val sections: List<AiSummarySection>,
)

internal sealed class AiSummaryFetchOutcome {
    data class Success(val result: AiSummaryResult) : AiSummaryFetchOutcome()
    data class Unavailable(val reason: String) : AiSummaryFetchOutcome()
    data class Error(val message: String) : AiSummaryFetchOutcome()
}

internal object BiliAiSummaryClient {
    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view?bvid="
    private const val CONCLUSION_URL = "https://api.bilibili.com/x/web-interface/view/conclusion/get"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val WBI_CACHE_TTL_MS = 60 * 60 * 1000L

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    @Volatile
    private var cachedMixinKey: String? = null

    @Volatile
    private var cachedMixinKeyAtMs: Long = 0L

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun fetch(bvid: String, knownCid: Long?, cookies: String): AiSummaryFetchOutcome {
        if (cookies.isBlank()) {
            return AiSummaryFetchOutcome.Error("未检测到登录 Cookie，请先登录 B 站账号")
        }
        return runCatching {
            val (cid, upMid) = fetchViewMeta(bvid, knownCid, cookies)
                ?: return AiSummaryFetchOutcome.Error("获取视频元数据（cid / up_mid）失败")
            val mixinKey = resolveMixinKey(cookies)
                ?: return AiSummaryFetchOutcome.Error("获取 WBI 签名密钥失败")

            val params = TreeMap<String, String>().apply {
                put("bvid", bvid)
                put("cid", cid.toString())
                put("up_mid", upMid.toString())
                put("wts", (System.currentTimeMillis() / 1000L).toString())
            }
            val queryWithoutSign = params.entries.joinToString("&") { (k, v) ->
                "${encodeComponent(k)}=${encodeComponent(v.filterNot { it in "!'()*" })}"
            }
            val wRid = md5Hex(queryWithoutSign + mixinKey)
            val url = "$CONCLUSION_URL?$queryWithoutSign&w_rid=$wRid"

            val body = executeGet(url, cookies)
                ?: return AiSummaryFetchOutcome.Error("请求 AI 总结接口失败")
            val root = JSONObject(body)
            val apiCode = root.optInt("code", Int.MIN_VALUE)
            if (apiCode != 0) {
                return AiSummaryFetchOutcome.Error("接口返回错误 ($apiCode)：${root.optString("message")}")
            }
            val data = root.optJSONObject("data")
                ?: return AiSummaryFetchOutcome.Unavailable("接口未返回总结数据")
            val dataCode = data.optInt("code", 0)
            if (dataCode != 0) {
                val reason = when (dataCode) {
                    1 -> "该视频暂不支持或尚未生成 AI 总结（可能缺少语音/字幕）"
                    -1 -> "该视频不支持 AI 总结"
                    else -> "暂无 AI 总结 (code=$dataCode)"
                }
                return AiSummaryFetchOutcome.Unavailable(reason)
            }
            val modelResult = data.optJSONObject("model_result")
                ?: return AiSummaryFetchOutcome.Unavailable("该视频暂无 AI 总结内容")

            val summary = modelResult.optString("summary").trim()
            val outlineArr = modelResult.optJSONArray("outline")
            val sections = buildList {
                if (outlineArr != null) {
                    for (i in 0 until outlineArr.length()) {
                        val obj = outlineArr.optJSONObject(i) ?: continue
                        val title = obj.optString("title").trim()
                        val ts = obj.optLong("timestamp", 0L)
                        val partsArr = obj.optJSONArray("part_outline")
                        val parts = buildList {
                            if (partsArr != null) {
                                for (j in 0 until partsArr.length()) {
                                    val p = partsArr.optJSONObject(j) ?: continue
                                    val pText = p.optString("content").trim()
                                    if (pText.isNotEmpty()) {
                                        add(AiSummaryPart(p.optLong("timestamp", 0L), pText))
                                    }
                                }
                            }
                        }
                        if (title.isNotEmpty() || parts.isNotEmpty()) {
                            add(AiSummarySection(title, ts, parts))
                        }
                    }
                }
            }
            if (summary.isEmpty() && sections.isEmpty()) {
                AiSummaryFetchOutcome.Unavailable("该视频暂无可用的 AI 总结内容")
            } else {
                AiSummaryFetchOutcome.Success(AiSummaryResult(summary, sections))
            }
        }.getOrElse { throwable ->
            AiSummaryFetchOutcome.Error("获取 AI 总结异常：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun formatTimestamp(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val mins = (safe % 3600L) / 60L
        val secs = safe % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun formatPlainText(result: AiSummaryResult): String = buildString {
        if (result.summary.isNotEmpty()) {
            appendLine("【视频总结】")
            appendLine(result.summary)
        }
        if (result.sections.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("【分段大纲】")
            result.sections.forEach { section ->
                if (section.title.isNotEmpty()) {
                    appendLine("[${formatTimestamp(section.timestampSec)}] ${section.title}")
                }
                section.parts.forEach { part ->
                    appendLine("  · [${formatTimestamp(part.timestampSec)}] ${part.content}")
                }
            }
        }
    }.trim()

    private fun fetchViewMeta(bvid: String, knownCid: Long?, cookies: String): Pair<Long, Long>? {
        val body = executeGet("$VIEW_URL$bvid", cookies) ?: return null
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val cid = knownCid?.takeIf { it > 0L } ?: data.optLong("cid", 0L)
        val upMid = data.optJSONObject("owner")?.optLong("mid", 0L) ?: 0L
        return if (cid > 0L && upMid > 0L) cid to upMid else null
    }

    private fun resolveMixinKey(cookies: String): String? {
        val now = System.currentTimeMillis()
        val existing = cachedMixinKey
        if (existing != null && now - cachedMixinKeyAtMs < WBI_CACHE_TTL_MS) {
            return existing
        }
        val body = executeGet(NAV_URL, cookies) ?: return null
        val wbiImg = JSONObject(body).optJSONObject("data")?.optJSONObject("wbi_img") ?: return null
        val imgKey = extractWbiKey(wbiImg.optString("img_url")) ?: return null
        val subKey = extractWbiKey(wbiImg.optString("sub_url")) ?: return null
        val raw = imgKey + subKey
        val mixin = buildString(32) {
            for (idx in MIXIN_KEY_ENC_TAB) {
                if (idx < raw.length && length < 32) {
                    append(raw[idx])
                }
            }
        }
        if (mixin.length < 32) return null
        cachedMixinKey = mixin
        cachedMixinKeyAtMs = now
        return mixin
    }

    private fun extractWbiKey(url: String): String? =
        url.substringAfterLast('/', "").substringBefore('.').takeIf { it.isNotEmpty() }

    private fun executeGet(url: String, cookies: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
