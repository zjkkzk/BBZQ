package io.github.bbzq.feats

import android.widget.Toast
import io.github.bbzq.BuildConfig
import io.github.bbzq.ModuleSettings
import io.github.bbzq.R
import io.github.bbzq.UpdateChecker

internal object HookUpdateChecker {
    fun check(env: RoamingEnv) {
        val acceptPrerelease = ModuleSettings.isAcceptPrereleaseUpdateEnabled(env.prefs)
        UpdateChecker.check(
            currentVersion = BuildConfig.RELEASE_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            acceptPrerelease = acceptPrerelease,
        ) { result ->
            when (result.status) {
                UpdateChecker.Status.UPDATE_AVAILABLE -> {
                    val version = result.latestVersion.orEmpty()
                    val message = runCatching {
                        (env.moduleContext ?: env.hostContext).getString(
                            R.string.hook_update_available_toast,
                            version,
                        )
                    }.getOrElse {
                        "BBZQ $version is available"
                    }
                    Toast.makeText(env.hostContext, message, Toast.LENGTH_LONG).show()
                    env.log("Hook update check found version $version")
                }

                UpdateChecker.Status.UP_TO_DATE ->
                    env.log("Hook update check: already up to date")

                UpdateChecker.Status.FAILED ->
                    env.log("Hook update check failed")
            }
        }
    }
}
