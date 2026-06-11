package io.github.bzzq.hooks

import android.app.Activity
import android.view.View
import io.github.bzzq.InAppSettingsDialog
import io.github.bzzq.ModuleSettings
import java.lang.reflect.Method

/**
 * Injects an entry into the "Mine" (User Center) page.
 * Similar to BiliRoaming's approach of adding an item to the list.
 */
class MinePageEntryHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {

    override fun startHook() {
        val resolver = HostMethodResolver(context)
        
        // Find the User Center / Home Center class and its method that adds items
        // In Bilibili, this is often a method that takes a List and adds menu items to it.
        val addEntryMethod = resolver.resolve(
            cacheKey = "mine_page_add_entry",
            fixedCandidates = { emptySequence() },
            usingStrings = listOf("main.my-information.noportrait.0.show", "bilibili://main/preference"),
            validate = { method ->
                method.parameterCount >= 2 && 
                (method.parameterTypes.any { it == List::class.java } || 
                 method.parameterTypes.any { it.name.contains("List") })
            }
        )

        if (addEntryMethod == null) {
            log("Mine page entry method not found via DexKit")
            return
        }

        xposed.hook(addEntryMethod).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                injectEntry(chain.args.toTypedArray(), chain.thisObject)
            }.onFailure {
                log("Failed to inject entry into Mine page", it)
            }
            result
        }
        
        log("Installed Mine page entry hook at ${addEntryMethod.declaringClass.name}")
    }

    private fun injectEntry(args: Array<Any?>, thisObject: Any?) {
        // Try to find the list in arguments
        val list = args.firstNotNullOfOrNull { HostAccess.asMutableList(it) } 
            ?: args.firstNotNullOfOrNull { arg ->
                arg?.let { HostAccess.get(it, "moreSectionList") }?.let { HostAccess.asMutableList(it) }
            } ?: return

        // Check if already added
        if (list.any { item -> item != null && HostAccess.get(item, "title") == "高级设置" }) return

        // Create a new item (needs to match the target's expected class, usuallyMenuGroup$Item)
        val itemClass = HostAccess.findClass(classLoader, "com.bilibili.lib.homepage.mine.MenuGroup\$Item") 
            ?: list.firstOrNull()?.javaClass ?: return
            
        val newItem = runCatching { itemClass.getConstructor().newInstance() }.getOrNull() ?: return
        
        HostAccess.set(newItem, 114514, "id")
        HostAccess.set(newItem, "高级设置", "title")
        HostAccess.set(newItem, "bzzq 模块设置", "summary")
        HostAccess.set(newItem, "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png", "icon")
        HostAccess.set(newItem, "bilibili://bzzq/settings", "uri")
        HostAccess.set(newItem, 1, "visible")

        list.add(newItem)
        
        // Also need to hook the router to handle this URI
        hookRouter()
    }

    private var routerHooked = false
    private fun hookRouter() {
        if (routerHooked) return
        routerHooked = true
        
        // Simplified: Hooking startActivity to catch our URI
        val method = Activity::class.java.getDeclaredMethod("startActivity", android.content.Intent::class.java)
        xposed.hook(method).intercept { chain ->
            val intent = chain.args[0] as? android.content.Intent
            if (intent?.data?.toString() == "bilibili://bzzq/settings") {
                InAppSettingsDialog.show(chain.thisObject as Activity)
                return@intercept null
            }
            chain.proceed()
        }
    }
}
