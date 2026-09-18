package com.golddigger.app.data.ocr

import android.graphics.Bitmap
import com.golddigger.app.domain.model.OcrToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around ML Kit's on-device text recognizer for the "import a
 * portfolio photo" flow — the only Android/ML-Kit-bound piece of it.
 * Recognition runs entirely on-device (the Latin-script model ships in the
 * app; no network call, no API key, nothing leaves the phone), and everything
 * downstream — turning recognized words into holding candidates — is pure
 * and lives in [com.golddigger.app.domain.PortfolioOcrParser].
 */
@Singleton
class TextRecognizerService @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Every recognized word, flattened out of ML Kit's block/line grouping —
     * [com.golddigger.app.domain.PortfolioOcrParser] re-derives table rows
     * from word geometry itself rather than trusting the recognizer's
     * reading order, which can interleave columns on a tabular screenshot.
     */
    suspend fun recognizeWords(bitmap: Bitmap): List<OcrToken> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val tokens = text.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.mapNotNull { element ->
                                val box = element.boundingBox ?: return@mapNotNull null
                                OcrToken(
                                    text = element.text,
                                    left = box.left.toFloat(),
                                    top = box.top.toFloat(),
                                    right = box.right.toFloat(),
                                    bottom = box.bottom.toFloat(),
                                )
                            }
                        }
                    }
                    cont.resume(tokens)
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
