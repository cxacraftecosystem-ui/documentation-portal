package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * THE CRAFT LIST EVERY PICKER READS (REQUIREMENTS_2026-10-20 §2.3-§2.4), held against a mock of the
 * frozen `GET /crafts/catalogue` answer built from the master list — the same fixture the web's
 * `frontend/e2e/craft-catalogue-unit.spec.ts` reads, so the two clients rank and group alike.
 */
class CraftCatalogueTest {
    private lateinit var dir: File
    private lateinit var target: File
    private val body: String = javaClass.getResourceAsStream("/craft-catalogue.json")!!.bufferedReader().readText()
    private val catalogue: CraftCatalogue = CraftCatalogueCache.parse(body)!!

    @Before
    fun setUp() {
        CraftCatalogueCache.forget()
        dir = Files.createTempDirectory("craft-catalogue").toFile()
        target = File(dir, CraftCatalogueCache.FILE_NAME)
    }

    @After
    fun tearDown() {
        CraftCatalogueCache.forget()
        dir.deleteRecursively()
    }

    @Test
    fun `the mocked catalogue is the master list`() {
        assertEquals(9, catalogue.categories.size)
        assertEquals(36, catalogue.categories.sumOf { it.subCategories.size })
        assertEquals(455, catalogue.crafts.size)
        assertEquals(209, catalogue.crafts.count { it.isGi })
    }

    @Test
    fun `searching ajrakh finds Kutch Ajrakh under its heading with a GI badge`() {
        val found = CraftCatalogueCache.search(catalogue, "Ajrakh")
        val ajrakh = found.firstOrNull { it.name == "Kutch Ajrakh" }
        assertNotNull(ajrakh)
        assertTrue(ajrakh!!.isGi)
        val group = CraftCatalogueCache.group(catalogue, found).first { g -> g.crafts.any { it.name == "Kutch Ajrakh" } }
        assertEquals("Textiles based Craft Category › Hand Block Printing", group.heading)
    }

    @Test
    fun `a category or sub-category name finds the crafts filed under it`() {
        val heading = "Textiles based Craft Category › Hand Block Printing"
        val bySub = CraftCatalogueCache.search(catalogue, "Hand Block Printing")
        assertTrue(bySub.size > 1)
        assertTrue(bySub.any { CraftCatalogueCache.path(catalogue, it) == heading })
        assertTrue(CraftCatalogueCache.group(catalogue, bySub).any { it.heading == heading })
        val byCategory = CraftCatalogueCache.search(catalogue, "Textiles based Craft Category")
        assertTrue(byCategory.size > 10)
        assertTrue(byCategory.all { CraftCatalogueCache.path(catalogue, it).startsWith("Textiles based Craft Category › ") })
    }

    @Test
    fun `the fold and the ranking are the web's`() {
        assertEquals("kutch ajrakh and co", CraftCatalogueCache.fold("  Kútch–AJRAKH & Co "))
        assertEquals("Tharu Embroidery", CraftCatalogueCache.search(catalogue, "Tharu Embroidery").first().name)
        assertTrue(CraftCatalogueCache.search(catalogue, "ajrakh gujarat").any { it.name == "Kutch Ajrakh" })
        assertTrue(CraftCatalogueCache.search(catalogue, "zzzz-not-a-craft").isEmpty())
        assertEquals("No craft matches “zzzz”.", CraftCatalogueCache.noMatch("zzzz"))
        val all = CraftCatalogueCache.search(catalogue, "")
        assertEquals(455, all.size)
        assertEquals(36, CraftCatalogueCache.group(catalogue, all).size)
    }

    @Test
    fun `free text resolves only to exactly one craft`() {
        assertEquals("Kutch-Ajrakh", CraftCatalogueCache.findByText(catalogue, "kutch ajrakh")?.slug)
        assertNull(CraftCatalogueCache.findByText(catalogue, ""))
        assertNull(CraftCatalogueCache.findByText(catalogue, "Portrait, Jammu, Jammu & Kashmir"))
    }

    @Test
    fun `in airplane mode the cached catalogue still finds Ajrakh`() = runBlocking {
        // Online once: the list lands in the file with its tag.
        val fresh = CraftCatalogueCache.refresh(target) { CraftCatalogueCache.Fetch.Fresh(body, "\"v1\"") }
        assertEquals(455, fresh?.crafts?.size)
        assertTrue(target.exists())
        // A cold start with no signal: memory is empty, the network throws, the file answers.
        CraftCatalogueCache.forget()
        var sentTag: String? = null
        val offline = CraftCatalogueCache.refresh(target) { etag ->
            sentTag = etag
            throw IOException("airplane mode")
        }
        assertEquals("\"v1\"", sentTag)
        assertFalse(CraftCatalogueCache.lastRefreshReachedServer)
        assertTrue(CraftCatalogueCache.search(offline!!, "Ajrakh").any { it.name == "Kutch Ajrakh" })
    }

    @Test
    fun `a 304 keeps the stored list and a first run offline has none`() = runBlocking {
        CraftCatalogueCache.refresh(target) { CraftCatalogueCache.Fetch.Fresh(body, "\"v1\"") }
        val same = CraftCatalogueCache.refresh(target) { CraftCatalogueCache.Fetch.NotModified }
        assertEquals(455, same?.crafts?.size)
        assertTrue(CraftCatalogueCache.lastRefreshReachedServer)

        CraftCatalogueCache.forget()
        target.delete()
        val refused = runCatching { CraftCatalogueCache.refresh(target) { throw IOException("offline") } }
        assertTrue(refused.isFailure)
        assertEquals("Connect once to load the craft list.", CraftCatalogueCache.OFFLINE_FIRST_RUN)
    }

    @Test
    fun `the fixture is the web's, byte for byte`() {
        val web = File("../../frontend/e2e/fixtures/craft-catalogue.json")
        if (!web.exists()) return
        assertEquals(web.readText().replace("\r\n", "\n"), body.replace("\r\n", "\n"))
    }

    @Test
    fun `the states offered are the 36 states and union territories plus All India`() {
        assertEquals(37, CraftCatalogueCache.STATE_OPTIONS.size)
        assertEquals("All India", CraftCatalogueCache.STATE_OPTIONS.last())
        val inFixture = catalogue.crafts.flatMap { it.stateOfOrigin + it.stateOfPractice }.toSet()
        assertTrue(CraftCatalogueCache.STATE_OPTIONS.containsAll(inFixture))
    }
}
