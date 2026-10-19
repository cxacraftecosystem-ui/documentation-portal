package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.QArtisanRecord
import com.fieldrepository.app.ui.questionnaire.ArtisanTimeline
import com.fieldrepository.app.ui.questionnaire.TimelineRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DESIGN §9 test 58: the artisan record fixture renders these rows, in this order. The fixture is
 * DESIGN §4.6's own example; the web's parity spec reads the same expected rows, and once
 * `shared/` carries a record fixture both sides switch to it.
 */
class ArtisanRecordParityTest {
    private val fixture = """
    {"artisanId":"a1","instruments":[{"questionnaireId":"q2","title":"2nd Craft Toolkit",
      "summary":{"sectionsCovered":14,"sectionsTotal":24,"sessions":3,"repeats":2,"open":["B","P","V","W"]},
      "sessions":[
        {"session":{"id":"s21","sessionDate":"2026-06-21","participantCount":1,"place":"Kolkata",
                    "participants":[{"artisanId":"a1","name":"Giriraj Das"}],"researchers":[{"userId":"u3","name":"Nandini"}]},
         "recordings":[
           {"id":"r3","capturedAt":"2026-06-21T10:39:00Z","durationMs":751000,"targets":[{"targetKey":"S:T","displayCode":null,"label":"Section T"}],
            "subjects":[{"artisanId":"a1","role":"ANSWERED","position":1}]},
           {"id":"r4","capturedAt":"2026-06-21T10:54:00Z","durationMs":590000,"targets":[{"targetKey":"S:U","label":"Section U"}],
            "subjects":[{"artisanId":"a1","role":"ANSWERED","position":1}]},
           {"id":"r5","capturedAt":"2026-06-21T12:00:00Z","estimated":true,"durationMs":42000,"targets":[{"targetKey":"Q:f7","displayCode":"F7"}],
            "subjects":[{"artisanId":"a1","role":"ANSWERED","position":1}]}],
         "uncertain":[{"id":"r5","capturedAt":"2026-06-21T12:00:00Z","estimated":true,"durationMs":42000,"targets":[{"targetKey":"Q:f7","displayCode":"F7"}],
            "subjects":[{"artisanId":"a1","role":"ANSWERED","position":1}]}]},
        {"session":{"id":"s24","sessionDate":"2026-06-24","participantCount":5,"place":"Kolkata",
                    "participants":[{"artisanId":"a1","name":"Giriraj Das"},{"artisanId":"a2","name":"Meena"},{"artisanId":"a3","name":"Ravi"},{"artisanId":"a4","name":"Asha"},{"artisanId":"a5","name":"Bimal"}],
                    "researchers":[{"userId":"u4","name":"Ankur"}]},
         "recordings":[
           {"id":"r1","capturedAt":"2026-06-24T05:12:00Z","durationMs":1524000,"rosterCount":5,"targets":[{"targetKey":"S:A","label":"Section A"}],
            "subjects":[{"artisanId":"a1","role":"ANSWERED","position":2,"repeatReason":"NEW_SETTING"}]},
           {"id":"r2","capturedAt":"2026-06-24T05:40:00Z","durationMs":482000,"rosterCount":5,"targets":[{"targetKey":"S:N","label":"Section N"}],
            "subjects":[{"artisanId":"a1","role":"PRESENT"}]}],
         "uncertain":[]}],
      "document":[],"retired":[]}]}
    """.trimIndent()

    @Test
    fun `the timeline rows match the design's example`() {
        val record = ApiClient.json.decodeFromString(QArtisanRecord.serializer(), fixture)
        val rows = ArtisanTimeline.rows(record, "q2", "a1").map {
            when (it) {
                is TimelineRow.SessionHead -> it.text
                is TimelineRow.Take -> listOfNotNull(it.time, it.text).joinToString("  ") + if (it.badges.isEmpty()) "" else " [" + it.badges.joinToString() + "]"
                TimelineRow.Uncertain -> "Order uncertain"
            }
        }
        assertEquals(
            listOf(
                "24 Jun · Group of 5 with Meena, Ravi +2 · Ankur · Kolkata",
                "10:42  Section A · 25:24 · Take 2 · New setting",
                "11:10  Section N · 8:02 · Present, didn't answer",
                "21 Jun · Solo · Nandini · Kolkata",
                "16:09  Section T · 12:31 · Take 1",
                "16:24  Section U · 9:50 · Take 1",
                "Order uncertain",
                "F7 · 0:42 · Take 1 [Estimated date]"
            ),
            rows
        )
    }
}
