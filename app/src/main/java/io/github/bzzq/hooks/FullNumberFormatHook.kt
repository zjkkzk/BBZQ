package io.github.bzzq.hooks

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class FullNumberFormatHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val accountMine = HostAccess.findClass(classLoader, ACCOUNT_MINE_CLASS)
        val memberCard = HostAccess.findClass(classLoader, MEMBER_CARD_CLASS)

        val mineMethods = accountMine?.let(::findMineBindMethods).orEmpty()
        val spaceMethods = memberCard?.let(::findSpaceBindMethods).orEmpty()

        mineMethods.forEach(::hookMineBind)
        spaceMethods.forEach(::hookSpaceBind)
        log("Installed full-number hooks: mine=${mineMethods.size}, space=${spaceMethods.size}")
    }

    private fun findMineBindMethods(accountMine: Class<*>): List<Method> {
        val fixed = MINE_FRAGMENT_CLASSES
            .mapNotNull { HostAccess.findClass(classLoader, it) }
            .flatMap { HostAccess.methods(it).toList() }
            .filter { isMineBind(it, accountMine) }
        if (fixed.isNotEmpty()) return fixed.distinctBy(Method::toGenericString)

        val bridge = context.dexKitOrNull() ?: return emptyList()
        return runCatching {
            bridge.findMethod {
                searchPackages("tv.danmaku.bili", "com.bilibili")
                matcher {
                    returnType(Void.TYPE)
                    paramTypes(accountMine, Boolean::class.javaPrimitiveType!!)
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.filter { method ->
                method.declaringClass.name.contains("mine", true) ||
                    method.declaringClass.simpleName.contains("UserCenter", true)
            }
        }.onFailure { log("Failed to discover mine number bind method", it) }
            .getOrDefault(emptyList())
            .distinctBy(Method::toGenericString)
    }

    private fun findSpaceBindMethods(memberCard: Class<*>): List<Method> {
        val fixed = SPACE_FRAGMENT_CLASSES
            .mapNotNull { HostAccess.findClass(classLoader, it) }
            .flatMap { HostAccess.methods(it).toList() }
            .filter { isSpaceBind(it, memberCard) }
        if (fixed.isNotEmpty()) return fixed.distinctBy(Method::toGenericString)

        val bridge = context.dexKitOrNull() ?: return emptyList()
        return runCatching {
            bridge.findMethod {
                searchPackages("com.bilibili.app.authorspace")
                matcher {
                    returnType(Void.TYPE)
                    paramTypes(memberCard)
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }
        }.onFailure { log("Failed to discover space number bind method", it) }
            .getOrDefault(emptyList())
            .distinctBy(Method::toGenericString)
    }

    private fun hookMineBind(method: Method) {
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) return@intercept result
                val model = chain.getArg(0) ?: return@intercept result
                applyWhenReady(
                    fragment = chain.thisObject,
                    values = listOf(
                        NumberValue(listOf("following_count", "dynamic_count"), listOf("动态"), HostAccess.getLong(model, "dynamic")),
                        NumberValue(listOf("attention_count"), listOf("关注"), HostAccess.getLong(model, "following")),
                        NumberValue(listOf("fans_count", "follower_count"), listOf("粉丝"), HostAccess.getLong(model, "follower")),
                    ),
                )
                result
            }
    }

    private fun hookSpaceBind(method: Method) {
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) return@intercept result
                val model = chain.getArg(0) ?: return@intercept result
                val likesObject = HostAccess.get(model, "likes", "like", "likeInfo")
                applyWhenReady(
                    fragment = chain.thisObject,
                    values = listOf(
                        NumberValue(
                            listOf("fans", "fans_count", "follower_count"),
                            listOf("粉丝"),
                            HostAccess.getLong(model, "mFollowers", "followers", "fans"),
                        ),
                        NumberValue(
                            listOf("attentions", "attention_count", "following_count"),
                            listOf("关注"),
                            HostAccess.getLong(model, "mFollowings", "followings", "attentions"),
                        ),
                        NumberValue(
                            listOf("likes", "like_num", "like_count"),
                            listOf("获赞", "点赞"),
                            likesObject?.let { HostAccess.getLong(it, "likeNum", "like_num", "likes") },
                        ),
                    ),
                )
                result
            }
    }

    private fun applyWhenReady(fragment: Any?, values: List<NumberValue>) {
        val root = resolveRootView(fragment) ?: return
        applyValues(root, values)
        APPLY_DELAYS.forEach { delay ->
            root.postDelayed({
                if (ModuleSettings.isFullNumberFormatEnabled(prefs)) applyValues(root, values)
            }, delay)
        }
    }

    private fun resolveRootView(target: Any?): View? {
        if (target is View) return target
        if (target == null) return null
        return HostAccess.invoke(target, "getView") as? View
            ?: HostAccess.get(target, "mView", "view", "rootView", "mRootView") as? View
    }

    private fun applyValues(root: View, values: List<NumberValue>) {
        values.forEach { item ->
            val value = item.value ?: return@forEach
            if (!setById(root, item.ids, value)) {
                setByLabel(root, item.labels, value)
            }
        }
    }

    private fun setById(root: View, names: List<String>, value: Long): Boolean {
        names.forEach { name ->
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            if (id == 0) return@forEach
            val target = root.findViewById<View>(id) as? TextView ?: return@forEach
            target.text = value.toString()
            return true
        }
        return false
    }

    private fun setByLabel(root: View, labels: List<String>, value: Long): Boolean {
        val label = findTextView(root) { text -> labels.any(text::contains) } ?: return false
        var parent = label.parent as? ViewGroup
        repeat(3) {
            val candidate = parent?.descendantTextViews()
                ?.filter { it !== label }
                ?.firstOrNull { it.text?.any(Char::isDigit) == true }
            if (candidate != null) {
                candidate.text = value.toString()
                return true
            }
            parent = parent?.parent as? ViewGroup
        }
        return false
    }

    private fun findTextView(root: View, predicate: (String) -> Boolean): TextView? {
        if (root is TextView && predicate(root.text?.toString().orEmpty())) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findTextView(group.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun ViewGroup.descendantTextViews(): List<TextView> {
        val result = mutableListOf<TextView>()
        for (index in 0 until childCount) {
            when (val child = getChildAt(index)) {
                is TextView -> result += child
                is ViewGroup -> result += child.descendantTextViews()
            }
        }
        return result
    }

    private fun isMineBind(method: Method, model: Class<*>): Boolean =
        method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(model, Boolean::class.javaPrimitiveType))

    private fun isSpaceBind(method: Method, model: Class<*>): Boolean =
        method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(model))

    private data class NumberValue(
        val ids: List<String>,
        val labels: List<String>,
        val value: Long?,
    )

    private companion object {
        private const val ACCOUNT_MINE_CLASS = "tv.danmaku.bili.ui.main2.api.AccountMine"
        private const val MEMBER_CARD_CLASS = "com.bilibili.app.authorspace.api.BiliMemberCard"
        private val MINE_FRAGMENT_CLASSES = listOf(
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
            "tv.danmaku.bilibilihd.ui.main.mine.HdHomeUserCenterFragment",
        )
        private val SPACE_FRAGMENT_CLASSES = listOf(
            "com.bilibili.app.authorspace.ui.SpaceHeaderFragment2",
        )
        private val APPLY_DELAYS = longArrayOf(16L, 80L, 200L, 500L, 1000L)
    }
}
