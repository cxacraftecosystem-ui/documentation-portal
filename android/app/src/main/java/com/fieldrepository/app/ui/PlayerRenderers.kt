package com.fieldrepository.app.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
object PlayerRenderers {
    fun configure(factory: DefaultRenderersFactory): DefaultRenderersFactory =
        factory.setEnableDecoderFallback(true)

    fun factory(context: Context): DefaultRenderersFactory = configure(DefaultRenderersFactory(context))

    fun builder(context: Context): ExoPlayer.Builder = ExoPlayer.Builder(context, factory(context))
}
