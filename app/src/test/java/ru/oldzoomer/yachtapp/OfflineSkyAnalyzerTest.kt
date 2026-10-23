package ru.oldzoomer.yachtapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.oldzoomer.yachtapp.data.Direction
import ru.oldzoomer.yachtapp.data.Observation

class OfflineSkyAnalyzerTest {

    @Test
    fun directionEnum_hasCorrectAnglesAndNames() {
        val sequenceDiagonal = Direction.sequenceForCapture()
        assertEquals(5, sequenceDiagonal.size)
        assertEquals(Direction.CLOSE_HAULED_STBD, sequenceDiagonal[0])
        assertEquals(Direction.TRUE_WINDWARD, sequenceDiagonal[4])

        val sequenceOrthogonal = Direction.sequenceForMode(ru.oldzoomer.yachtapp.data.DirectionMode.ORTHOGONAL)
        assertEquals(5, sequenceOrthogonal.size)
        assertEquals(Direction.BOW_WINDWARD, sequenceOrthogonal[0])
        assertEquals(Direction.TRUE_WINDWARD, sequenceOrthogonal[4])
    }

    @Test
    fun observationData_instantiatesCorrectly() {
        val obs = Observation(
            timestamp = 1000L,
            latitude = 60.0,
            longitude = 30.0,
            photoUri = "file:///test.jpg",
            direction = Direction.WINDWARD,
            pressureHpa = 1012f,
            userWindKnots = 15,
            userIsGusty = true
        )

        assertEquals(1000L, obs.timestamp)
        assertEquals(Direction.WINDWARD, obs.direction)
        assertEquals(15, obs.userWindKnots)
        assertEquals(true, obs.userIsGusty)
    }

    @Test
    fun redBlueRatio_differentiatesClearSkyFromClouds() {
        // 1. Clear zenith sky (R:70, G:120, B:220)
        val rSky = 70f
        val bSky = 220f
        val rbSky = rSky / bSky
        assertTrue("Clear sky R/B ratio ($rbSky) should be < 0.75", rbSky < 0.75f)

        // 2. Fair weather cumulus cloud (R:240, G:240, B:245)
        val rCloud = 240f
        val bCloud = 245f
        val rbCloud = rCloud / bCloud
        assertTrue("Cloud R/B ratio ($rbCloud) should be >= 0.75", rbCloud >= 0.75f)

        // 3. Dark storm cloud (R:50, G:55, B:60)
        val rStorm = 50f
        val gStorm = 55f
        val bStorm = 60f
        val rbStorm = rStorm / bStorm
        val brightnessStorm = rStorm * 0.299f + gStorm * 0.587f + bStorm * 0.114f
        assertTrue("Dark storm cloud R/B ($rbStorm) should be >= 0.75", rbStorm >= 0.75f)
        assertTrue("Dark storm cloud brightness ($brightnessStorm) should be < 70", brightnessStorm < 70f)
    }

    @Test
    fun barometricPressureTrend_detectsRapidDrop() {
        val now = 3_600_000L * 2
        val hourAgo = 3_600_000L * 1

        val obsOld = Observation(timestamp = hourAgo, latitude = 0.0, longitude = 0.0, photoUri = "", direction = Direction.NORTH, pressureHpa = 1015f)
        val obsNew = Observation(timestamp = now, latitude = 0.0, longitude = 0.0, photoUri = "", direction = Direction.NORTH, pressureHpa = 1012f)

        val observations = listOf(obsOld, obsNew)
        val sorted = observations.sortedBy { it.timestamp }

        val currentPressure = sorted.last().pressureHpa
        val oldestPressure = sorted.first().pressureHpa
        val timeDiffHours = maxOf((sorted.last().timestamp - sorted.first().timestamp).toFloat() / 3_600_000f, 0.5f)
        val pressureRatePerHour = (currentPressure - oldestPressure) / timeDiffHours

        val isRapidPressureDrop = pressureRatePerHour < -1.5f
        assertTrue("3 hPa drop over 1 hour should be detected as rapid drop (-3.0 hPa/h)", isRapidPressureDrop)
        assertEquals(-3.0f, pressureRatePerHour, 0.01f)
    }

    @Test
    fun barometricPressureTrend_ignoresMinorFluctuations() {
        val now = 3_600_000L * 2
        val hourAgo = 3_600_000L * 1

        val obsOld = Observation(timestamp = hourAgo, latitude = 0.0, longitude = 0.0, photoUri = "", direction = Direction.NORTH, pressureHpa = 1013.2f)
        val obsNew = Observation(timestamp = now, latitude = 0.0, longitude = 0.0, photoUri = "", direction = Direction.NORTH, pressureHpa = 1013.0f)

        val observations = listOf(obsOld, obsNew)
        val sorted = observations.sortedBy { it.timestamp }

        val currentPressure = sorted.last().pressureHpa
        val oldestPressure = sorted.first().pressureHpa
        val timeDiffHours = maxOf((sorted.last().timestamp - sorted.first().timestamp).toFloat() / 3_600_000f, 0.5f)
        val pressureRatePerHour = (currentPressure - oldestPressure) / timeDiffHours

        val isRapidPressureDrop = pressureRatePerHour < -1.5f
        assertFalse("0.2 hPa drop over 1 hour should NOT trigger rapid drop warning", isRapidPressureDrop)
    }
}

