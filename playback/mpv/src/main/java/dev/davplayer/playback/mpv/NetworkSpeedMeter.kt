package dev.davplayer.playback.mpv

import java.util.concurrent.atomic.AtomicLong

/** Counts upstream video body bytes, excluding disk hits and loopback traffic. */
internal class NetworkSpeedMeter(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val bytes = AtomicLong()
    private var lastSample = clockMs()

    fun add(count: Int) { if (count > 0) bytes.addAndGet(count.toLong()) }

    fun sample(): Long {
        val now = clockMs()
        val elapsed = now - lastSample
        if (elapsed <= 0) return 0
        lastSample = now
        return (bytes.getAndSet(0).toDouble() * 1_000 / elapsed).toLong().coerceAtLeast(0)
    }
}
