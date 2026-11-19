package com.mobileharness.code.device

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import moe.shizuku.server.IRemoteProcess

/**
 * A [Process] view over a Shizuku [IRemoteProcess]. Mirrors the pattern already used by
 * [com.mobileharness.code.runtime.NativeSpawnProcess]: stdin/stdout/stderr are backed by
 * [ParcelFileDescriptor] auto-close streams and lifecycle calls delegate to the remote binder.
 */
internal class ShizukuProcess(private val remote: IRemoteProcess) : Process() {
    override fun getOutputStream(): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)

    override fun getInputStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)

    override fun getErrorStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun waitFor(): Int = remote.waitFor()

    override fun exitValue(): Int = remote.exitValue()

    override fun destroy() {
        runCatching { remote.destroy() }
    }

    override fun isAlive(): Boolean = runCatching { remote.alive() }.getOrDefault(false)
}
