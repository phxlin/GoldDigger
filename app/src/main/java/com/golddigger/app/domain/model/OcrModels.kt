package com.golddigger.app.domain.model

/**
 * One word recognized from a photo, with its bounding box in the source
 * image's pixel coordinates. Deliberately free of `android.graphics.Rect` /
 * ML Kit's `Text.Element` so [com.golddigger.app.domain.PortfolioOcrParser]
 * is unit-testable on the JVM without a device or Play Services.
 */
data class OcrToken(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

/**
 * HIGH: both shares and price were found and confidently attributed to a
 * column (via a header row, or a position inferred from the rest of the
 * table). LOW: the row parsed but at least one field is missing or guessed —
 * shown to the user for review rather than auto-checked.
 */
enum class ParseConfidence { HIGH, LOW }

/**
 * One holding candidate lifted from a photo of a portfolio. Never written to
 * Room directly — the import screen always shows these for the user to
 * confirm or correct first, since OCR on an arbitrary screenshot can and
 * will misread things.
 */
data class ParsedHolding(
    val ticker: String,
    val shares: Double?,
    val avgPrice: Double?,
    val confidence: ParseConfidence,
    /** The raw recognized text of the row this came from, for the user to sanity-check. */
    val sourceText: String,
)
