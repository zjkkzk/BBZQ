package io.github.bzzq.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * BiliRoamingX-like copy behavior:
 * 1. "Disable long-press copy" consumes the original copy action.
 * 2. "Enhance long-press copy" shows a selectable dialog and lets the user choose "copy all".
 */
class FreeCopyHook(
    override val targetPackageName: String,
) : AppHook {
    override fun install(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) {
        val prefs = xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)
        val classLoader = packageReady.getClassLoader()

        COPY_MENU_HOLDER_CLASS_NAMES.forEach { className ->
            runCatching {
                val clazz = Class.forName(className, false, classLoader)
                val candidates = clazz.declaredMethods.filter { method ->
                    method.parameterTypes.size >= 3 &&
                        View::class.java.isAssignableFrom(method.parameterTypes.last())
                }
                if (candidates.isEmpty()) {
                    log("No free-copy candidate methods found in $className", null)
                    return@runCatching
                }

                candidates.forEach { method ->
                    method.isAccessible = true
                    xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val disableOriginalCopy = ModuleSettings.isDisableLongPressCopyEnabled(prefs)
                            if (!disableOriginalCopy) {
                                return@intercept chain.proceed()
                            }

                            val menu = chain.getArg(1)
                            val triggerView = chain.getArg(method.parameterTypes.size - 1) as? View
                            if (!looksLikeCopyAction(menu, triggerView)) return@intercept chain.proceed()

                            val enhanceCopy = ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)
                            if (!enhanceCopy) {
                                log("Consumed default long-press copy action", null)
                                return@intercept null
                            }

                            val result = chain.proceed()
                            val text = readClipboardText(triggerView?.context) ?: return@intercept result
                            showSelectableDialog(triggerView?.context, text, log)
                            result
                        }
                }
                log("Installed free-copy hook for $className with ${candidates.size} candidate method(s)", null)
            }.onFailure {
                log("Failed to install free-copy hook for $className", it)
            }
        }
    }

    private fun looksLikeCopyAction(menu: Any?, view: View?): Boolean {
        val menuText = menu?.toString().orEmpty()
        if (menuText.contains("COPY", ignoreCase = true) || menuText.contains("复制")) return true

        return containsText(view) {
            it.contains("复制") || it.contains("拷贝")
        }
    }

    private fun containsText(view: View?, predicate: (String) -> Boolean): Boolean {
        if (view == null) return false
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (predicate(text)) return true
        }
        val group = view as? android.view.ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsText(group.getChildAt(index), predicate)) return true
        }
        return false
    }

    private fun readClipboardText(context: Context?): CharSequence? {
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clipData: ClipData = clipboard.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        return clipData.getItemAt(0).coerceToText(context)?.takeIf { it.isNotBlank() }
    }

    private fun showSelectableDialog(context: Context?, text: CharSequence, log: (String, Throwable?) -> Unit) {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            runCatching {
                AlertDialog.Builder(activity)
                    .setTitle("自由复制内容")
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("复制全部") { _, _ ->
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("bzzq_free_copy", text))
                    }
                    .show()
                    .findViewById<TextView>(android.R.id.message)
                    ?.setTextIsSelectable(true)
            }.onFailure {
                log("Failed to show free-copy dialog", it)
            }
        }
    }

    private companion object {
        private val COPY_MENU_HOLDER_CLASS_NAMES = listOf(
            "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder",
        )
    }
}
