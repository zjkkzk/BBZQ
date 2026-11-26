package io.github.bbzq.feats.hook.profile

import android.view.View
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredMineProfileSymbols
import java.util.Collections
import java.util.WeakHashMap
import java.lang.reflect.Method
import kotlin.LazyThreadSafetyMode

class MineProfileHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val mineSymbols: RestoredMineProfileSymbols? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        env.symbols?.mineProfile?.restore(classLoader)
    }
    private val seenMenuTitles: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val masterMenuLists: MutableMap<Any, MutableList<Any?>> = Collections.synchronizedMap(WeakHashMap())
    private var composeVipInstalled = false

    override fun startHook() {
        if (env.processName != env.packageName) return

        val symbols = mineSymbols
        if (symbols != null) {
            env.hookBefore(symbols.onResume) { param ->
                runCatching {
                    if (!ModuleSettings.isMineRemoveVipEnabled(prefs)) return@runCatching
                    val fragment = param.thisObject ?: return@runCatching
                    val vipView = symbols.resolveVipView(fragment) ?: return@runCatching
                    vipView.visibility = if (ModuleSettings.isMineKeepVipSpaceEnabled(prefs)) {
                        View.INVISIBLE
                    } else {
                        View.GONE
                    }
                }.onFailure {
                    log("MineProfile vip hook failed at ${symbols.onResume.declaringClass.name}.${symbols.onResume.name}", it)
                }
            }
        } else {
            log("MineProfileHook: Mine profile symbols missing for VIP hook")
        }

        if (!composeVipInstalled) {
            composeVipInstalled = env.hookComposeMineVip()
            if (!composeVipInstalled) hookVipEntranceState()
        }
        hookMineComponentData()
        hookMenuGroupItems()
    }

    /**
     * Both the View page (onViewCreated) and the Compose pages (MineVipModule in mine/v2 and v3)
     * only build the VIP card when the "vip" entrance state is non-zero.
     */
    private fun hookVipEntranceState() {
        val method = classLoader.findClassOrNull(TEENAGERS_MODE_CLASS)
            ?.declaredMethods
            ?.firstOrNull {
                it.name == "getEntranceState" &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    it.returnType == Int::class.javaPrimitiveType
            }
            ?: return log("MineProfileHook: TeenagersMode.getEntranceState not found, VIP entrance hook skipped")
        env.hookBefore(method) { param ->
            if (param.args.firstOrNull() != VIP_ENTRANCE_KEY) return@hookBefore
            if (!ModuleSettings.isMineRemoveVipEnabled(prefs)) return@hookBefore
            // The View page keeps the card's space through the onResume hook instead.
            if (ModuleSettings.isMineKeepVipSpaceEnabled(prefs) && isCalledFromViewPage()) return@hookBefore
            param.result = 0
        }
    }

    private fun isCalledFromViewPage(): Boolean =
        Thread.currentThread().stackTrace.any { it.className == VIEW_PAGE_FRAGMENT_CLASS }

    /**
     * mine/v2 and v3 decode AccountMineV2 with kotlinx, so filter the menu groups at their getter.
     * The host mutates the returned list in place (e.g. appends the local "设置" entry to the last group).
     * We keep a non-destructive master copy so unhiding restores items correctly, and ensure "设置" is never hidden.
     */
    private fun hookMenuGroupItems() {
        val getItemList = classLoader.findClassOrNull(MENU_GROUP_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getItemList" && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
            ?: return log("MineProfileHook: MenuGroupV2.getItemList not found, menu item hook skipped")
        val getTitle = classLoader.findClassOrNull(MENU_ITEM_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getTitle" && it.parameterCount == 0 && it.returnType == String::class.java }
            ?: return log("MineProfileHook: MenuGroupV2.Item.getTitle not found, menu item hook skipped")
        val getUri = classLoader.findClassOrNull(MENU_ITEM_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getUri" && it.parameterCount == 0 && it.returnType == String::class.java }
        val getId = classLoader.findClassOrNull(MENU_ITEM_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getId" && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
        val setLocalShow = classLoader.findClassOrNull(MENU_ITEM_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "setLocalShow" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }

        env.hookAfter(getItemList) { param ->
            val group = param.thisObject ?: return@hookAfter
            val items = param.result as? List<*> ?: return@hookAfter
            val itemsSnapshot = runCatching { ArrayList<Any?>(items) }.getOrNull() ?: return@hookAfter

            val master = synchronized(masterMenuLists) {
                masterMenuLists.getOrPut(group) { ArrayList(itemsSnapshot) }
            }
            val masterSnapshot = synchronized(master) {
                for (item in itemsSnapshot) {
                    if (item != null && !master.contains(item)) {
                        master.add(item)
                    }
                }
                ArrayList(master)
            }

            // Ensure "设置" is present in the "更多服务" group
            val hasSettingsInMaster = masterSnapshot.any { item ->
                if (item == null) false else {
                    val title = runCatching { getTitle.invoke(item) as? String }.getOrNull()?.trim()
                    val uri = runCatching { getUri?.invoke(item) as? String }.getOrNull()?.trim()
                    val id = runCatching { getId?.invoke(item) as? Long }.getOrNull()
                    isMineSettingsItem(title, uri, id)
                }
            }

            if (!hasSettingsInMaster && isMoreServiceGroup(group, masterSnapshot)) {
                val settingsItem = createSettingsItem()
                if (settingsItem != null) {
                    synchronized(master) {
                        if (!master.contains(settingsItem)) {
                            master.add(settingsItem)
                        }
                    }
                    if (!masterSnapshot.contains(settingsItem)) {
                        masterSnapshot.add(settingsItem)
                    }
                    recordIntoBacking(group, settingsItem, insertAtStart = false)
                }
            }

            val hidden = if (ModuleSettings.isCustomMineComponentHideEnabled(prefs)) {
                ModuleSettings.getHiddenMineComponents(prefs)
            } else {
                emptySet()
            }
            val titles = linkedSetOf<String>()
            val kept = ArrayList<Any?>(masterSnapshot.size)

            for (item in masterSnapshot) {
                if (item == null) {
                    kept.add(item)
                    continue
                }
                val title = runCatching { getTitle.invoke(item) as? String }.getOrNull()?.trim()
                val uri = runCatching { getUri?.invoke(item) as? String }.getOrNull()?.trim()
                val id = runCatching { getId?.invoke(item) as? Long }.getOrNull()
                val isSettings = isMineSettingsItem(title, uri, id)

                if (!title.isNullOrBlank() && !isSettings) {
                    titles.add(title)
                }

                val isHidden = !isSettings && !title.isNullOrBlank() && title in hidden
                runCatching { setLocalShow?.invoke(item, !isHidden) }

                if (!isHidden) {
                    kept.add(item)
                }
            }

            // Guarantee that if this is the "更多服务" group, kept list ALWAYS contains settings item
            val hasSettingsInKept = kept.any { item ->
                if (item == null) false else {
                    val title = runCatching { getTitle.invoke(item) as? String }.getOrNull()?.trim()
                    val uri = runCatching { getUri?.invoke(item) as? String }.getOrNull()?.trim()
                    val id = runCatching { getId?.invoke(item) as? Long }.getOrNull()
                    isMineSettingsItem(title, uri, id)
                }
            }
            if (!hasSettingsInKept && isMoreServiceGroup(group, masterSnapshot)) {
                val settingsItem = createSettingsItem()
                if (settingsItem != null) {
                    kept.add(settingsItem)
                    synchronized(master) {
                        if (!master.contains(settingsItem)) master.add(settingsItem)
                    }
                    recordIntoBacking(group, settingsItem, insertAtStart = false)
                }
            }

            if (titles.isNotEmpty() && seenMenuTitles.addAll(titles)) {
                saveKnownComponents(titles)
            }

            // 每次返回獨立列表副本以避免併發遍歷 ConcurrentModificationException（#134），
            // 同時將宿主對回傳列表的 add/addAll（如追加本地「設定」項）同步回 master 和底層 itemList，避免入口消失（#135）。
            param.result = SyncedMenuItemList(
                initialItems = kept,
                master = master,
                group = group,
                getTitle = getTitle,
                getUri = getUri,
                getId = getId,
                isMineSettings = ::isMineSettingsItem,
                onRecordBacking = ::recordIntoBacking,
            )
        }
    }

    private class SyncedMenuItemList(
        initialItems: List<Any?>,
        private val master: MutableList<Any?>,
        private val group: Any?,
        private val getTitle: Method?,
        private val getUri: Method?,
        private val getId: Method?,
        private val isMineSettings: (String?, String?, Long?) -> Boolean,
        private val onRecordBacking: (Any?, Any?, Boolean) -> Unit,
    ) : ArrayList<Any?>(initialItems) {
        override fun add(element: Any?): Boolean {
            if (isDuplicateSettings(element)) return true
            val changed = super.add(element)
            recordIntoMaster(element, insertAtStart = false)
            onRecordBacking(group, element, false)
            return changed
        }

        override fun add(index: Int, element: Any?) {
            if (isDuplicateSettings(element)) return
            super.add(index, element)
            recordIntoMaster(element, insertAtStart = index == 0)
            onRecordBacking(group, element, index == 0)
        }

        override fun addAll(elements: Collection<Any?>): Boolean {
            val toAdd = elements.filterNot { isDuplicateSettings(it) }
            val changed = super.addAll(toAdd)
            if (changed) {
                toAdd.forEach {
                    recordIntoMaster(it, insertAtStart = false)
                    onRecordBacking(group, it, false)
                }
            }
            return changed
        }

        override fun addAll(index: Int, elements: Collection<Any?>): Boolean {
            val toAdd = elements.filterNot { isDuplicateSettings(it) }
            val changed = super.addAll(index, toAdd)
            if (changed) {
                toAdd.forEach {
                    recordIntoMaster(it, insertAtStart = index == 0)
                    onRecordBacking(group, it, index == 0)
                }
            }
            return changed
        }

        override fun set(index: Int, element: Any?): Any? {
            val previous = super.set(index, element)
            recordIntoMaster(element, insertAtStart = false)
            onRecordBacking(group, element, false)
            return previous
        }

        private fun isDuplicateSettings(element: Any?): Boolean {
            if (element == null) return false
            val title = runCatching { getTitle?.invoke(element) as? String }.getOrNull()?.trim()
            val uri = runCatching { getUri?.invoke(element) as? String }.getOrNull()?.trim()
            val id = runCatching { getId?.invoke(element) as? Long }.getOrNull()
            if (!isMineSettings(title, uri, id)) return false
            return this.any { existing ->
                if (existing == null) false else {
                    val exTitle = runCatching { getTitle?.invoke(existing) as? String }.getOrNull()?.trim()
                    val exUri = runCatching { getUri?.invoke(existing) as? String }.getOrNull()?.trim()
                    val exId = runCatching { getId?.invoke(existing) as? Long }.getOrNull()
                    isMineSettings(exTitle, exUri, exId)
                }
            }
        }

        private fun recordIntoMaster(element: Any?, insertAtStart: Boolean) {
            if (element == null) return
            synchronized(master) {
                if (!master.contains(element)) {
                    if (insertAtStart) {
                        master.add(0, element)
                    } else {
                        master.add(element)
                    }
                }
            }
        }
    }

    private fun hookMineComponentData() {
        val jsonClass = sequenceOf("com.alibaba.fastjson.JSON", "com.alibaba.fastjson2.JSON")
            .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
            .firstOrNull()
        if (jsonClass == null) {
            log("MineProfileHook: FastJson class not found, component hook skipped")
            return
        }

        jsonClass.methods.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.name == "parseObject" &&
                    it.parameterCount >= 2 &&
                    it.parameterTypes[0] == String::class.java
        }.forEach { method ->
            env.hookAfter(method) { param ->
                val result = param.result ?: return@hookAfter
                processParsedResult(result)
            }
        }
    }

    private fun processParsedResult(result: Any) {
        var data = result
        if (data.javaClass.name.endsWith(".GeneralResponse")) {
            val dataField = data.javaClass.methods.firstOrNull { it.name == "getData" }
                ?.let { runCatching { it.invoke(data) }.getOrNull() }
                ?: runCatching { 
                    data.javaClass.getDeclaredField("data").apply { isAccessible = true }.get(data) 
                }.getOrNull()
            
            if (dataField != null) {
                data = dataField
            } else {
                return
            }
        }

        val className = data.javaClass.name
        if (className == "tv.danmaku.bili.ui.main2.api.AccountMine" || className == "tv.danmaku.bili.ui.main2.api.AccountMineV2") {
            purifyMineData(data)
        }
    }

    private fun purifyMineData(mineData: Any) = runCatching {
        val components = linkedSetOf<String>()
        val hidden = if (ModuleSettings.isCustomMineComponentHideEnabled(prefs)) {
            ModuleSettings.getHiddenMineComponents(prefs)
        } else {
            emptySet()
        }

        // AccountMineV2 structure: sectionListV2 -> itemList -> title
        val sectionListV2Field = runCatching { mineData.javaClass.getDeclaredField("sectionListV2").apply { isAccessible = true } }.getOrNull()
        if (sectionListV2Field != null) {
            val sections = runCatching { sectionListV2Field.get(mineData) as? List<*> }.getOrNull()
            sections?.forEach { section ->
                if (section == null) return@forEach
                val itemListField = runCatching { section.javaClass.getDeclaredField("itemList").apply { isAccessible = true } }.getOrNull()
                if (itemListField != null) {
                    val rawList = runCatching { itemListField.get(section) as? List<*> }.getOrNull()
                    if (rawList != null) {
                        val toKeep = ArrayList<Any?>()
                        var modified = false
                        rawList.forEach { item ->
                            if (item == null) {
                                toKeep.add(item)
                                return@forEach
                            }
                            val titleField = runCatching { item.javaClass.getDeclaredField("title").apply { isAccessible = true } }.getOrNull()
                            val title = titleField?.let { runCatching { it.get(item) as? String }.getOrNull() }?.trim()
                            val isSettings = isMineSettingsItem(title, null)
                            if (!title.isNullOrBlank() && !isSettings) {
                                components.add(title)
                                if (title in hidden) {
                                    modified = true
                                    return@forEach
                                }
                            }
                            toKeep.add(item)
                        }
                        if (modified) {
                            val success = replaceItemsInPlace(rawList, toKeep)
                            if (!success) {
                                runCatching { itemListField.set(section, toKeep) }
                            }
                        }
                    }
                }
            }
        }

        // AccountMine legacy structure: padSectionList, recommendSectionList, moreSectionList
        val legacyLists = listOf("padSectionList", "recommendSectionList", "moreSectionList")
        legacyLists.forEach { fieldName ->
            val listField = runCatching { mineData.javaClass.getDeclaredField(fieldName).apply { isAccessible = true } }.getOrNull()
            if (listField != null) {
                val rawList = runCatching { listField.get(mineData) as? List<*> }.getOrNull()
                if (rawList != null) {
                    val toKeep = ArrayList<Any?>()
                    var modified = false
                    rawList.forEach { item ->
                        if (item == null) {
                            toKeep.add(item)
                            return@forEach
                        }
                        val titleField = runCatching { item.javaClass.getDeclaredField("title").apply { isAccessible = true } }.getOrNull()
                        val title = titleField?.let { runCatching { it.get(item) as? String }.getOrNull() }?.trim()
                        val isSettings = isMineSettingsItem(title, null)
                        if (!title.isNullOrBlank() && !isSettings) {
                            components.add(title)
                            if (title in hidden) {
                                modified = true
                                return@forEach
                            }
                        }
                        toKeep.add(item)
                    }
                    if (modified) {
                        val success = replaceItemsInPlace(rawList, toKeep)
                        if (!success) {
                            runCatching { listField.set(mineData, toKeep) }
                        }
                    }
                }
            }
        }

        if (components.isNotEmpty()) {
            saveKnownComponents(components)
        }
    }.onFailure { throwable ->
        log("purifyMineData error", throwable)
    }

    private fun replaceItemsInPlace(rawList: List<*>, toKeep: List<Any?>): Boolean = runCatching {
        if (rawList !is MutableList<*>) return false
        @Suppress("UNCHECKED_CAST")
        val mutableList = rawList as MutableList<Any?>
        mutableList.clear()
        mutableList.addAll(toKeep)
        true
    }.getOrDefault(false)

    private fun isMoreServiceGroup(group: Any, items: List<Any?>): Boolean {
        val title = runCatching {
            group.javaClass.getDeclaredMethod("getTitle").invoke(group) as? String
        }.getOrNull()?.trim()
        if (!title.isNullOrBlank()) {
            if (title == "更多服务" || title == "更多服務" || title.contains("更多") || title.contains("More") ||
                title == "其他设置" || title == "其他設定" || title.contains("设置") || title.contains("設定")
            ) {
                return true
            }
        }
        val moduleType = runCatching {
            group.javaClass.getDeclaredMethod("getModuleType").invoke(group) as? Int
        }.getOrNull()
        if (moduleType == 3) {
            return true
        }
        val getItemTitle = classLoader.findClassOrNull(MENU_ITEM_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getTitle" && it.parameterCount == 0 && it.returnType == String::class.java }
        val getItemUri = classLoader.findClassOrNull(MENU_ITEM_CLASS)
            ?.declaredMethods
            ?.firstOrNull { it.name == "getUri" && it.parameterCount == 0 && it.returnType == String::class.java }
        for (item in items) {
            if (item == null) continue
            val itemTitle = runCatching { getItemTitle?.invoke(item) as? String }.getOrNull()?.trim()
            val itemUri = runCatching { getItemUri?.invoke(item) as? String }.getOrNull()?.trim()
            if (itemTitle != null) {
                if (itemTitle.contains("未成年") || itemTitle.contains("青少年") ||
                    itemTitle.contains("客服") || itemTitle.contains("听视频") ||
                    itemTitle.contains("聽視頻") || itemTitle.contains("小黑屋")
                ) {
                    return true
                }
            }
            if (itemUri != null) {
                if (itemUri.contains("teenagers") || itemUri.contains("customer_service") || itemUri.contains("feedback")) {
                    return true
                }
            }
        }
        return false
    }

    private fun createSettingsItem(): Any? {
        val itemClass = classLoader.findClassOrNull(MENU_ITEM_CLASS) ?: return null
        return runCatching {
            val item = itemClass.getDeclaredConstructor().newInstance()
            val hostContext = env.hostContext
            val res = hostContext.resources
            val stringId = res.getIdentifier("settings_global_string_256", "string", env.packageName)
                .takeIf { it != 0 } ?: 0x7f1140bf
            val drawableId = res.getIdentifier("ic_mine_setting", "drawable", env.packageName)
                .takeIf { it != 0 } ?: 0x7f081711
            val title = runCatching { hostContext.getString(stringId) }.getOrNull() ?: "设置"

            itemClass.getDeclaredField("id").apply { isAccessible = true }.set(item, 10L)
            itemClass.getDeclaredField("title").apply { isAccessible = true }.set(item, title)
            itemClass.getDeclaredField("uri").apply { isAccessible = true }.set(item, "activity://main/preference")
            itemClass.getDeclaredField("iconResId").apply { isAccessible = true }.set(item, drawableId)
            itemClass.getDeclaredField("localShow").apply { isAccessible = true }.set(item, true)
            item
        }.onFailure {
            log("MineProfileHook: failed to create fallback settings item", it)
        }.getOrNull()
    }

    private fun recordIntoBacking(group: Any?, element: Any?, insertAtStart: Boolean = false) {
        if (element == null || group == null) return
        runCatching {
            val field = group.javaClass.getDeclaredField("itemList").apply { isAccessible = true }
            val backing = field.get(group)
            if (backing is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val mutable = backing as MutableList<Any?>
                synchronized(mutable) {
                    if (!mutable.contains(element)) {
                        if (insertAtStart) mutable.add(0, element) else mutable.add(element)
                    }
                }
            } else if (backing is List<*>) {
                val newBacking = ArrayList<Any?>(backing)
                if (!newBacking.contains(element)) {
                    if (insertAtStart) newBacking.add(0, element) else newBacking.add(element)
                    field.set(group, newBacking)
                }
            }
        }.onFailure {
            log("MineProfileHook: failed to record element into group backing itemList", it)
        }
    }

    private fun isMineSettingsItem(title: String?, uri: String?, id: Long? = null): Boolean {
        if (id == 10L) return true
        if (ModuleSettings.isMineSettingsTitle(title)) return true
        if (uri != null && (uri.contains("preference") || uri.contains("setting"))) return true
        return false
    }

    private fun saveKnownComponents(names: Set<String>) {
        val filtered = names.filter { !isMineSettingsItem(it, null) }
        val known = ModuleSettings.getKnownMineComponents(prefs).toMutableSet()
        if (!known.addAll(filtered)) return
        ModuleSettings.cacheKnownMineComponents(known)
        prefs.edit().putStringSet(ModuleSettings.KEY_KNOWN_MINE_COMPONENTS, known).apply()
    }

    private companion object {
        private const val TEENAGERS_MODE_CLASS = "com.bilibili.teenagersmode.TeenagersMode"
        private const val VIEW_PAGE_FRAGMENT_CLASS = "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
        private const val MENU_GROUP_CLASS = "com.bilibili.lib.homepage.mine.MenuGroupV2"
        private const val MENU_ITEM_CLASS = "com.bilibili.lib.homepage.mine.MenuGroupV2\$Item"
        private const val VIP_ENTRANCE_KEY = "vip"
    }
}
