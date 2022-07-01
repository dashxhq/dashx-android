@file:JvmName("Utils")

package com.dashx.sdk

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.Context
import android.content.SharedPreferences

fun getPackageInfo(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
fun getPrefKey(context: Context) = "$PACKAGE_NAME.$DEFAULT_INSTANCE.$context.packageName"
fun
    getDashXSharedPreferences(context: Context): SharedPreferences = context.getSharedPreferences(getPrefKey(context), Context.MODE_PRIVATE)
