@file:JvmName("Utils")

package com.dashx.android.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import java.io.InputStream

fun getPackageInfo(context: Context): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
    }

fun getPrefKey(context: Context) = "$PACKAGE_NAME.$DEFAULT_INSTANCE.packageName"

private const val ENCRYPTED_PREFS_FILE = "$PACKAGE_NAME.$DEFAULT_INSTANCE.secure"

fun getDashXSharedPreferences(context: Context): SharedPreferences {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val encryptedPrefs = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateFromLegacyPrefs(context, encryptedPrefs)
            return encryptedPrefs
        } catch (_: Exception) {
            // Fall through to unencrypted SharedPreferences
        }
    }
    return context.getSharedPreferences(getPrefKey(context), Context.MODE_PRIVATE)
}

private fun migrateFromLegacyPrefs(context: Context, encryptedPrefs: SharedPreferences) {
    val legacyPrefs = context.getSharedPreferences(getPrefKey(context), Context.MODE_PRIVATE)
    val entries = legacyPrefs.all
    if (entries.isEmpty()) return

    val editor = encryptedPrefs.edit()
    for ((key, value) in entries) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }
    editor.apply()
    legacyPrefs.edit().clear().apply()
}

fun getFileContentType(context: Context?, file: File): String {
    val contentResolver = context?.contentResolver
    val mimeType = contentResolver?.getType(Uri.fromFile(file))
    if (mimeType != null && mimeType.startsWith("image")) {
        return FileConstants.IMAGE_CONTENT_TYPE
    } else if (mimeType != null && mimeType.startsWith("video")) {
        return FileConstants.VIDEO_CONTENT_TYPE
    }
    return FileConstants.FILE_CONTENT
}

fun generateMuxVideoUrl(playbackId: String?): String {
    return "https://stream.mux.com/$playbackId.m3u8"
}
