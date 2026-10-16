package com.fieldrepository.app.ui

import com.fieldrepository.app.data.PasswordMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add a person": who may be created by whom, what is sent for each sign-in choice, and what is said. */
class AddPersonCopyTest {
    private val wire = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `an admin creates strictly below their own rank and never another admin`() {
        val roles = creatableRoles("ADMIN", actorIsMaster = false)
        assertFalse("ADMIN" in roles)
        assertFalse("MASTER_ADMIN" in roles)
        assertTrue("PROFESSOR" in roles)
        assertEquals("PROFESSOR", roles.first())
        assertEquals("CROWDSOURCE_VOLUNTEER", roles.last())
    }

    @Test
    fun `only the master admin is offered admin, and nobody is offered master admin`() {
        val roles = creatableRoles("MASTER_ADMIN", actorIsMaster = true)
        assertEquals("ADMIN", roles.first())
        assertFalse("MASTER_ADMIN" in roles)
    }

    @Test
    fun `a professor is offered only the tiers below professor`() {
        val roles = creatableRoles("PROFESSOR", actorIsMaster = false)
        assertFalse("PROFESSOR" in roles)
        assertTrue(roles.all { FieldPermissions.rank(it) < FieldPermissions.rank("PROFESSOR") })
        assertEquals(emptyList<String>(), creatableRoles("CROWDSOURCE_VOLUNTEER", actorIsMaster = false))
    }

    @Test
    fun `the form opens on researcher where it may`() {
        assertEquals("RESEARCHER", defaultNewRole(creatableRoles("ADMIN", false)))
        assertEquals("CROWDSOURCE_VOLUNTEER", defaultNewRole(listOf("CROWDSOURCE_VOLUNTEER")))
        assertNull(defaultNewRole(emptyList()))
    }

    @Test
    fun `only a set password is checked for length`() {
        assertEquals("Enter their full name.", addPersonProblem(" ", "a@b.org", "RESEARCHER", PasswordMode.SET, "long enough"))
        assertNotNull(addPersonProblem("Asha", "asha", "RESEARCHER", PasswordMode.SET, "long enough"))
        assertEquals("Choose a role.", addPersonProblem("Asha", "a@b.org", null, PasswordMode.NONE, ""))
        assertEquals(passwordRuleLine(), addPersonProblem("Asha", "a@b.org", "RESEARCHER", PasswordMode.SET, "short"))
        assertNull(addPersonProblem("Asha", "a@b.org", "RESEARCHER", PasswordMode.GENERATE, ""))
        assertNull(addPersonProblem("Asha", "a@b.org", "RESEARCHER", PasswordMode.NONE, ""))
    }

    @Test
    fun `each sign-in choice sends only its own keys`() {
        val set = wire.encodeToString(addPersonBody("Asha ", " A@B.org", "RESEARCHER", PasswordMode.SET, "long enough", false, true, true))
        assertTrue(set.contains("\"password\":\"long enough\""))
        assertTrue(set.contains("\"mustChangePassword\":false"))
        assertTrue(set.contains("\"email\":\"a@b.org\""))
        assertFalse(set.contains("sendPasswordLink"))

        val generate = wire.encodeToString(addPersonBody("Asha", "a@b.org", "RESEARCHER", PasswordMode.GENERATE, "typed", true, true, true))
        assertFalse(generate.contains("\"password\""))
        assertFalse(generate.contains("mustChangePassword"))
        assertTrue(generate.contains("\"passwordMode\":\"GENERATE\""))

        val linked = wire.encodeToString(addPersonBody("Asha", "a@b.org", "RESEARCHER", PasswordMode.NONE, "", true, true, true))
        assertTrue(linked.contains("\"sendPasswordLink\":true"))
        val noMail = wire.encodeToString(addPersonBody("Asha", "a@b.org", "RESEARCHER", PasswordMode.NONE, "", true, true, false))
        assertFalse(noMail.contains("sendPasswordLink"))
    }

    @Test
    fun `the done line says what to do next for each choice`() {
        assertTrue(addPersonDoneLine("Asha", PasswordMode.GENERATE, false, null).contains("temporary password"))
        assertTrue(addPersonDoneLine("Asha", PasswordMode.NONE, true, null).contains("on its way"))
        assertTrue(addPersonDoneLine("Asha", PasswordMode.NONE, false, "Mail could not be sent.").contains("Mail could not be sent."))
        assertTrue(addPersonDoneLine("Asha", PasswordMode.NONE, false, null).contains("Google"))
    }
}
