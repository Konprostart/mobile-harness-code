package com.mobileharness.code.devtools

import android.content.Context
import android.os.Process
import com.mobileharness.code.BuildConfig
import kotlin.system.exitProcess

/**
 * Global uncaught-exception handler that records every app crash to [CrashLogStore]
 * before handing control back to the previous handler (normally Android's default,
 * which surfaces the system "app keeps stopping" dialog).
 *
 * It is deliberately defensive: a failure to persist the crash must never swallow the
 * original crash, so everything is wrapped in `runCatching` and the delegate is always
 * invoked. It never starts a new thread or touches the UI — it runs synchronously on
 * the crashing thread, which is required by the `UncaughtExceptionHandler` contract.
 */
class CrashHandler(
    private val context: Context,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            CrashLogStore.write(context, CrashRecord.from(thread, throwable, BuildConfig.VERSION_NAME))
        }
        val previous = delegate
        if (previous != null) {
            previous.uncaughtException(thread, throwable)
        } else {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        /** Idempotent: installs once per process and never stacks another wrapper. */
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            // Housekeeping: drop crash records older than 30 days so they cannot accumulate.
            runCatching { CrashLogStore.pruneStale(CrashLogStore.directory(context.applicationContext)) }
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, current))
        }
    }
}
