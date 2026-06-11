package io.github.bzzq.hooks

class PackageLoadLogHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        log("Observed $packageName after classloader became ready")
    }
}
