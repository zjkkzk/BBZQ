package io.github.bbzq

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class ReadableModulePreferences(
    private val context: Context,
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {

    init {
        ensureReadable()
    }

    override fun edit(): SharedPreferences.Editor =
        ReadableEditor(delegate.edit())

    fun ensureReadable() {
        val dataDir = File(context.applicationInfo.dataDir)
        val prefsDir = File(dataDir, "shared_prefs")
        val prefsFile = File(prefsDir, "${ModuleSettings.PREFS_NAME}.xml")
        dataDir.setExecutable(true, false)
        prefsDir.setExecutable(true, false)
        prefsDir.setReadable(true, false)
        prefsFile.setReadable(true, false)
    }

    private inner class ReadableEditor(
        private val editor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by editor {
        override fun apply() {
            editor.apply()
            ensureReadable()
        }

        override fun commit(): Boolean {
            val result = editor.commit()
            ensureReadable()
            return result
        }
    }
}
