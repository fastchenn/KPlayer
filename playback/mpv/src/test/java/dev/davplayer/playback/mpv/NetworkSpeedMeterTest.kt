package dev.davplayer.playback.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedMeterTest {
    @Test fun reportsBytesPerSecondAndResetsWhenDownloadsStop() {
        var time = 0L
        val meter = NetworkSpeedMeter { time }
        meter.add(1024)
        meter.add(1024)
        time = 500
        assertEquals(4096L, meter.sample())
        time = 1500
        assertEquals(0L, meter.sample())
    }

    @Test fun sameTimestampDoesNotLoseBytesOrDivideByZero() {
        var time = 0L
        val meter = NetworkSpeedMeter { time }
        meter.add(100)
        assertEquals(0L, meter.sample())
        meter.add(-1)
        time = 1000
        assertEquals(100L, meter.sample())
    }
}
