package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A10: the camera reopens after each photo until the researcher backs out. */
class KeepShootingCameraTest {

    @Test
    fun `a saved shot opens the camera again while the setting is on`() {
        assertEquals(KeepShootingStep.Again, keepShootingStep(success = true, keepShooting = true, takenSoFar = 0))
        assertEquals(KeepShootingStep.Again, keepShootingStep(success = true, keepShooting = true, takenSoFar = 7))
    }

    @Test
    fun `backing out of the camera always ends the run and counts what was kept`() {
        assertEquals(KeepShootingStep.Done(0), keepShootingStep(success = false, keepShooting = true, takenSoFar = 0))
        assertEquals(KeepShootingStep.Done(4), keepShootingStep(success = false, keepShooting = true, takenSoFar = 4))
        assertEquals(KeepShootingStep.Done(0), keepShootingStep(success = false, keepShooting = false, takenSoFar = 0))
    }

    @Test
    fun `with the setting off one photo is one photo`() {
        assertEquals(KeepShootingStep.Done(1), keepShootingStep(success = true, keepShooting = false, takenSoFar = 0))
    }

    @Test
    fun `only a run of several photos earns a sentence`() {
        assertNull(keepShootingSentence(0))
        assertNull(keepShootingSentence(1))
        assertEquals("3 photos added.", keepShootingSentence(3))
    }

    @Test
    fun `every attachments camera in the forms goes through the keep-shooting camera`() {
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        // The grid-measurement capture is the one single-shot camera left: a record has one grid image.
        val singleShot = Regex("""ActivityResultContracts\.TakePicture\(\)""").findAll(source).count()
        assertEquals("only the two grid-measurement cameras stay single-shot", 2, singleShot)
        assertTrue(source.contains("rememberKeepShootingCamera("))
        assertEquals(3, Regex("""KeepShootingToggle\(\)""").findAll(source).count())
    }
}
