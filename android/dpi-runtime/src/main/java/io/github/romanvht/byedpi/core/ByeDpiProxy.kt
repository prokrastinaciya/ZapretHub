package io.github.romanvht.byedpi.core

/**
 * Thin JNI adapter for the pinned `libbyedpi.so`. The fully-qualified class
 * name is dictated by the native symbols compiled from the unchanged upstream
 * `byedpi-jni.c`; do not rename this class.
 *
 * All calls are made on a worker thread, never the Android UI thread. Each
 * `startProxy` runs the engine's blocking accept loop until `stopProxy`
 * shuts the listening socket down.
 */
class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    fun startProxy(args: Array<String>): Int = jniStartProxy(args)

    fun stopProxy(): Int = jniStopProxy()

    private external fun jniStartProxy(args: Array<String>): Int

    private external fun jniStopProxy(): Int
}
