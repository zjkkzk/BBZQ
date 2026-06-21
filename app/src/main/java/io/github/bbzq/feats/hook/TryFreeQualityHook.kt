package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfterMethod
import io.github.bbzq.feats.hookBeforeMethod

class TryFreeQualityHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    private val booleanPrimitiveType = requireNotNull(Boolean::class.javaPrimitiveType)

    override fun startHook() {
        if (env.processName != env.packageName) return
        if (!ModuleSettings.isUnlockVideoFeaturesEnabled(prefs)) {
            log("startHook: TryFreeQuality disabled")
            return
        }

        var methods = 0
        methods += hookNeedTrial("com.bapis.bilibili.pgc.gateway.player.v2.SceneControl")
        methods += hookNeedTrial("com.bapis.bilibili.playershared.VideoVod")
        methods += hookStreamInfo("com.bapis.bilibili.app.playurl.v1.StreamInfo")
        methods += hookStreamInfo("com.bapis.bilibili.playershared.StreamInfo")
        log("startHook: TryFreeQuality, methods=$methods")
    }

    private fun hookNeedTrial(className: String): Int {
        val type = className.from(classLoader) ?: return 0
        var methods = 0
        methods += env.hookBeforeMethod(type, "getIsNeedTrial", hooker = { param ->
            param.result = true
        })
        methods += env.hookBeforeMethod(type, "setIsNeedTrial", booleanPrimitiveType, hooker = { param ->
            if (param.args.isNotEmpty()) {
                param.args[0] = true
            }
        })
        return methods
    }

    private fun hookStreamInfo(className: String): Int {
        val type = className.from(classLoader) ?: return 0
        var methods = 0
        methods += env.hookAfterMethod(type, "getVipFree", hooker = { param ->
            val needVip = (param.thisObject?.getObjectField("needVip_") as? Boolean) ?: return@hookAfterMethod
            param.result = needVip
        })
        methods += env.hookBeforeMethod(type, "getNeedVip", hooker = { param ->
            param.result = false
        })
        return methods
    }
}
