package com.automation.agent.automation

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebView
import java.io.ByteArrayOutputStream

/**
 * Screenshot - Captures screenshots
 * 
 * Operations:
 * - Capture full screen
 * - Capture specific view
 * - Capture element by selector
 */
class Screenshot {

    /**
     * Capture screenshot of WebView
     */
    fun captureWebView(webView: WebView): Bitmap? {
        // TODO: Capture WebView screenshot
        return null
    }

    /**
     * Capture screenshot of View
     */
    fun captureView(view: View): Bitmap? {
        view.isDrawingCacheEnabled = true
        view.buildDrawingCache()
        val bitmap = Bitmap.createBitmap(view.drawingCache)
        view.isDrawingCacheEnabled = false
        return bitmap
    }

    /**
     * Convert bitmap to byte array (PNG)
     */
    fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * Convert bitmap to byte array (JPEG)
     */
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

