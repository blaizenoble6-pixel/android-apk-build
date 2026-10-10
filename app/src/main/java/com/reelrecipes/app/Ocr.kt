package com.reelrecipes.app

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Reads text off screenshots and post photos on the phone itself (Google ML Kit, offline, free). */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(jpeg: ByteArray): String {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return ""
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    // Keep top-to-bottom order of blocks so lists stay in sequence.
                    val lines = text.textBlocks
                        .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                        .flatMap { b -> b.lines.map { it.text } }
                    if (cont.isActive) cont.resume(lines.joinToString("\n"))
                }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        }
    }

    suspend fun readAll(images: List<ByteArray>): String =
        images.map { read(it) }.filter { it.isNotBlank() }.joinToString("\n\n")
}
