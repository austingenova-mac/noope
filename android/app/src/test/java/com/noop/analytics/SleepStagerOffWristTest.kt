package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests SleepStager's off-wrist backstop (#500). A wrist-OFF stretch reads as perfectly still
 * gravity with no contrary motion, so the gravity spine classifies it as sleep — and because the
 * off-wrist epochs carry zero/missing HR the daytime guard treats them as "missing data" and lets
 * them through. The backstop rejects any candidate sleep run whose HR coverage has a contiguous gap
 * longer than offWristHRGapMin (20 min), and — when available — any run overlapping a WRIST_OFF
 * event. Faithful Kotlin mirror of the off-wrist cases in SleepStagerTests.swift.
 */
class SleepStagerOffWristTest {

    private val dev = "test"

    /** 2025-06-10 00:00:00 UTC — an arbitrary fixed midnight (ref % 86400 == 0). */
    private val refMidnight = 1_749_513_600L

    private fun startAtHour(hourUTC: Int): Long = refMidnight + hourUTC * 3_600L

    private fun stillGravity(start: Long, durationS: Int): List<GravitySample> =
        (0 until durationS).map { GravitySample(deviceId = dev, ts = start + it, x = 0.0, y = 0.0, z = 1.0) }

    private fun activeGravity(start: Long, durationS: Int): List<GravitySample> =
        (0 until durationS).map { i ->
            val phase = (i % 2) * 0.5
            GravitySample(deviceId = dev, ts = start + i, x = phase, y = 0.0, z = 1.0)
        }

    private fun hrStream(start: Long, durationS: Int, bpm: Int): List<HrSample> =
        (0 until durationS).map { HrSample(deviceId = dev, ts = start + it, bpm = bpm) }

    @Test
    fun offWristDaytimeGapNotSleep() {
        // A long, still daytime stretch where HR has a >20-min contiguous gap (strap off the wrist)
        // must NOT be classified as sleep. The dip-confirming HR before the gap would even satisfy
        // the daytime guard, so ONLY the HR-gap backstop rejects it.
        val dayStart = startAtHour(10)          // 10:00 active context, HR 72 (lifts the baseline)
        val dayDur = 2 * 60 * 60
        val dayGrav = activeGravity(dayStart, dayDur)
        val dayHR = hrStream(dayStart, dayDur, 72)

        // 12:00 the strap goes still on a desk for 2 h (≥90-min daytime minimum, center in [11,20)).
        val offStart = dayStart + dayDur
        val offDur = 2 * 60 * 60
        val offGrav = stillGravity(offStart, offDur)
        // HR covers only the FIRST 20 min at a low 50 bpm (a real dip), then NOTHING — a >20-min gap.
        val offHR = hrStream(offStart, 20 * 60, 50)

        val sessions = SleepStager.detectSleep(hr = dayHR + offHR, gravity = dayGrav + offGrav)
        assertTrue(
            "a still daytime stretch with a >20-min HR-coverage gap is off-wrist, not sleep",
            sessions.isEmpty(),
        )
    }

    @Test
    fun wornNightWithDenseHRStillRegisters() {
        // The backstop must NOT suppress a genuine worn night: dense 1 Hz HR has no gap.
        val start = startAtHour(2)
        val dur = 90 * 60
        val grav = stillGravity(start, dur)
        val hr = hrStream(start, dur, 50)
        val sessions = SleepStager.detectSleep(hr = hr, gravity = grav)
        assertEquals("a worn night with dense, gap-free HR must still register", 1, sessions.size)
    }

    @Test
    fun wristOffEventDropsRun() {
        // The explicit-event path (#500 bonus): a WRIST_OFF event inside an otherwise-valid window
        // drops it, even though the HR here is dense and gap-free.
        val start = startAtHour(2)
        val dur = 90 * 60
        val grav = stillGravity(start, dur)
        val hr = hrStream(start, dur, 50)
        assertEquals(1, SleepStager.detectSleep(hr = hr, gravity = grav).size)
        val withEvent = SleepStager.detectSleep(hr = hr, gravity = grav, wristOff = listOf(start + 30 * 60))
        assertTrue("a WRIST_OFF event inside the run must drop it", withEvent.isEmpty())
    }

    @Test
    fun hasOffWristHRGapBoundaries() {
        val p = SleepStager.Period(stage = "sleep", start = 0L, end = 3_600L)
        // Dense coverage → no gap.
        val dense = (0..3_600).map { HrSample(deviceId = dev, ts = it.toLong(), bpm = 50) }
        assertFalse(SleepStager.hasOffWristHRGap(p, dense))
        // A single 21-min interior gap (> 20 min) → tripped.
        val gappy = (0..600).map { HrSample(deviceId = dev, ts = it.toLong(), bpm = 50) } +
            (1_860..3_600).map { HrSample(deviceId = dev, ts = it.toLong(), bpm = 50) } // gap 600→1860 = 1260 s
        assertTrue(SleepStager.hasOffWristHRGap(p, gappy))
        // No HR stream at all → false (can't assert off-wrist without HR; other guards handle it).
        assertFalse(SleepStager.hasOffWristHRGap(p, emptyList()))
    }
}
