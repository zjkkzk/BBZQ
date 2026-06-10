package io.github.bzzq.hooks

import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class AutoLikeVideoDetailHook(
    override val targetPackageName: String,
) : AppHook {
    private var lastClickAt = 0L

    override fun install(xposed: XposedInterface, packageReady: PackageReadyParam, log: (String, Throwable?) -> Unit) {
        val prefs = xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)

        runCatching {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            xposed.hook(onResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) {
                        scheduleAutoLike(chain.getThisObject(), prefs, log)
                    }
                    result
                }

            val onWindowFocusChanged = Activity::class.java.getDeclaredMethod(
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
            )
            xposed.hook(onWindowFocusChanged)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (chain.getArg(0) == true && ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) {
                        scheduleAutoLike(chain.getThisObject(), prefs, log)
                    }
                    result
                }

            log("Installed video detail auto-like hook for ${packageReady.getPackageName()}", null)
        }.onFailure {
            log("Failed to install video detail auto-like hook", it)
        }
    }

    private fun scheduleAutoLike(
        target: Any?,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        val activity = target as? Activity ?: return
        if (!ModuleSettings.isAutoLikeVideoDetailEnabled(prefs) || !isVideoDetailActivity(activity)) return

        val decor = activity.window?.decorView ?: return
        SCAN_DELAYS_MS.forEach { delay ->
            decor.postDelayed({
                if (ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) {
                    clickLikeIfNeeded(activity, log)
                }
            }, delay)
        }
    }

    private fun clickLikeIfNeeded(activity: Activity, log: (String, Throwable?) -> Unit) {
        val likeView = findLikeView(activity) ?: return
        if (hasRecentlyClicked() || hasLikedState(likeView)) return

        likeView.post {
            if (hasRecentlyClicked() || hasLikedState(likeView)) return@post
            if (performClick(likeView)) {
                lastClickAt = System.currentTimeMillis()
                log("Clicked video detail like button in ${activity.javaClass.name}", null)
            }
        }
    }

    private fun findLikeView(activity: Activity): View? {
        val decor = activity.window?.decorView ?: return null
        LIKE_VIEW_ID_NAMES.forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) {
                decor.findViewById<View>(id)?.let { return it }
            }
        }
        return null
    }

    private fun hasLikedState(root: View): Boolean {
        var visitedCount = 0
        fun visit(view: View?): Boolean {
            if (view == null || visitedCount++ > MAX_VIEW_SCAN_NODES) return false
            if (view.isSelected || view.isActivated) return true
            if (containsLikedText(view.contentDescription)) return true
            if (view is TextView && containsLikedText(view.text)) return true

            val group = view as? ViewGroup ?: return false
            for (index in 0 until group.childCount) {
                if (visit(group.getChildAt(index))) return true
            }
            return false
        }
        return visit(root)
    }

    private fun containsLikedText(text: CharSequence?): Boolean {
        val compact = text?.toString()?.replace(" ", "") ?: return false
        return LIKED_TEXT_MARKERS.any { marker -> compact.contains(marker) }
    }

    private fun performClick(view: View): Boolean {
        if (!view.isShown || !view.isEnabled) return false
        if (view.isClickable || view.hasOnClickListeners()) return view.callOnClick()

        val parent = view.parent as? View
        return parent != null &&
            parent.isShown &&
            parent.isEnabled &&
            (parent.isClickable || parent.hasOnClickListeners()) &&
            parent.callOnClick()
    }

    private fun hasRecentlyClicked(): Boolean {
        return System.currentTimeMillis() - lastClickAt < CLICK_COOLDOWN_MS
    }

    private fun isVideoDetailActivity(activity: Activity): Boolean {
        val className = activity.javaClass.name
        return VIDEO_DETAIL_ACTIVITY_NAME_PARTS.any { className.contains(it) }
    }

    private companion object {
        private val VIDEO_DETAIL_ACTIVITY_NAME_PARTS = listOf(
            "UnitedBizDetailsActivity",
            "VideoDetailsActivity",
            "VideoDetailActivity",
            "VideoDetailPageActivity",
        )
        private val LIKE_VIEW_ID_NAMES = listOf("frame_like", "like_layout")
        private val LIKED_TEXT_MARKERS = listOf("已点赞", "已赞", "取消点赞")
        private val SCAN_DELAYS_MS = longArrayOf(300L, 900L, 1600L, 2600L)
        private const val CLICK_COOLDOWN_MS = 10_000L
        private const val MAX_VIEW_SCAN_NODES = 120
    }
}
