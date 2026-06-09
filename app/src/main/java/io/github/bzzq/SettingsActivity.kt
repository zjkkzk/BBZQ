package io.github.bzzq

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE) }
    private val tagCheckBoxes = mutableMapOf<String, CheckBox>()
    private lateinit var enabledSwitch: Switch
    private lateinit var blockedCountView: TextView
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })

        enabledSwitch = Switch(this).apply {
            text = getString(R.string.purify_story_video_ad_title)
            textSize = 18f
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, isChecked ->
                if (refreshing) return@setOnCheckedChangeListener

                prefs.edit().putBoolean(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_ENABLED, isChecked).apply()
                if (isChecked && selectedTagKeys().isEmpty()) {
                    prefs.edit()
                        .putStringSet(
                            ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_TAGS,
                            ModuleSettings.defaultStoryVideoAdTags,
                        )
                        .apply()
                }
                refresh()
            }
        }
        root.addView(enabledSwitch)

        ModuleSettings.storyVideoAdTags.forEach { tag ->
            val checkBox = CheckBox(this).apply {
                text = tag.label
                setOnCheckedChangeListener { _, _ ->
                    if (!refreshing) saveSelectedTags()
                }
            }
            tagCheckBoxes[tag.key] = checkBox
            root.addView(checkBox)
        }

        blockedCountView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(blockedCountView)

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
        refresh()
    }

    private fun refresh() {
        refreshing = true
        val enabled = ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)

        enabledSwitch.isChecked = enabled
        tagCheckBoxes.forEach { (key, checkBox) ->
            checkBox.isEnabled = enabled
            checkBox.isChecked = key in selectedTags
        }
        blockedCountView.text = getString(
            R.string.purify_story_video_ad_blocked_count,
            prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0),
        )
        refreshing = false
    }

    private fun saveSelectedTags() {
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_TAGS, selectedTagKeys())
            .apply()
    }

    private fun selectedTagKeys(): Set<String> {
        return tagCheckBoxes
            .filterValues { it.isChecked }
            .keys
            .toSet()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
