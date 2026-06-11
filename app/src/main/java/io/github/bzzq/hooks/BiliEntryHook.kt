package io.github.bzzq.hooks

import android.app.Activity
import android.graphics.Color
import android.preference.Preference
import android.preference.PreferenceFragment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.bzzq.InAppSettingsDialog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy

class BiliEntryHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var count = hookLegacyPreference()
        count += hookAndroidXPreference()
        count += hookActivityFallback()
        log("Installed $count advanced settings entry hook(s)")
    }

    private fun hookLegacyPreference(): Int {
        val method = PreferenceFragment::class.java.getDeclaredMethod("onResume")
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject as? PreferenceFragment ?: return@intercept result
                if (looksLikeSettingsComponent(fragment.javaClass.name)) {
                    addLegacyPreference(fragment)
                }
                result
            }
        return 1
    }

    private fun addLegacyPreference(fragment: PreferenceFragment) {
        val activity = fragment.activity ?: return
        val screen = fragment.preferenceScreen ?: return
        if (fragment.findPreference(ENTRY_KEY) != null) return
        screen.addPreference(
            Preference(activity).apply {
                key = ENTRY_KEY
                title = ENTRY_TITLE
                summary = ENTRY_SUMMARY
                order = Int.MAX_VALUE - 100
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    InAppSettingsDialog.show(activity)
                    true
                }
            },
        )
    }

    private fun hookAndroidXPreference(): Int {
        val fragmentClass = HostAccess.findClass(
            classLoader,
            "androidx.preference.PreferenceFragmentCompat",
        ) ?: return 0
        val method = HostAccess.method(fragmentClass, listOf("onResume")) { it.parameterCount == 0 }
            ?: return 0
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject ?: return@intercept result
                if (looksLikeSettingsComponent(fragment.javaClass.name)) {
                    addAndroidXPreference(fragment)
                }
                result
            }
        return 1
    }

    private fun addAndroidXPreference(fragment: Any) {
        val screen = HostAccess.invoke(fragment, "getPreferenceScreen") ?: return
        if (HostAccess.invoke(fragment, "findPreference", ENTRY_KEY) != null) return
        val activity = HostAccess.invoke(fragment, "getActivity") as? Activity ?: return
        val preferenceClass = HostAccess.findClass(classLoader, "androidx.preference.Preference") ?: return
        val preference = runCatching {
            preferenceClass.getConstructor(android.content.Context::class.java).newInstance(activity)
        }.getOrNull() ?: return

        HostAccess.invoke(preference, "setKey", ENTRY_KEY)
        HostAccess.invoke(preference, "setTitle", ENTRY_TITLE)
        HostAccess.invoke(preference, "setSummary", ENTRY_SUMMARY)
        HostAccess.invoke(preference, "setOrder", Int.MAX_VALUE - 100)

        val listenerClass = HostAccess.findClass(
            classLoader,
            "androidx.preference.Preference\$OnPreferenceClickListener",
        ) ?: return
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, _ ->
            if (method.name == "onPreferenceClick") {
                InAppSettingsDialog.show(activity)
                true
            } else {
                null
            }
        }
        HostAccess.invoke(preference, "setOnPreferenceClickListener", listener)
        HostAccess.invoke(screen, "addPreference", preference)
    }

    private fun hookActivityFallback(): Int {
        val method = Activity::class.java.getDeclaredMethod("onResume")
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@intercept result
                activity.window?.decorView?.post {
                    runCatching { attachFallbackRow(activity) }
                        .onFailure { log("Failed to attach settings fallback row", it) }
                }
                result
            }
        return 1
    }

    private fun attachFallbackRow(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (!looksLikeSettingsPage(activity, root)) return
        if (root.findViewWithTag<View>(ENTRY_TAG) != null) return

        val anchor = findAnchorRow(root) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        val row = createEntryRow(activity).apply { tag = ENTRY_TAG }
        parent.addView(row, parent.indexOfChild(anchor).coerceAtLeast(0))
    }

    private fun looksLikeSettingsComponent(className: String): Boolean {
        val lower = className.lowercase()
        return "setting" in lower || "preference" in lower
    }

    private fun looksLikeSettingsPage(activity: Activity, root: View): Boolean {
        if (looksLikeSettingsComponent(activity.javaClass.name)) return true
        val markers = SETTINGS_MARKERS.count { marker ->
            findTextView(root) { it.contains(marker) } != null
        }
        return markers >= 2
    }

    private fun findAnchorRow(root: View): View? {
        val text = findTextView(root) { value ->
            ANCHOR_TEXTS.any(value::contains)
        } ?: return null
        var current: View = text
        repeat(6) {
            val parent = current.parent as? ViewGroup ?: return@repeat
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
                return current
            }
            current = parent
        }
        return null
    }

    private fun createEntryRow(activity: Activity): View {
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = ENTRY_TITLE
                textSize = 16f
                setTextColor(Color.parseColor("#18191C"))
            })
            addView(TextView(activity).apply {
                text = ENTRY_SUMMARY
                textSize = 13f
                setTextColor(Color.parseColor("#9499A0"))
                setPadding(0, dp(activity, 4), 0, 0)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "进入"
                textSize = 13f
                setTextColor(Color.parseColor("#FB7299"))
            })
            setOnClickListener { InAppSettingsDialog.show(activity) }
        }
    }

    private fun findTextView(root: View, predicate: (String) -> Boolean): TextView? {
        if (root is TextView && predicate(root.text?.toString().orEmpty())) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findTextView(group.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val ENTRY_KEY = "bzzq_advanced_settings"
        private const val ENTRY_TAG = "bzzq_advanced_settings_row"
        private const val ENTRY_TITLE = "高级设置"
        private const val ENTRY_SUMMARY = "bzzq 模块设置"
        private val SETTINGS_MARKERS = listOf(
            "关于",
            "清理缓存",
            "推荐设置",
            "隐私权限设置",
            "青少年守护",
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
