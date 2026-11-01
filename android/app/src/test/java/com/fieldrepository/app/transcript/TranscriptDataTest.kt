package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.DataRecording
import com.fieldrepository.app.transcript.data.DataTranscriptFile
import com.fieldrepository.app.transcript.data.DataTranscriptFilters
import com.fieldrepository.app.transcript.data.TranscriptUiText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.0.6: the View data / Download data wire shapes and filters (transcript contract `dataBrowser`). */
class TranscriptDataTest {
    @Test fun `the filters are the contract's query names, blanks left out`() {
        assertTrue(DataTranscriptFilters().toQuery().isEmpty())
        assertFalse(DataTranscriptFilters(q = "  ").active)
        val q = DataTranscriptFilters(q = " indigo ", speaker = "Giriraj", language = "hi", status = "COMPLETED", since = "2026-06-01", hasEnglish = false, extra = mapOf("questionnaireId" to "q1", "sectionId" to "")).toQuery()
        assertEquals(mapOf("questionnaireId" to "q1", "q" to "indigo", "speaker" to "Giriraj", "language" to "hi", "status" to "COMPLETED", "since" to "2026-06-01", "hasEnglish" to "false"), q)
        val names = TranscriptContractTest.contract["dataBrowser"]!!.jsonObject["filters"]!!.jsonObject.keys
        assertTrue(names.containsAll(q.keys - "questionnaireId"))
    }

    @Test fun `a data recording decodes, withheld or full`() {
        val json = TranscriptContractTest.json
        val full = json.decodeFromString(
            DataRecording.serializer(),
            """{"mediaId":"m1","file":"a.m4a","mediaType":"AUDIO","withheld":null,"source":"TURNS","languages":[{"code":"hi","label":"Hindi"}],
               "speakers":[{"speakerKey":"speaker_0","label":"Nandini (researcher)","colorKey":"s0","role":"RESEARCHER"}],
               "turns":[{"index":0,"speakerKey":"speaker_0","speaker":"Nandini (researcher)","colorKey":"s0","startMs":4000,"endMs":7600,"start":"00:00:04","end":"00:00:07","language":"hi","text":"आप","english":"You","translationStatus":"TRANSLATED","edited":false}],
               "timed":true,"summary":["One."],"quotes":[{"speaker":"Giriraj","original":"मिट्टी","english":"Clay"}],"glossary":[{"term":"dabu","gloss":"mud resist","craft":"Block printing"}],"chips":{"questionnaire":{"id":"q1"}}}"""
        )
        assertEquals("00:00:04", full.turns.single().start)
        assertEquals("s0", full.speakers.single().colorKey)
        assertNull(full.costUsd)
        val refused = json.decodeFromString(DataRecording.serializer(), """{"mediaId":"m2","withheld":"CONSENT_REFUSED","turns":[]}""")
        assertEquals("CONSENT_REFUSED", refused.withheld)
        assertTrue(refused.turns.isEmpty())
    }

    @Test fun `a media row carries the server's preview`() {
        val m = com.fieldrepository.app.data.ApiClient.json.decodeFromString(
            com.fieldrepository.app.data.MediaFileDto.serializer(),
            """{"id":"m1","originalFilename":"a.m4a","mediaType":"AUDIO","transcriptPreview":{"text":"Giriraj: Clay.","lines":[{"speaker":"Giriraj","speakerKey":"speaker_1","colorKey":"s1","text":"Clay."}]}}"""
        )
        assertEquals("s1", m.transcriptPreview!!.lines.single().colorKey)
        val none = com.fieldrepository.app.data.ApiClient.json.decodeFromString(
            com.fieldrepository.app.data.MediaFileDto.serializer(), """{"id":"m2","originalFilename":"b.jpg","mediaType":"IMAGE","transcriptPreview":null}"""
        )
        assertNull(none.transcriptPreview)
    }

    @Test fun `a transcript file's chip names its format, track and times`() {
        assertEquals("Word", TranscriptUiText.fileLabel("docx", english = false, timestamps = true))
        assertEquals("Markdown, no times", TranscriptUiText.fileLabel("md", english = false, timestamps = false))
        assertEquals("SRT, English", TranscriptUiText.fileLabel("srt", english = true, timestamps = true))
        assertEquals(CopyTrack.ENGLISH, DataTranscriptFilters.trackOf(DataTranscriptFile(track = "english")))
        assertEquals(CopyTrack.BOTH, DataTranscriptFilters.trackOf(DataTranscriptFile(track = "nonsense")))
        assertTrue(TranscriptContractTest.contract["dataBrowser"]!!.jsonObject["tree"]!!.jsonPrimitive.content.contains("transcriptFiles"))
    }
}
