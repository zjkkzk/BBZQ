package io.github.bzzq.hooks

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Injects the module entry into Bilibili's "Other Settings" page.
 *
 * The placement strategy is inspired by BiliRoaming/BiliRoamingX:
 * prefer a settings-list anchor instead of appending arbitrary overlay views.
 */
class BiliEntryHook(
    override val targetPackageName: String,
) : AppHook {
    override fun install(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) {
        val classLoader = packageReady.getClassLoader()

        OTHER_SETTING_ACTIVITY_NAMES.forEach { className ->
            runCatching {
                val clazz = Class.forName(className, false, classLoader)
                val onCreate = clazz.getDeclaredMethod("onCreate", Bundle::class.java)
                val onResume = clazz.getDeclaredMethod("onResume")

                xposed.hook(onCreate).intercept { chain ->
                    val result = chain.proceed()
                    scheduleInjection(chain.thisObject as Activity, log)
                    result
                }
                xposed.hook(onResume).intercept { chain ->
                    val result = chain.proceed()
                    scheduleInjection(chain.thisObject as Activity, log)
                    result
                }
                log("Installed other-settings entry hook for $className", null)
            }.onFailure {
                log("Unable to hook $className", it)
            }
        }
    }

    private fun scheduleInjection(activity: Activity, log: (String, Throwable?) -> Unit) {
        val decor = activity.window?.decorView ?: return
        INJECTION_DELAYS_MS.forEach { delay ->
            decor.postDelayed({
                runCatching { injectIntoOtherSettings(activity, log) }
                    .onFailure { log("Failed to inject bzzq entry", it) }
            }, delay)
        }
    }

    private fun injectIntoOtherSettings(activity: Activity, log: (String, Throwable?) -> Unit) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (root.findViewWithTag<View>(ENTRY_TAG) != null) return

        val anchor = findAnchorRow(root)
        val parent = anchor?.parent as? ViewGroup ?: findBestSettingsContainer(root) ?: return
        val entry = createEntryView(activity).apply { tag = ENTRY_TAG }

        if (anchor != null) {
            val index = parent.indexOfChild(anchor)
            parent.addView(entry, index.coerceAtLeast(0))
            log("Inserted bzzq entry before anchor row in other settings", null)
            return
        }

        parent.addView(entry, 0)
        log("Inserted bzzq entry at top of fallback settings container", null)
    }

    private fun createEntryView(activity: Activity): View {
        val versionName = runCatching {
            activity.packageManager.getPackageInfo("io.github.bzzq", 0).versionName
        }.getOrDefault("unknown")

        val titleLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleLayout.addView(TextView(activity).apply {
            text = "bzzq"
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
        })
        titleLayout.addView(TextView(activity).apply {
            text = "模块设置"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(activity, 4), 0, 0)
        })

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            addView(titleLayout)
            addView(TextView(activity).apply {
                text = "v$versionName"
                textSize = 13f
                setTextColor(Color.parseColor("#9E9E9E"))
            })
            setOnClickListener {
                val intent = Intent().apply {
                    setClassName("io.github.bzzq", "io.github.bzzq.SettingsActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
        }
    }

    private fun findAnchorRow(view: View): View? {
        val titleView = findTextView(view) { text ->
            ANCHOR_TEXTS.any { anchor ->
                text.contains(anchor, ignoreCase = false)
            }
        } ?: return null

        var current: View? = titleView
        while (current != null) {
            val parent = current.parent as? ViewGroup ?: break
            if (parent.isVerticalContainer() && parent.indexOfChild(current) >= 0) {
                return current
            }
            current = parent
        }
        return null
    }

    private fun findBestSettingsContainer(view: View): ViewGroup? {
        if (view !is ViewGroup) return null

        if (view.isVerticalContainer() && view.childCount >= 2) {
            val textChildCount = countTextDrivenChildren(view)
            if (textChildCount >= 2) return view
        }

        for (index in 0 until view.childCount) {
            findBestSettingsContainer(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun countTextDrivenChildren(group: ViewGroup): Int {
        var count = 0
        for (index in 0 until group.childCount) {
            if (containsAnyText(group.getChildAt(index))) count++
        }
        return count
    }

    private fun containsAnyText(view: View): Boolean {
        if (view is TextView && !view.text.isNullOrBlank()) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsAnyText(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun findTextView(view: View, predicate: (String) -> Boolean): TextView? {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (predicate(text)) return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun ViewGroup.isVerticalContainer(): Boolean {
        return this is LinearLayout && orientation == LinearLayout.VERTICAL
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val ENTRY_TAG = "bzzq_other_settings_entry"
        private val INJECTION_DELAYS_MS = longArrayOf(120L, 360L, 720L)
        private val OTHER_SETTING_ACTIVITY_NAMES = listOf(
            "com.bilibili.app.comm.setting.v2.OtherSettingActivity",
            "com.bilibili.app.comm.setting.OtherSettingActivity",
        )
        private val ANCHOR_TEXTS = listOf(
            "关于哔哩哔哩",
            "关于",
            "隐私权限设置",
            "青少年守护",
            "清理缓存",
            "推荐设置",
        )
    }
}
