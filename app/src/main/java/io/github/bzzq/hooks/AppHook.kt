package io.github.bzzq.hooks

interface AppHook {
    val targetPackageName: String

    fun install(context: HookContext)
}
