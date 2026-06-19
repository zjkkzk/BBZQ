package io.github.bbzq.roaming.hook

import android.app.Activity
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.hookAfterMethod

class TeenagersModeHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!ModuleSettings.isBlockTeenagersModeDialogEnabled(prefs)) return

        val activityClass = TARGET_ACTIVITIES.firstNotNullOfOrNull { it.from(classLoader) }
        if (activityClass != null) {
            env.hookAfterMethod(activityClass, "onCreate", android.os.Bundle::class.java) { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfterMethod
                activity.finish()
                log("Teenagers mode dialog has been closed")
            }
            log("TeenagersModeHook installed")
        } else {
            log("TeenagersModeHook: Activity not found")
        }
    }

    private companion object {
        private val TARGET_ACTIVITIES = arrayOf(
            "com.bilibili.app.preferences.TeenagersModeDialogActivity",
            "com.bilibili.p4439app.preferences.TeenagersModeDialogActivity",
            "tv.danmaku.bili.ui.teenagersmode.TeenagersModeDialogActivity",
        )
    }
}
