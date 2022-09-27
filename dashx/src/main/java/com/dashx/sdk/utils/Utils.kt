@file:JvmName("Utils")

package com.dashx.sdk.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

fun getPackageInfo(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)

fun getPrefKey(context: Context) = "$PACKAGE_NAME.$DEFAULT_INSTANCE.$context.packageName"

fun getDashXSharedPreferences(context: Context): SharedPreferences = context.getSharedPreferences(getPrefKey(context), Context.MODE_PRIVATE)

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

fun getBytes(inputStream: InputStream): ByteArray {
    val byteBuffer = ByteArrayOutputStream()
    val bufferSize = 1024
    val buffer = ByteArray(bufferSize)
    var len = inputStream.read(buffer)
    while (len != -1) {
        if (len == -1)
            break
        byteBuffer.write(buffer, 0, len)
        len = inputStream.read(buffer)
    }
    return byteBuffer.toByteArray()
}

fun generateMuxVideoUrl(playbackId: String?): String {
    return "https://stream.mux.com/$playbackId.m3u8"
}
