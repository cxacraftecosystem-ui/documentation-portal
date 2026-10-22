package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** C9: the start sheet fills the place from the phone as "District, State", and only the start sheet does. */
class SessionPlaceTest {
    @QFeature("C9")
    @Test fun `the place reads district and state, whichever the geocoder has`() {
        assertEquals("Jaipur, Rajasthan", placeWords(" Jaipur ", "Rajasthan"))
        assertEquals("Rajasthan", placeWords(null, "Rajasthan"))
        assertEquals("Delhi", placeWords("Delhi", "Delhi"))
        assertNull(placeWords(" ", null))
        assertEquals("Use my location", QuestionnaireCopy.s("place.useLocation"))
    }

    @QFeature("C9")
    @Test fun `only the start sheet captures the place, never an edit`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/java") }.first { it.isDirectory }
        val users = root.walkTopDown().filter { it.extension == "kt" && it.name != "SessionPlace.kt" }
            .filter { it.readText().contains("PlaceFromDevice(") }.map { it.name }.toList()
        assertEquals(listOf("SessionStartSheet.kt"), users)
        assertTrue(File(root.walkTopDown().first { it.name == "SessionStartSheet.kt" }.path).readText().contains("trailingIcon = { PlaceFromDevice("))
    }
}
