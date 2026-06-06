package ai.kodi.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.TimeZone

/** Pure-JVM tests for the scheduled-task timestamp parser. */
class DeviceToolExecutorTest {

    @Test
    fun equivalentOffsetsParseToSameInstant() {
        val a = DeviceToolExecutor.parseLocalIso("2026-05-21T20:00:00+01:00")
        val b = DeviceToolExecutor.parseLocalIso("2026-05-21T19:00:00+00:00")
        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test
    fun offsetShiftChangesInstantByExactHour() {
        val plus1 = DeviceToolExecutor.parseLocalIso("2026-05-21T20:00:00+01:00")!!
        val plus2 = DeviceToolExecutor.parseLocalIso("2026-05-21T20:00:00+02:00")!!
        // A later UTC offset means the same wall-clock time is an earlier instant.
        assertEquals(3_600_000L, plus1 - plus2)
    }

    @Test
    fun tzLessIsoUsesDeviceLocalZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val tzLess = DeviceToolExecutor.parseLocalIso("2026-05-21T20:00:00")
        val explicit = DeviceToolExecutor.parseLocalIso("2026-05-21T20:00:00+00:00")
        assertEquals(explicit, tzLess)
    }

    @Test
    fun acceptsMinutePrecisionAndSpaceSeparator() {
        assertNotNull(DeviceToolExecutor.parseLocalIso("2026-05-21T20:00"))
        assertNotNull(DeviceToolExecutor.parseLocalIso("2026-05-21 20:00"))
        assertNotNull(DeviceToolExecutor.parseLocalIso("2026-05-21 20:00:00"))
    }

    @Test
    fun returnsNullOnGarbage() {
        assertNull(DeviceToolExecutor.parseLocalIso("not-a-date"))
        assertNull(DeviceToolExecutor.parseLocalIso(""))
        assertNull(DeviceToolExecutor.parseLocalIso("21/05/2026 8pm"))
    }
}
