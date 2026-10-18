package dev.davplayer.playback.mpv

import androidx.test.platform.app.InstrumentationRegistry
import `is`.xyz.mpv.MPVLib
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Runs on an ABI-matched Android device/emulator; actually initializes and decodes with JNI. */
class NativeMpvSmokeTest {
    @Test fun initializesDecodesAndReleasesNativePlayerTwice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sample = context.cacheDir.resolve("mpv-smoke.wav")
        val rate = 8_000
        val frames = rate * 2
        val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { wav.putShort((sin(2 * PI * 440 * it / rate) * 4_000).toInt().toShort()) }
        sample.writeBytes(wav.array())
        try {
            repeat(2) {
                val loaded = CountDownLatch(1)
                val progressed = CountDownLatch(1)
                val ended = CountDownLatch(1)
                val client = NativeMpvClient(context,
                    onProperty = { name, value ->
                        if (name == "time-pos" && value is Double && value >= 0.25) progressed.countDown()
                        if (name == "eof-reached" && value == true) ended.countDown()
                    },
                    onEvent = { if (it == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) loaded.countDown() })
                try {
                    onMain {
                        MPVLib.ensureLoaded()
                        client.initialize(hardware = false, paused = false, speed = 1f)
                        // Decode without requesting a display or producing sound during tests.
                        MPVLib.setPropertyString("vo", "null")
                        MPVLib.setPropertyString("ao", "null")
                        client.load(sample.absolutePath, 0)
                    }
                    assertTrue("Native file-loaded callback missing", loaded.await(10, TimeUnit.SECONDS))
                    assertTrue("Native decoded position did not advance", progressed.await(10, TimeUnit.SECONDS))
                    assertTrue("Native EOF callback missing", ended.await(10, TimeUnit.SECONDS))
                } finally { onMain { client.close() } }
            }
        } finally { sample.delete() }
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw AssertionError("Native operation failed", it) }
    }
}
