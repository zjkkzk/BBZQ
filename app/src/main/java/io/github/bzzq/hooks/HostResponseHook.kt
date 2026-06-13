package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class HostResponseHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val parserMethods = buildList {
            addAll(findGsonParsers())
            addAll(findFastJsonParsers())
        }.distinctBy { it.toGenericString() }

        parserMethods.forEach { method ->
            method.isAccessible = true
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { dispatch(result) }
                        .onFailure { log("Host response processor failed for ${method.declaringClass.name}.${method.name}", it) }
                    result
                }
        }

        if (parserMethods.isEmpty()) {
            log("No Gson/Fastjson response parser found")
        } else {
            log("Installed host response processors on ${parserMethods.size} parser method(s)")
        }
    }

    private fun findGsonParsers(): List<Method> {
        val gson = HostAccess.findClass(classLoader, "com.google.gson.Gson") ?: return emptyList()
        return HostAccess.methods(gson)
            .filter { it.name == "fromJson" && !Modifier.isAbstract(it.modifiers) }
            .toList()
    }

    private fun findFastJsonParsers(): List<Method> {
        val json = HostAccess.findClass(
            classLoader,
            "com.alibaba.fastjson.JSON",
            "com.alibaba.fastjson2.JSON",
        ) ?: return emptyList()
        return HostAccess.methods(json)
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name in FAST_JSON_PARSE_METHODS &&
                    method.returnType != Void.TYPE
            }
            .toList()
    }

    private fun dispatch(rawResult: Any?) {
        val result = unwrap(rawResult) ?: return
        when (result.javaClass.name) {
            in SPLASH_LIST_CLASSES -> {
                if (!ModuleSettings.isSkipSplashAdEnabled(prefs)) return
                processSplashList(result)
            }

            in SPLASH_SHOW_CLASSES -> {
                if (!ModuleSettings.isSkipSplashAdEnabled(prefs)) return
                processSplashShow(result)
            }

            LIVE_ROOM_USER_INFO_CLASS -> {
                if (ModuleSettings.isBlockLiveRoomQoePopupEnabled(prefs)) {
                    HostAccess.clear(result, "qoe")
                }
            }

            in REPLY_ADD_RESPONSE_CLASSES -> {
                if (ModuleSettings.isUnlockCommentGifEnabled(prefs)) {
                    unlockPostedReply(result)
                }
            }
        }
    }

    private fun unwrap(result: Any?): Any? {
        if (result == null) return null
        val className = result.javaClass.name
        return if (
            className.endsWith("GeneralResponse") ||
            className.endsWith("RxGeneralResponse")
        ) {
            HostAccess.get(result, "data")
        } else {
            result
        }
    }

    private fun clearMutableList(target: Any, fieldName: String) {
        HostAccess.asMutableList(HostAccess.get(target, fieldName))?.clear()
    }

    private fun processSplashList(target: Any) {
        clearMutableList(target, "adList")
        clearMutableList(target, "showList")
        clearMutableList(target, "splashList")
        clearMutableList(target, "strategyList")
    }

    private fun processSplashShow(target: Any) {
        clearMutableList(target, "showList")
        clearMutableList(target, "strategyList")
    }

    private fun unlockPostedReply(response: Any) {
        val data = HostAccess.get(response, "data") ?: return
        val successAction = HostAccess.getLong(data, "success_action", "successAction")
        if (successAction != null && successAction != 0L) return

        val reply = HostAccess.get(data, "reply") ?: return
        val content = HostAccess.get(reply, "content") ?: return
        val pictures = HostAccess.asIterable(HostAccess.get(content, "pictures", "picturesList"))
        if (pictures.none()) return

        HostAccess.set(content, 1.5f, "picture_scale", "pictureScale")
        pictures.forEach { picture ->
            if (picture == null) return@forEach
            HostAccess.set(picture, true, "play_gif_thumbnail", "playGifThumbnail")
            HostAccess.clear(picture, "top_right_icon", "topRightIcon")
        }
    }

    private companion object {
        private val FAST_JSON_PARSE_METHODS = setOf("parse", "parseObject")
        private val SPLASH_LIST_CLASSES = setOf(
            "tv.danmaku.bili.splash.ad.model.SplashListResponse",
            "tv.danmaku.bili.ui.splash.SplashData",
            "tv.danmaku.bili.ui.splash.ad.model.SplashData",
        )
        private val SPLASH_SHOW_CLASSES = setOf(
            "tv.danmaku.bili.splash.ad.model.SplashShowResponse",
            "tv.danmaku.bili.ui.splash.ShowSplashData",
            "tv.danmaku.bili.ui.splash.ad.model.SplashShowData",
        )
        private const val LIVE_ROOM_USER_INFO_CLASS =
            "com.bilibili.bililive.videoliveplayer.net.beans.gateway.userinfo.BiliLiveRoomUserInfo"
        private val REPLY_ADD_RESPONSE_CLASSES = setOf(
            "com.bilibili.app.comment3.data.model.ReplyAddResponse",
            "com.bilibili.app.comm.comment2.model.ReplyAddResponse",
        )
    }
}
