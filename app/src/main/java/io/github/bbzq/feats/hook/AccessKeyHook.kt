package io.github.bbzq.feats.hook

import android.content.Context
import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.callStaticMethod
import io.github.bbzq.feats.from

class AccessKeyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        // 浠呭湪涓昏繘绋嬩腑杩愯
        if (env.processName != env.packageName) return
        
        AccessKeyRepository.register {
            runCatching { getAccessKey() }.getOrNull()
        }
        log("AccessKeyHook installed")
    }

    private fun getAccessKey(): String? {
        val biliAccountsClass = findBiliAccountsClass() ?: return null
        val context = env.hostContext
        
        // 灏濊瘯鑾峰彇褰撳墠璐﹀彿瀹炰緥
        val account = biliAccountsClass.callStaticMethod("get", context) 
            ?: biliAccountsClass.callStaticMethod("a", context)
            ?: return null
            
        // 灏濊瘯璋冪敤 getAccessKey 鏂规硶
        return (account.callMethod("getAccessKey") as? String)
            ?: (account.callMethod("a") as? String) // 甯歌娣锋穯鍚?
    }

    private fun findBiliAccountsClass(): Class<*>? {
        // 濡傛灉纭紪鐮佺殑绫诲悕鏃犳硶鎵惧埌锛屽彲鑳介渶瑕佷娇鐢?dexHelper 鎼滅储瀛楃涓?"initFacial enter" 鏉ュ畾浣嶈绫?
        return "com.bilibili.lib.accounts.BiliAccounts".from(classLoader)
            ?: "com.bilibili.p4439app.accounts.BiliAccounts".from(classLoader)
            ?: "com.bilibili.app.accounts.BiliAccounts".from(classLoader)
    }
}

