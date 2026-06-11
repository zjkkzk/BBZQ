package io.github.bzzq.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.io.use

object HookRegistry {
    private val targetPackageNames = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.blue",
    )

    private val hookFactories: List<(String) -> BaseHook> = listOf(
        ::PackageLoadLogHook,
        ::GsonSplashAdHook,
        ::VideoFeatureUnlockHook,
        ::AutoLikeVideoDetailHook,
        ::BlockLiveReservationHook,
        ::BlockLiveRoomQoeHook,
        ::LiveQualityHook,
        ::StoryVideoAdHook,
        ::MiniGameRewardAdHook,
        ::BiliEntryHook,
        ::AccessKeyCaptureHook,
        ::FreeCopyHook,
        ::SelectableTextHook,
        ::SharePurifyHook,
        ::FullNumberFormatHook,
        ::UnlockCommentGifHook,
    )

    fun handlePackageReady(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) {
        val packageName = packageReady.getPackageName()
        if (packageName !in targetPackageNames) return

        val matchingHooks = hookFactories.map { factory -> factory(packageName) }

        log("Installing ${matchingHooks.size} hook(s) for $packageName", null)
        HookContext(xposed, packageReady, log).use { context ->
            matchingHooks.forEach { hook ->
                runCatching { hook.install(context) }
                    .onFailure { log("Hook failed for $packageName", it) }
            }
            matchingHooks.forEach { hook ->
                runCatching { hook.lateInitHook() }
                    .onFailure { log("Late hook failed for $packageName", it) }
            }
        }
    }
}
