package io.github.bzzq.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

interface AppHook {
    val targetPackageName: String

    fun install(context: HookContext) {
        install(context.xposed, context.packageReady, context.log)
    }

    fun install(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) = Unit
}
