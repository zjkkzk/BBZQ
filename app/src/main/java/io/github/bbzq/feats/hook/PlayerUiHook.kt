package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import java.util.Collections
import java.util.WeakHashMap

/** Player window tweaks that avoid dependencies on obfuscated host classes. */
class PlayerUiHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val hiddenPortraitControls = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun startHook() {
        if (env.processName != env.packageName) return
        val transparentStatusBar = ModuleSettings.isPlayerTransparentStatusBarEnabled(prefs)
        val hidePortraitControl = ModuleSettings.isHidePlayerPortraitControlEnabled(prefs)
        if (!transparentStatusBar && !hidePortraitControl) return
        val application = env.hostContext as? Application ?: return

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (!isVideoDetailActivity(activity)) return
                if (transparentStatusBar) applyTransparentStatusBar(activity)
                if (hidePortraitControl) schedulePortraitControlScan(activity.window.decorView)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (hidePortraitControl) installVisibilityGuard()
        log("startHook: PlayerUi transparentStatusBar=$transparentStatusBar hidePortraitControl=$hidePortraitControl")
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentStatusBar(activity: Activity) {
        val window = activity.window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun installVisibilityGuard() {
        val method = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        env.hookAfter(method) { param ->
            val view = param.thisObject as? View ?: return@hookAfter
            if (view.visibility == View.GONE || !isPortraitControl(view)) return@hookAfter
            hiddenPortraitControls.add(view)
            view.post { if (view.isAttachedToWindow) view.visibility = View.GONE }
        }
    }

    private fun revealPortraitControls(view: View) {
        if (isPortraitControl(view)) {
            hiddenPortraitControls.add(view)
            view.visibility = View.GONE
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) revealPortraitControls(view.getChildAt(index))
        }
    }

    private fun schedulePortraitControlScan(decor: View) {
        revealPortraitControls(decor)
        CONTROL_RECHECK_DELAYS_MS.forEach { delay ->
            decor.postDelayed({
                if (decor.isAttachedToWindow) revealPortraitControls(decor)
                hiddenPortraitControls.toList().forEach {
                    if (it.isAttachedToWindow) it.visibility = View.GONE
                }
            }, delay)
        }
    }

    private fun isPortraitControl(view: View): Boolean {
        val description = view.contentDescription?.toString().orEmpty()
        val text = (view as? TextView)?.text?.toString().orEmpty()
        if (description.contains("竖屏") || text.trim() == "竖屏") return true
        if (view.id == View.NO_ID) return false
        val entry = runCatching { view.resources.getResourceEntryName(view.id) }
            .getOrNull()?.lowercase() ?: return false
        return PORTRAIT_ID_MARKERS.any(entry::contains) && CONTROL_ID_MARKERS.any(entry::contains)
    }

    private fun isVideoDetailActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name.contains("VideoDetail", ignoreCase = true) ||
            name.contains("DetailActivity", ignoreCase = true) ||
            name.contains("UnitedBizDetailsActivity", ignoreCase = true)
    }

    private companion object {
        private val CONTROL_RECHECK_DELAYS_MS = longArrayOf(250L, 1_000L)
        private val PORTRAIT_ID_MARKERS = listOf("portrait", "vertical")
        private val CONTROL_ID_MARKERS = listOf("screen", "fullscreen", "orientation", "control", "button")
    }
}
