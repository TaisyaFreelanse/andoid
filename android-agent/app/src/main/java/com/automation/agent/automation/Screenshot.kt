package com.automation.agent.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screenshot - Captures and processes screenshots
 * 
 * Operations:
 * - Capture full screen
 * - Capture specific view
 * - Capture WebView (visible and full page)
 * - Save to file
 * - Convert to various formats
 * - Compress and optimize
 */
class Screenshot(private val context: Context? = null) {

    companion object {
        private const val TAG = "Screenshot"
        private const val DEFAULT_QUALITY = 85
        private const val MAX_DIMENSION = 4096
    }

    // ==================== WebView Capture ====================

    /**
     * Capture screenshot of WebView (visible area)
     */
    fun captureWebView(webView: WebView): Bitmap? {
        return try {
            if (webView.width <= 0 || webView.height <= 0) {
                Log.w(TAG, "WebView has invalid dimensions")
                return null
            }
            
            val bitmap = Bitmap.createBitmap(
                webView.width,
                webView.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE) // Background
            webView.draw(canvas)
            
            Log.d(TAG, "WebView screenshot captured: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture WebView: ${e.message}")
            null
        }
    }

    /**
     * Capture full page screenshot of WebView
     */
    fun captureWebViewFullPage(webView: WebView): Bitmap? {
        return try {
            val contentHeight = webView.contentHeight
            val scale = webView.scale
            val fullHeight = (contentHeight * scale).toInt()
            
            if (fullHeight <= 0 || webView.width <= 0) {
                Log.w(TAG, "Invalid dimensions for full page capture")
                return captureWebView(webView)
            }
            
            // Limit max height to prevent OOM
            val cappedHeight = fullHeight.coerceAtMost(MAX_DIMENSION)
            
            val bitmap = Bitmap.createBitmap(
                webView.width,
                cappedHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            
            // Save and restore scroll position
            val originalScrollY = webView.scrollY
            webView.scrollTo(0, 0)
            webView.draw(canvas)
            webView.scrollTo(0, originalScrollY)
            
            Log.d(TAG, "Full page screenshot captured: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture full page: ${e.message}")
            captureWebView(webView)
        }
    }

    // ==================== View Capture ====================

    /**
     * Capture screenshot of any View
     */
    fun captureView(view: View): Bitmap? {
        return try {
            if (view.width <= 0 || view.height <= 0) {
                Log.w(TAG, "View has invalid dimensions")
                return null
            }
            
            val bitmap = Bitmap.createBitmap(
                view.width,
                view.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture view: ${e.message}")
            null
        }
    }

    /**
     * Capture screenshot using drawing cache (legacy method)
     */
    @Suppress("DEPRECATION")
    fun captureViewLegacy(view: View): Bitmap? {
        return try {
            view.isDrawingCacheEnabled = true
            view.buildDrawingCache()
            val bitmap = Bitmap.createBitmap(view.drawingCache)
            view.isDrawingCacheEnabled = false
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture view (legacy): ${e.message}")
            null
        }
    }

    /**
     * Capture specific region of view
     */
    fun captureRegion(view: View, region: Rect): Bitmap? {
        val fullBitmap = captureView(view) ?: return null
        
        return try {
            Bitmap.createBitmap(
                fullBitmap,
                region.left.coerceAtLeast(0),
                region.top.coerceAtLeast(0),
                region.width().coerceAtMost(fullBitmap.width - region.left),
                region.height().coerceAtMost(fullBitmap.height - region.top)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture region: ${e.message}")
            null
        }
    }

    // ==================== Format Conversion ====================

    /**
     * Convert bitmap to PNG byte array
     */
    fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * Convert bitmap to JPEG byte array
     */
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Convert bitmap to WebP byte array
     */
    fun bitmapToWebP(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): ByteArray {
        val stream = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
        }
        return stream.toByteArray()
    }

    /**
     * Convert bitmap to Base64 string
     */
    fun bitmapToBase64(bitmap: Bitmap, format: Format = Format.PNG, quality: Int = DEFAULT_QUALITY): String {
        val bytes = when (format) {
            Format.PNG -> bitmapToPng(bitmap)
            Format.JPEG -> bitmapToJpeg(bitmap, quality)
            Format.WEBP -> bitmapToWebP(bitmap, quality)
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Convert bitmap to data URL
     */
    fun bitmapToDataUrl(bitmap: Bitmap, format: Format = Format.PNG, quality: Int = DEFAULT_QUALITY): String {
        val base64 = bitmapToBase64(bitmap, format, quality)
        val mimeType = when (format) {
            Format.PNG -> "image/png"
            Format.JPEG -> "image/jpeg"
            Format.WEBP -> "image/webp"
        }
        return "data:$mimeType;base64,$base64"
    }

    // ==================== Image Processing ====================

    /**
     * Resize bitmap
     */
    fun resize(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }
        
        val ratio = minOf(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height
        )
        
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Compress bitmap to target size (in KB)
     */
    fun compressToSize(bitmap: Bitmap, targetSizeKb: Int, format: Format = Format.JPEG): ByteArray {
        var quality = 100
        var bytes: ByteArray
        
        do {
            bytes = when (format) {
                Format.PNG -> bitmapToPng(bitmap)
                Format.JPEG -> bitmapToJpeg(bitmap, quality)
                Format.WEBP -> bitmapToWebP(bitmap, quality)
            }
            quality -= 10
        } while (bytes.size > targetSizeKb * 1024 && quality > 10)
        
        return bytes
    }

    /**
     * Crop bitmap
     */
    fun crop(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap? {
        return try {
            Bitmap.createBitmap(
                bitmap,
                x.coerceAtLeast(0),
                y.coerceAtLeast(0),
                width.coerceAtMost(bitmap.width - x),
                height.coerceAtMost(bitmap.height - y)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop: ${e.message}")
            null
        }
    }

    // ==================== File Operations ====================

    /**
     * Save bitmap to file
     */
    fun saveToFile(
        bitmap: Bitmap,
        filename: String,
        format: Format = Format.PNG,
        quality: Int = DEFAULT_QUALITY
    ): File? {
        val ctx = context ?: return null
        
        return try {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: ctx.filesDir
            
            val extension = when (format) {
                Format.PNG -> "png"
                Format.JPEG -> "jpg"
                Format.WEBP -> "webp"
            }
            
            val file = File(dir, "$filename.$extension")
            FileOutputStream(file).use { out ->
                val compressFormat = when (format) {
                    Format.PNG -> Bitmap.CompressFormat.PNG
                    Format.JPEG -> Bitmap.CompressFormat.JPEG
                    Format.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                }
                bitmap.compress(compressFormat, quality, out)
            }
            
            Log.d(TAG, "Screenshot saved: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}")
            null
        }
    }

    /**
     * Save with timestamp filename
     */
    fun saveWithTimestamp(
        bitmap: Bitmap,
        prefix: String = "screenshot",
        format: Format = Format.PNG
    ): File? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return saveToFile(bitmap, "${prefix}_$timestamp", format)
    }

    /**
     * Delete screenshot file
     */
    fun deleteFile(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: ${e.message}")
            false
        }
    }

    // ==================== Utility ====================

    /**
     * Get bitmap info
     */
    fun getBitmapInfo(bitmap: Bitmap): BitmapInfo {
        return BitmapInfo(
            width = bitmap.width,
            height = bitmap.height,
            config = bitmap.config?.name ?: "UNKNOWN",
            byteCount = bitmap.byteCount,
            density = bitmap.density
        )
    }

    /**
     * Check if bitmap is valid
     */
    fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
    }

    /**
     * Recycle bitmap safely
     */
    fun recycleSafely(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    // ==================== Data Classes ====================

    enum class Format {
        PNG,
        JPEG,
        WEBP
    }

    data class BitmapInfo(
        val width: Int,
        val height: Int,
        val config: String,
        val byteCount: Int,
        val density: Int
    )

    data class ScreenshotResult(
        val bitmap: Bitmap?,
        val bytes: ByteArray?,
        val file: File?,
        val format: Format,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ScreenshotResult
            return timestamp == other.timestamp
        }

        override fun hashCode(): Int {
            return timestamp.hashCode()
        }
    }
}
