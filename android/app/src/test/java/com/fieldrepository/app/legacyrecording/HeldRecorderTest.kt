package com.fieldrepository.app.legacyrecording

import com.fieldrepository.app.data.DraftValues
import com.fieldrepository.app.data.LegacyQuestionnaireSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeldRecorderTest {
    private val main = File("src/main/java/com/fieldrepository/app/MainActivity.kt").readText()

    @Test fun `the created interview's id travels with the draft, so a later Save updates it`() {
        assertTrue(
            "createdId is bound into the questionnaire form's draft",
            main.contains("""var createdId by remember(editing?.id) { mutableStateOf<String?>(null) }.drafted(draft, "createdId")""")
        )
        val restored = DraftValues.decode(DraftValues.encode("int_42")!!)
        assertEquals("int_42", restored)
        assertEquals(LegacyQuestionnaireSave.Target.Update("int_42"), LegacyQuestionnaireSave.target(null, restored as String))
    }

    @Test fun `every recorder the app starts is a held take under the foreground service`() {
        val lines = main.lines()
        val starts = lines.withIndex().filter { (_, line) -> line.contains("createAudioRecorder(") && !line.contains("private fun createAudioRecorder") }
        assertEquals("the legacy questionnaire recorder and startHeldTake, nothing else", 2, starts.size)
        starts.forEach { (index, _) ->
            val next = lines.subList(index, minOf(lines.size, index + 4)).joinToString("\n")
            assertTrue("line ${index + 1} hands the recorder to LegacyTake", next.contains("LegacyTake.begin("))
            assertTrue("line ${index + 1} starts the foreground service", next.contains("LegacyRecordingService.start("))
        }
        assertTrue("no screen stops a raw recorder any more", !main.contains("recorder?.stop()"))
    }
}
