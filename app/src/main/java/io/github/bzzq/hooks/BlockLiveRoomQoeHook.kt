package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings
class BlockLiveRoomQoeHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val gsonClass = runCatching {
            Class.forName(GSON_CLASS_NAME, false, context.classLoader)
        }.getOrElse {
            context.log("Gson class not found for live room qoe hook", it)
            return
        }

        val prefs = context.prefs
        val fromJsonMethods = gsonClass.declaredMethods.filter { it.name == FROM_JSON_METHOD_NAME }
        if (fromJsonMethods.isEmpty()) {
            context.log("Gson.fromJson not found for live room qoe hook", null)
            return
        }

        fromJsonMethods.forEach { method ->
            method.isAccessible = true
            context.xposed.hook(method)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!ModuleSettings.isBlockLiveRoomQoePopupEnabled(prefs)) {
                        return@intercept result
                    }

                    runCatching { stripQoe(result, context.log) }
                        .onFailure { context.log("Failed to strip live room qoe popup payload", it) }
                    result
                }
        }

        context.log("Installed live room qoe popup hook for ${context.packageName}", null)
    }

    private fun stripQoe(result: Any?, log: (String, Throwable?) -> Unit) {
        if (result == null) return
        if (result.javaClass.name != LIVE_ROOM_USER_INFO_CLASS_NAME) return

        val qoeField = findField(result.javaClass, QOE_FIELD_NAME) ?: return
        qoeField.isAccessible = true
        if (qoeField.get(result) == null) return
        qoeField.set(result, null)
        log("Cleared live room qoe popup payload", null)
    }

    private fun findField(startClass: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var currentClass: Class<*>? = startClass
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    private companion object {
        private const val GSON_CLASS_NAME = "com.google.gson.Gson"
        private const val FROM_JSON_METHOD_NAME = "fromJson"
        private const val LIVE_ROOM_USER_INFO_CLASS_NAME =
            "com.bilibili.bililive.videoliveplayer.net.beans.gateway.userinfo.BiliLiveRoomUserInfo"
        private const val QOE_FIELD_NAME = "qoe"
    }
}
