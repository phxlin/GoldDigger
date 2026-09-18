package com.golddigger.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes a photo the user took or picked for the "import a portfolio photo"
 * flow into an upright [Bitmap] sized sensibly for OCR: downsampled so
 * recognition stays fast and memory-safe, but not so far that small table
 * text turns to mush, and rotated to match its EXIF orientation. Camera
 * photos are very often stored "sideways" with only a rotation tag (the
 * pixels themselves aren't rotated) — left uncorrected, that would scramble
 * the row-by-position table reading in
 * [com.golddigger.app.domain.PortfolioOcrParser].
 */
@Singleton
class PortfolioPhotoDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun decode(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = runCatching { resolver.openInputStream(uri) }.getOrElse {
            Log.w(TAG, "openInputStream (bounds) failed for $uri", it)
            null
        }
        if (boundsStream == null) {
            Log.w(TAG, "no input stream for $uri")
            return null
        }
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "decodeStream (bounds) produced ${bounds.outWidth}x${bounds.outHeight} for $uri")
            return null
        }

        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= MAX_DIMENSION) sampleSize *= 2

        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
        }.getOrElse {
            Log.w(TAG, "decodeStream (full) failed for $uri", it)
            null
        }
        if (decoded == null) {
            Log.w(TAG, "decodeStream (full) returned null for $uri")
            return null
        }

        val rotation = readRotationDegrees(uri)
        return if (rotation == 0) decoded else rotate(decoded, rotation)
    }

    private fun readRotationDegrees(uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        const val TAG = "PortfolioPhotoDecoder"

        /** Longest side after downsampling — plenty of resolution for OCR on a dense table without ballooning memory/latency. */
        const val MAX_DIMENSION = 2000
    }
}
