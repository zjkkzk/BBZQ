package io.github.bzzq.hooks

import android.content.SharedPreferences
import io.github.bzzq.ModuleSettings
import io.github.bzzq.StoryVideoAdTag
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class StoryVideoAdHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val storyPagerPlayerClass = runCatching {
            Class.forName(STORY_PAGER_PLAYER_CLASS_NAME, false, context.classLoader)
        }.getOrElse {
            context.log("StoryPagerPlayer class not found in ${context.packageName}", it)
            return
        }

        val addVideoMethods = storyPagerPlayerClass.declaredMethods.filter { method ->
            method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        if (addVideoMethods.isEmpty()) {
            context.log("StoryPagerPlayer add-video method not found in ${context.packageName}", null)
            return
        }

        val prefs = context.prefs
        addVideoMethods.forEach { method ->
            method.isAccessible = true
            context.xposed.hook(method).intercept { chain ->
                if (ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)) {
                    runCatching { purifyStoryVideoList(chain.getArg(0), prefs, context.log) }
                        .onFailure { context.log("Failed to purify story video ad list", it) }
                }
                chain.proceed()
            }
        }

        context.log("Installed story video ad hook for ${context.packageName}", null)
    }

    private fun purifyStoryVideoList(
        arg: Any?,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val storyDetailList = arg as? MutableList<Any?> ?: return
        if (storyDetailList.isEmpty()) return

        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        if (selectedTags.isEmpty()) return

        val tagsByKey = ModuleSettings.storyVideoAdTags.associateBy { it.key }
        val removedItems = storyDetailList.filter { item ->
            item?.let { shouldRemove(it, selectedTags, tagsByKey) } == true
        }
        if (removedItems.isEmpty()) return

        storyDetailList.removeAll(removedItems.toSet())
        incrementBlockedCount(prefs, removedItems.size)
        log("Removed ${removedItems.size} story video ad item(s)", null)
    }

    private fun shouldRemove(
        item: Any,
        selectedTags: Set<String>,
        tagsByKey: Map<String, StoryVideoAdTag>,
    ): Boolean {
        val isAd = invokeNoArg(item, "isAd") as? Boolean
        if ("ad" in selectedTags && isAd == true) return true

        val cartText = getCartInfoText(item) ?: return false
        return selectedTags.any { key -> tagsByKey[key]?.cartText == cartText }
    }

    private fun getCartInfoText(item: Any): String? {
        val cartIconInfo = invokeNoArg(item, "getCartIconInfo") ?: return null
        return invokeNoArg(cartIconInfo, "getEntryText") as? String
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        val method = findNoArgMethod(target.javaClass, methodName) ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findNoArgMethod(startClass: Class<*>, methodName: String): Method? {
        var currentClass: Class<*>? = startClass
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredMethod(methodName)
            } catch (_: NoSuchMethodException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    private fun incrementBlockedCount(prefs: SharedPreferences, count: Int) {
        prefs.edit()
            .putInt(
                ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT,
                prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0) + count,
            )
            .apply()
    }

    private companion object {
        private const val STORY_PAGER_PLAYER_CLASS_NAME = "com.bilibili.video.story.player.StoryPagerPlayer"
    }
}
