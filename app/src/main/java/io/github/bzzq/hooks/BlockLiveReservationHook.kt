package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings

class BlockLiveReservationHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val classLoader = context.classLoader
        val prefs = context.prefs

        runCatching {
            val builderClass = Class.forName("com.bapis.bilibili.app.view.v1.ViewReply\$Builder", false, classLoader)
            val buildMethod = builderClass.getDeclaredMethod("build")
            context.xposed.hook(buildMethod).intercept { chain ->
                val builder = chain.thisObject
                if (builder != null && ModuleSettings.isBlockLiveReservationEnabled(prefs)) {
                    runCatching {
                        val clearMethod = builder.javaClass.getDeclaredMethod("clearLiveOrderInfo")
                        clearMethod.invoke(builder)
                    }
                }
                chain.proceed()
            }
            context.log("Installed ViewReply\$Builder.build() hook for block live reservation", null)
        }
        
        runCatching {
            val viewReplyClass = Class.forName("com.bapis.bilibili.app.view.v1.ViewReply", false, classLoader)
            
            val hasLiveOrderInfo = viewReplyClass.getDeclaredMethod("hasLiveOrderInfo")
            context.xposed.hook(hasLiveOrderInfo).intercept { chain ->
                if (ModuleSettings.isBlockLiveReservationEnabled(prefs)) {
                    false
                } else {
                    chain.proceed()
                }
            }
            context.log("Installed ViewReply.hasLiveOrderInfo() hook for block live reservation", null)
        }
    }
}
