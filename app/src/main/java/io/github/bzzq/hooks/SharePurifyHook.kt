package io.github.bzzq.hooks

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface

class SharePurifyHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var count = hookShareResult()
        count += hookClipboardFallback()
        log("Installed $count share purification hook(s)")
    }

    private fun hookShareResult(): Int {
        val shareResult = HostAccess.findClass(
            classLoader,
            "com.bilibili.lib.sharewrapper.online.api.ShareClickResult",
        ) ?: return 0
        var count = 0
        HostAccess.methods(shareResult)
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name in setOf("getLink", "getContent")
            }
            .forEach { method ->
                xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val rawResult = chain.proceed()
                        val result = rawResult as? String ?: return@intercept rawResult
                        if (!ModuleSettings.isPurifyShareEnabled(prefs)) return@intercept result
                        val purified = purifyText(result)
                        chain.thisObject?.let { target ->
                            val field = if (method.name == "getLink") "link" else "content"
                            HostAccess.set(target, purified, field)
                        }
                        purified
                    }
                count++
            }
        return count
    }

    private fun hookClipboardFallback(): Int {
        val method = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!ModuleSettings.isPurifyShareEnabled(prefs)) return@intercept chain.proceed()
                val clip = chain.getArg(0) as? ClipData ?: return@intercept chain.proceed()
                val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    ?: return@intercept chain.proceed()
                val purified = purifyText(text)
                if (purified == text) {
                    chain.proceed()
                } else {
                    chain.proceed(arrayOf<Any>(ClipData.newPlainText(clip.description.label, purified)))
                }
            }
        return 1
    }

    private fun purifyText(text: String): String = URL_REGEX.replace(text) { match ->
        val raw = match.value
        val suffix = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        val url = raw.dropLast(suffix.length)
        purifyUrl(url) + suffix
    }

    private fun purifyUrl(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host.orEmpty()
        if (!host.endsWith("bilibili.com") && host !in SHORT_LINK_HOSTS) return url
        if (host in SHORT_LINK_HOSTS) {
            return uri.buildUpon().clearQuery().fragment(null).build().toString()
        }

        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name !in KEPT_QUERY_NAMES) return@forEach
            val value = uri.getQueryParameter(name).orEmpty()
            if (value.isNotEmpty() && !(name == "p" && value == "1")) {
                builder.appendQueryParameter(name, value)
            }
        }
        builder.fragment(uri.fragment?.takeIf { it.startsWith("reply") })
        return builder.build().toString()
    }

    private companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
        private val SHORT_LINK_HOSTS = setOf("b23.tv", "bili2233.cn")
        private val KEPT_QUERY_NAMES = setOf(
            "start_progress",
            "t",
            "p",
            "topic_id",
            "comment_on",
            "comment_root_id",
            "comment_secondary_id",
            "type",
            "itemsId",
        )
        private val TRAILING_PUNCTUATION = setOf(')', '）', ']', '】', '>', '》', ',', '，', '.', '。', ';', '；', '!', '！', '?', '？')
    }
}
