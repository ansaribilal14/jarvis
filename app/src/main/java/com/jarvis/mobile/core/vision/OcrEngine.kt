package com.jarvis.mobile.core.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Local OCR fallback (spec: LOCAL OCR) - ML Kit on-device text recognition.
 * Used only when the accessibility tree is empty or too sparse (e.g. some
 * games/PDFs/webviews). Images never leave the device.
 */
object OcrEngine {

    private val recognizer by lazy {
        runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull()
    }

    fun available(): Boolean = recognizer != null

    suspend fun extractText(bitmap: Bitmap): String? {
        val rec = recognizer ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                rec.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text -> cont.resume(text.text.ifBlank { null }) }
                    .addOnFailureListener { e ->
                        Logx.w("ocr", "OCR failed: ${e.message}")
                        cont.resume(null)
                    }
            }.onFailure { cont.resume(null) }
        }
    }
}
