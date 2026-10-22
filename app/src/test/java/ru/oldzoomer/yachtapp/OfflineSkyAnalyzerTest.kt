package ru.oldzoomer.yachtapp

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.oldzoomer.yachtapp.data.Direction
import ru.oldzoomer.yachtapp.data.Observation

class OfflineSkyAnalyzerTest {

    @Test
    fun directionEnum_hasCorrectAnglesAndNames() {
        val sequence = Direction.sequenceForCapture()
        assertEquals(5, sequence.size)
        assertEquals(Direction.WINDWARD, sequence[0])
        assertEquals(Direction.NORTH, sequence[4])
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
}
