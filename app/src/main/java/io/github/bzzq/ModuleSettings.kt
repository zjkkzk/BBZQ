package io.github.bzzq

import android.content.SharedPreferences

object ModuleSettings {
    const val PREFS_NAME = "bzzq_settings"
    const val KEY_PURIFY_STORY_VIDEO_AD_ENABLED = "purify_story_video_ad_enabled"
    const val KEY_PURIFY_STORY_VIDEO_AD_TAGS = "purify_story_video_ad_tags"
    const val KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT = "purify_story_video_ad_blocked_count"

    val defaultStoryVideoAdTags = setOf("ad")

    val storyVideoAdTags = listOf(
        StoryVideoAdTag("ad", "廣告", null),
        StoryVideoAdTag("short", "短劇", "短剧"),
        StoryVideoAdTag("shopping", "購物", "购物"),
        StoryVideoAdTag("tv", "電視劇", "电视剧"),
        StoryVideoAdTag("doc", "紀錄片", "纪录片"),
        StoryVideoAdTag("ent", "娛樂", "娱乐"),
        StoryVideoAdTag("movie", "電影", "电影"),
        StoryVideoAdTag("music", "音樂", "音乐"),
        StoryVideoAdTag("topic", "話題", "话题"),
    )

    fun isPurifyStoryVideoAdEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(KEY_PURIFY_STORY_VIDEO_AD_ENABLED, false)
    }

    fun getPurifyStoryVideoAdTags(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY_PURIFY_STORY_VIDEO_AD_TAGS, defaultStoryVideoAdTags)
            ?: defaultStoryVideoAdTags
    }
}

data class StoryVideoAdTag(
    val key: String,
    val label: String,
    val cartText: String?,
)
