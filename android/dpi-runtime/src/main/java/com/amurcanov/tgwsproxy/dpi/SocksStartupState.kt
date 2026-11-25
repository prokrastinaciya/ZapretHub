package com.amurcanov.tgwsproxy.dpi

/** Serializes worker exit with ready publication and cancellation. */
internal class SocksStartupState {
    private var done = false
    private var ready = false
    @Synchronized fun publishReady(publish: () -> Unit): Boolean {
        if (done) return false
        ready = true
        publish()
        return true
    }
    @Synchronized fun exited(code: Int): Boolean {
        done = true
        return ready
    }
    @Synchronized fun stop() { done = true }
    @Synchronized fun isDone(): Boolean = done
}
