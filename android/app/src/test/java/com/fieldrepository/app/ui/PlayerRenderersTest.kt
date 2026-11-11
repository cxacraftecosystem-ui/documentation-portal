package com.fieldrepository.app.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerRenderersTest {
    private fun bare(): DefaultRenderersFactory =
        DefaultRenderersFactory::class.java.getConstructor(Context::class.java).newInstance(null)

    private fun flag(factory: DefaultRenderersFactory, name: String): Any? =
        DefaultRenderersFactory::class.java.getDeclaredField(name).apply { isAccessible = true }.get(factory)

    @Test
    fun everyPlayerFallsBackToAnotherDecoderWhenOneFails() {
        val plain = bare()
        assertFalse(flag(plain, "enableDecoderFallback") as Boolean)
        val configured = PlayerRenderers.configure(plain)
        assertTrue(flag(configured, "enableDecoderFallback") as Boolean)
    }

    @Test
    fun noExtensionRendererIsPreferredBecauseNoneIsBundled() {
        val configured = PlayerRenderers.configure(bare())
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, flag(configured, "extensionRendererMode"))
    }
}
