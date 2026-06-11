package io.github.bzzq.hooks

import android.content.SharedPreferences
import io.github.bzzq.ModuleSettings
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class StoryVideoAdHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val resolver = HostMethodResolver(context)
        val addVideo = resolver.resolve(
            cacheKey = "story_add_video",
            fixedCandidates = {
                STORY_PLAYER_CLASSES.asSequence()
                    .mapNotNull { HostAccess.findClass(classLoader, it) }
                    .flatMap(HostAccess::methods)
            },
            searchPackages = listOf("com.bilibili.video.story"),
            usingStrings = listOf(" add "),
            validate = ::isStoryListMethod,
        )

        if (addVideo == null) {
            log("Story video list method not found")
            return
        }

        xposed.hook(addVideo).intercept { chain ->
            if (ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)) {
                HostAccess.asMutableList(chain.getArg(0))?.let(::filterStoryList)
            }
            chain.proceed()
        }
        log("Installed story video filter at ${addVideo.declaringClass.name}.${addVideo.name}")
    }

    private fun isStoryListMethod(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.declaringClass.name.contains(".story.")

    private fun filterStoryList(items: MutableList<Any?>) {
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        if (selectedTags.isEmpty()) return
        val tags = ModuleSettings.storyVideoAdTags.associateBy { it.key }
        val before = items.size
        items.removeAll { item ->
            if (item == null) return@removeAll false
            if ("ad" in selectedTags && HostAccess.getBoolean(item, "ad", "isAd") == true) {
                return@removeAll true
            }
            val cart = HostAccess.get(item, "cartIconInfo") ?: return@removeAll false
            val entryText = HostAccess.get(cart, "entryText")?.toString() ?: return@removeAll false
            selectedTags.any { key -> tags[key]?.cartText == entryText }
        }
        val removed = before - items.size
        if (removed > 0) incrementBlockedCount(prefs, removed)
    }

    private fun incrementBlockedCount(prefs: SharedPreferences, count: Int) {
        val oldValue = prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0)
        prefs.edit().putInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, oldValue + count).apply()
    }

    private companion object {
        private val STORY_PLAYER_CLASSES = listOf(
            "com.bilibili.video.story.player.StoryPagerPlayer",
        )
    }
}
