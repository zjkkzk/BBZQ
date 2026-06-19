package io.github.bbzq.feats.hook

import android.os.Bundle
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfterMethod
import io.github.bbzq.feats.setBooleanField

class RewardAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!ModuleSettings.isSkipRewardAdEnabled(prefs)) return

        val rewardAdActivity = "com.bilibili.ad.reward.RewardAdActivity".from(classLoader) ?: return
        val rewardFlag = rewardAdActivity.declaredFields
            .filter { it.type == Boolean::class.javaPrimitiveType }
            .getOrNull(1)
            ?.name
            ?: return

        val count = env.hookAfterMethod(rewardAdActivity, "onCreate", Bundle::class.java) { param ->
            val activity = param.thisObject ?: return@hookAfterMethod
            activity.setBooleanField(rewardFlag, true)
            rewardAdActivity.declaredFields
                .firstOrNull { it.type == TextView::class.java }
                ?.apply { isAccessible = true }
                ?.get(activity)
                .let { it as? TextView }
                ?.performClick()
        }
        log("startHook: RewardAd, methods=$count")
    }
}

