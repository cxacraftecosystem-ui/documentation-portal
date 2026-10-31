package com.fieldrepository.app.transcript.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.fieldrepository.app.transcript.data.QuoteCard
import java.io.ByteArrayOutputStream

/**
 * THE QUOTE CARD AS AN IMAGE (DESIGN §10.5): the original, the English, the artisan's name, the
 * craft and the date, on the brand's deep purple, 1080 px wide for reports and the Ministry. The
 * same layout [QuoteCardFace] draws on screen; painted with the platform's text layout so every
 * Indic script shapes as it does everywhere else on the phone.
 */
object QuoteCardImage {
    private const val WIDTH = 1080
    private const val PAD = 88f
    private val INK = 0xFFFFFFFF.toInt()
    private val INK_MUTED = 0xFFD9CCF2.toInt()
    private val ACCENT = 0xFFC4B1F5.toInt()
    private val GROUND = 0xFF2E1065.toInt()

    private fun layout(text: String, size: Float, color: Int, style: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }
        val width = (WIDTH - 2 * PAD).toInt()
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .build()
    }

    fun png(card: QuoteCard): ByteArray {
        val quote = layout("“${card.original}”", 56f, INK, Typeface.BOLD)
        val english = card.english.takeIf { it.isNotBlank() && it != card.original }?.let { layout(it, 40f, INK_MUTED, Typeface.ITALIC) }
        val credit = listOfNotNull(card.artisanName ?: card.speakerName.ifBlank { null }, card.craft, card.date.ifBlank { null }).joinToString(" · ")
        val attribution = layout(credit, 34f, ACCENT, Typeface.BOLD)
        val gap = 48f
        val height = (PAD * 2 + quote.height + (english?.let { gap + it.height } ?: 0) + gap + attribution.height).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), height.toFloat()), 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GROUND })
        var y = PAD
        fun draw(l: StaticLayout) {
            canvas.save(); canvas.translate(PAD, y); l.draw(canvas); canvas.restore(); y += l.height
        }
        draw(quote)
        english?.let { y += gap; draw(it) }
        y += gap
        draw(attribution)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
