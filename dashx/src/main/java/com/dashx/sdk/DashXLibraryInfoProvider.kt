package com.dashx.android

/**
 * SPI interface for wrapper SDKs (e.g. React Native, Flutter) to declare
 * their library name and version. The Android SDK discovers implementations
 * via [java.util.ServiceLoader] at configure-time and uses them to populate
 * the library info in SystemContext.
 *
 * Wrapper SDKs should:
 * 1. Implement this interface.
 * 2. Register the implementation in
 *    `META-INF/services/com.dashx.android.DashXLibraryInfoProvider`.
 */
interface DashXLibraryInfoProvider {
    val name: String
    val version: String
}
