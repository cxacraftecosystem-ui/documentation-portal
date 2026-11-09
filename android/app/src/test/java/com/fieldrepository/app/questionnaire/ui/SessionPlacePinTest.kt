package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.data.PlaceHit
import com.fieldrepository.app.questionnaire.data.QSessionLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPlacePinTest {
    private val bagru = PlaceHit(id = "p1", name = "Bagru", context = "Jaipur, Rajasthan, India", lon = 75.5466, lat = 26.8105)

    @Test fun `a searched place fills the words and pins the point`() {
        assertEquals("Bagru, Jaipur, Rajasthan", placeFromHit(bagru.name, bagru.context))
        assertEquals("Bagru", placeFromHit("Bagru", ""))
        assertEquals("Jaipur, Rajasthan", placeFromHit("Jaipur", "Jaipur, Rajasthan, India"))
        val pinned = locationFromHit(bagru)
        assertEquals(26.8105, pinned.latitude, 0.0)
        assertEquals(75.5466, pinned.longitude, 0.0)
        assertEquals("Bagru, Jaipur, Rajasthan, India", pinned.placeName)
        assertNull(pinned.accuracy)
    }

    @Test fun `a pin from the map keeps the typed words`() {
        val where = pinnedAt(SessionWhere("Sanganer"), 26.82, 75.78)
        assertEquals("Sanganer", where.place)
        assertEquals(QSessionLocation(26.82, 75.78, placeName = "Sanganer"), where.location)
        assertNull(pinnedAt(SessionWhere(""), 1.0, 2.0).location!!.placeName)
    }

    @Test fun `the pinned line reads five decimals`() {
        assertEquals("Pinned at 26.81050, 75.54660", pinnedLine(QSessionLocation(26.8105, 75.5466)))
    }

    @Test fun `editing sends a moved pin, a removed pin as a clear, and nothing when unchanged`() {
        val pin = QSessionLocation(26.8105, 75.5466, placeName = "Bagru")
        val before = SessionEdit.of("2026-11-09", "FIRST_SITTING", "Bagru", null, null, null, pin)
        val same = before.patch(3, before)
        assertNull(same.location)
        assertNull(same.clearLocation)
        val moved = before.copy(location = pin.copy(latitude = 26.9)).patch(3, before)
        assertEquals(26.9, moved.location!!.latitude, 0.0)
        assertNull(moved.clearLocation)
        val removed = before.copy(location = null).patch(3, before)
        assertNull(removed.location)
        assertEquals(true, removed.clearLocation)
        val fresh = SessionEdit.of("2026-11-09", null, "", null, null, null).copy(location = pin)
        assertEquals(pin, fresh.patch(1, SessionEdit.of("2026-11-09", null, "", null, null, null)).location)
    }
}
