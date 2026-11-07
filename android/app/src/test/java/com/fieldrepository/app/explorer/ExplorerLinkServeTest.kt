package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.ui.ExplorerIdentity
import com.fieldrepository.app.explorer.ui.ExplorerLinkRequest
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import com.fieldrepository.app.explorer.ui.attemptOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import java.io.IOException

class ExplorerLinkServeTest {
    @After fun clear() {
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
    }

    @Test fun `a link stays pending until its work is done, then it is used up`() = runBlocking {
        val link = ExplorerLinkRequest(viewId = null, path = "by-workshop/w1")
        ExplorerViewLinks.request(link)
        val gate = CompletableDeferred<Unit>()
        val serving = launch(start = CoroutineStart.UNDISPATCHED) {
            ExplorerViewLinks.serve(link, onFailure = { throw AssertionError(it) }) { gate.await() }
        }
        assertSame(link, ExplorerViewLinks.requests.value)
        gate.complete(Unit)
        serving.join()
        assertNull(ExplorerViewLinks.requests.value)
    }

    @Test fun `a link cancelled because the user left Data is used up and the cancellation goes on up`() = runBlocking {
        val link = ExplorerLinkRequest(viewId = null, path = null, go = listOf("media", "m1"))
        ExplorerViewLinks.request(link)
        var failed = false
        val serving = launch(start = CoroutineStart.UNDISPATCHED) {
            ExplorerViewLinks.serve(link, onFailure = { failed = true }) { awaitCancellation() }
        }
        serving.cancelAndJoin()
        assertTrue(serving.isCancelled)
        assertFalse(failed)
        assertNull(ExplorerViewLinks.requests.value)
    }

    @Test fun `a cancelled link never clears a newer one`() = runBlocking {
        val old = ExplorerLinkRequest(viewId = "v1", path = null, at = 1)
        val newer = ExplorerLinkRequest(viewId = "v2", path = null, at = 2)
        ExplorerViewLinks.request(old)
        val serving = launch(start = CoroutineStart.UNDISPATCHED) {
            ExplorerViewLinks.serve(old, onFailure = {}) { awaitCancellation() }
        }
        ExplorerViewLinks.request(newer)
        serving.cancelAndJoin()
        assertSame(newer, ExplorerViewLinks.requests.value)
    }

    @Test fun `a saved view that is gone reads differently from a network or server failure`() {
        assertEquals(ExplorerUiText.viewMissing, ExplorerViewLinks.viewFailure(FakeExplorerApi.httpError(404)))
        assertEquals(ExplorerUiText.viewMissing, ExplorerViewLinks.viewFailure(FakeExplorerApi.httpError(403)))
        assertEquals(ExplorerUiText.viewOffline, ExplorerViewLinks.viewFailure(IOException("Unable to resolve host")))
        assertEquals(ExplorerUiText.viewFailed, ExplorerViewLinks.viewFailure(FakeExplorerApi.httpError(500)))
        assertEquals(ExplorerUiText.viewFailed, ExplorerViewLinks.viewFailure(FakeExplorerApi.httpError(503)))
        assertNotEquals(ExplorerUiText.viewMissing, ExplorerUiText.viewOffline)
        assertNotEquals(ExplorerUiText.viewMissing, ExplorerUiText.viewFailed)
        assertNotEquals(ExplorerUiText.viewOffline, ExplorerUiText.viewFailed)
    }

    @Test fun `a link that fails says so once and is used up`() = runBlocking {
        val link = ExplorerLinkRequest(viewId = "v1", path = null)
        ExplorerViewLinks.request(link)
        val failures = mutableListOf<Throwable>()
        ExplorerViewLinks.serve(link, onFailure = { failures += it }) { throw IOException("offline") }
        assertEquals(1, failures.size)
        assertNull(ExplorerViewLinks.requests.value)
    }

    @Test fun `a newer link that arrives while one is handled is kept`() = runBlocking {
        val first = ExplorerLinkRequest(viewId = "v1", path = null, at = 1)
        val second = ExplorerLinkRequest(viewId = "v2", path = null, at = 2)
        ExplorerViewLinks.request(first)
        ExplorerViewLinks.serve(first, onFailure = {}) { ExplorerViewLinks.request(second) }
        assertSame(second, ExplorerViewLinks.requests.value)
    }

    @Test fun `a link's media type is the server's, then the name's, never a guessed video`() {
        assertEquals("IMAGE", ExplorerViewLinks.mediaType("IMAGE", "m42"))
        assertEquals("IMAGE", ExplorerViewLinks.mediaType("image", "clip.mp4"))
        assertEquals("AUDIO", ExplorerViewLinks.mediaType(null, "Interview.m4a"))
        assertEquals("PDF", ExplorerViewLinks.mediaType("OTHER", "consent.pdf"))
        assertEquals("OTHER", ExplorerViewLinks.mediaType("OTHER", "m42"))
        assertNull(ExplorerViewLinks.mediaType(null, "m42"))
        assertNotEquals("VIDEO", ExplorerViewLinks.mediaType(null, "photo-without-extension"))
    }

    @Test fun `attempting a call keeps cancellation and turns failures into nothing`() = runBlocking {
        assertEquals(3, attemptOrNull { 3 })
        assertNull(attemptOrNull<Int> { throw IOException("down") })
        val inner = async(start = CoroutineStart.UNDISPATCHED) { attemptOrNull { awaitCancellation() } }
        inner.cancel()
        val thrown = runCatching { inner.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        yield()
    }

    @Test fun `a Retrofit service is never equal to itself, its identity is`() {
        val api = Retrofit.Builder().baseUrl("https://portal.example.org/api/").build().create(ExplorerApi::class.java)
        val same: Any = api
        assertFalse("Retrofit answers equals on its handler, so a service cannot be a remember key", api.equals(same))
        assertEquals(ExplorerIdentity(api), ExplorerIdentity(api))
        assertEquals(ExplorerIdentity(api).hashCode(), ExplorerIdentity(api).hashCode())
        val other = Retrofit.Builder().baseUrl("https://portal.example.org/api/").build().create(ExplorerApi::class.java)
        assertNotEquals(ExplorerIdentity(api), ExplorerIdentity(other))
        assertEquals(ExplorerIdentity(null), ExplorerIdentity(null))
    }
}
