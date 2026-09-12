package ai.kodi.app.accessibility

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The contact ladder is implemented twice - here for the device, and in Python for the
 * eval suite's device double. Both are held to one shared fixture so they cannot drift
 * apart: `backend/evals/fixtures/contact_matching.json`.
 *
 * Picking the wrong person is among this app's worst failure modes, and until
 * [DeviceToolExecutor.matchContact] was extracted it was only reachable through a
 * ContentResolver, so none of it was tested.
 *
 * Gson rather than org.json on purpose: Android stubs org.json for unit tests, so every
 * call would throw "Stub!". Gson is a real dependency here via the Retrofit converter.
 */
class ContactMatchingTest {

    @Test
    fun sharedFixtureCasesAllHold() {
        val fixture = findFixture()
        assumeTrue(
            "shared fixture not found (expected at backend/evals/fixtures/contact_matching.json)",
            fixture != null,
        )

        val cases = JsonParser.parseString(fixture!!.readText())
            .asJsonObject.getAsJsonArray("cases")
        assertTrue("fixture should not be empty", cases.size() > 0)

        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val why = case.get("why")?.asString ?: ""
            val candidates = case.getAsJsonArray("contacts").map {
                val row = it.asJsonArray
                row.get(0).asString to row.get(1).asString
            }
            val expectedElement = case.get("expected")
            val expected =
                if (expectedElement == null || expectedElement.isJsonNull) null
                else expectedElement.asString

            val actual = DeviceToolExecutor.matchContact(candidates, case.get("needle").asString)
            assertEquals("$id: $why", expected, actual)
        }
    }

    // A few cases are also asserted directly, so the ladder keeps coverage even if the
    // fixture cannot be located (a packaged test run, say).

    @Test
    fun exactMatchBeatsPrefix() {
        val contacts = listOf("Amara" to "+260971111111", "Amara Banda" to "+260972222222")
        assertEquals("+260971111111", DeviceToolExecutor.matchContact(contacts, "Amara"))
    }

    @Test
    fun prefixBeatsContains() {
        val contacts = listOf("Bwalya Phiri" to "+260973333333", "John Bwalya" to "+260974444444")
        assertEquals("+260973333333", DeviceToolExecutor.matchContact(contacts, "Bwalya"))
    }

    @Test
    fun containsRequiresThreeCharacters() {
        val contacts = listOf("Chanda" to "+260976666666")
        assertNull(DeviceToolExecutor.matchContact(contacts, "an"))
        assertEquals("+260976666666", DeviceToolExecutor.matchContact(contacts, "han"))
    }

    @Test
    fun bareNumberIsUsedDirectly() {
        assertEquals(
            "+260971234567",
            DeviceToolExecutor.matchContact(emptyList(), "+260 97 123 4567"),
        )
    }

    @Test
    fun unknownNameResolvesToNothing() {
        val contacts = listOf("Amara" to "+260971111111")
        assertNull(DeviceToolExecutor.matchContact(contacts, "Mutale"))
    }

    @Test
    fun emptyNeedleDoesNotResolveToAnEmptyNumber() {
        // Regression: `"".all { ... }` is true, so an empty contact used to fall through
        // the digits branch and return "", which was then handed to SmsManager.
        assertNull(DeviceToolExecutor.matchContact(listOf("Amara" to "+260971111111"), ""))
        assertNull(DeviceToolExecutor.matchContact(emptyList(), "   "))
    }

    @Test
    fun blankCandidateRowsAreSkipped() {
        val contacts = listOf("" to "+260970000000", "Amara" to "", "Amara" to "+260971111111")
        assertEquals("+260971111111", DeviceToolExecutor.matchContact(contacts, "Amara"))
    }

    @Test
    fun storedNumberSpacesAreStripped() {
        val contacts = listOf("Amara" to "+260 97 111 1111")
        assertEquals("+260971111111", DeviceToolExecutor.matchContact(contacts, "Amara"))
    }

    /** Walk up from the working directory to find the repo-shared fixture. */
    private fun findFixture(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "backend/evals/fixtures/contact_matching.json")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }
}
