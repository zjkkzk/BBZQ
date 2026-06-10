package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings
import java.lang.reflect.Field

class GsonSplashAdHook(
    override val targetPackageName: String,
) : AppHook {
    override fun install(context: HookContext) {
        val gsonClass = runCatching {
            Class.forName(GSON_CLASS_NAME, false, context.classLoader)
        }.getOrElse {
            context.log("Gson class not found in ${context.packageName}", it)
            return
        }

        val fromJsonMethods = gsonClass.declaredMethods.filter { it.name == FROM_JSON_METHOD_NAME }
        if (fromJsonMethods.isEmpty()) {
            context.log("Gson.fromJson not found in ${context.packageName}", null)
            return
        }

        val prefs = context.prefs
        fromJsonMethods.forEach { method ->
            context.xposed.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (ModuleSettings.isSkipSplashAdEnabled(prefs)) {
                    runCatching { processGsonResult(result, context.log) }
                        .onFailure { context.log("Failed to process Gson splash response", it) }
                }
                result
            }
        }

        context.log("Installed splash ad skip hook for ${context.packageName}", null)
    }

    private fun processGsonResult(result: Any?, log: (String, Throwable?) -> Unit) {
        if (result == null) return

        if (processSplashResponse(result, log)) return

        val data = getFieldValue(result, "data") ?: return
        processSplashResponse(data, log)
    }

    private fun processSplashResponse(response: Any, log: (String, Throwable?) -> Unit): Boolean {
        return when (response.javaClass.name) {
            SPLASH_LIST_RESPONSE_CLASS_NAME -> {
                clearListField(response, "splashList", log)
                clearListField(response, "strategyList", log)
                true
            }

            SPLASH_SHOW_RESPONSE_CLASS_NAME -> {
                clearListField(response, "strategyList", log)
                true
            }

            else -> false
        }
    }

    private fun clearListField(target: Any, fieldName: String, log: (String, Throwable?) -> Unit) {
        val list = getFieldValue(target, fieldName) as? MutableList<*> ?: return
        val itemCount = list.size
        if (itemCount == 0) return

        list.clear()
        log("Cleared $itemCount splash ad item(s) from ${target.javaClass.simpleName}.$fieldName", null)
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        val field = findField(target.javaClass, fieldName) ?: return null
        field.isAccessible = true
        return field.get(target)
    }

    private fun findField(startClass: Class<*>, fieldName: String): Field? {
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
        private const val SPLASH_LIST_RESPONSE_CLASS_NAME = "tv.danmaku.bili.splash.ad.model.SplashListResponse"
        private const val SPLASH_SHOW_RESPONSE_CLASS_NAME = "tv.danmaku.bili.splash.ad.model.SplashShowResponse"
    }
}
