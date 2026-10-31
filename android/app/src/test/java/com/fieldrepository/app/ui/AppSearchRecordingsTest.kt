package com.fieldrepository.app.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.data.RecordingSearch
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app search's Recordings group (owner, 31 Oct 2026): a take found by what its chips say reads
 * as what it answers over its session's title, carries its chips into the panel, sits in its own
 * group, and a tap opens the recordings list searched for the same words on the take's form.
 */
class AppSearchRecordingsTest {
    private val take = QuestionnairePortal.json.decodeFromString(
        QRecording.serializer(),
        """{"id":"r1","chips":{"session":{"id":"s1","title":"Interview_2026-10-22_Bagru"},"questionnaire":{"id":"f1","title":"2nd Craft Toolkit"},"targets":[{"kind":"SECTION","sectionId":"secA","sectionCode":"A","sectionTitle":"Materials"}],"artisans":[{"id":"a1","name":"Giriraj"}],"sessionDate":"2026-10-22"}}"""
    )

    @QFeature("E5")
    @Test fun `a take found by the search is a Recordings row with its chips, and opens the list filtered`() {
        var opened: QuestionnaireFilters? = null
        val items = recordingSearchItems(RecordingSearch.hits(listOf(take), "giriraj")) { opened = it }
        val item = items.single()
        assertEquals(AppSearchGroup.RECORDINGS, item.group)
        assertEquals("A · Materials", item.title)
        assertEquals("Interview_2026-10-22_Bagru", item.subtitle)
        assertEquals("Giriraj", item.chips?.artisans?.single()?.name)
        item.onOpen()
        assertEquals("q=giriraj&questionnaireId=f1", opened?.toQuery())
        val sections = groupAppSearchResults(items, "giriraj")
        assertEquals(listOf(AppSearchGroup.RECORDINGS), sections.map { it.group })
        assertTrue(AppSearchGroup.entries.indexOf(AppSearchGroup.RECORDINGS) > AppSearchGroup.entries.indexOf(AppSearchGroup.MEDIA))
    }

    @QFeature("E1")
    @Test fun `a media row carries its take's chips`() {
        val media = QuestionnairePortal.json.decodeFromString(
            com.fieldrepository.app.data.MediaFileDto.serializer(),
            """{"id":"m1","originalFilename":"clip.m4a","mediaType":"AUDIO","questionnaireChips":{"questionnaire":{"id":"f1","title":"2nd Craft Toolkit"},"targets":[],"durationMs":42000}}"""
        )
        assertEquals(listOf("Whole session", "2nd Craft Toolkit", "0:42"), com.fieldrepository.app.questionnaire.data.ChipRules.display(media.questionnaireChips).map { it.label })
    }
}
