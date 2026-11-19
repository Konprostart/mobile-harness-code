package com.mobileharness.code.devtools

import android.util.Log
import com.mobileharness.code.BuildConfig

/**
 * Logcat sink that is compiled out of release builds.
 *
 * The release APK ships with [BuildConfig.DEBUG] == false, so every call here becomes a no-op in
 * production. This enforces the project's zero-telemetry / privacy policy: user prompts, agent
 * output, environment variable names, base URLs and internal diagnostics are never written to the
 * system log on a user's device in a release build, while debug builds keep full verbose logging
 * for development.
 */
object DebugLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, message, throwable)
    }
}
