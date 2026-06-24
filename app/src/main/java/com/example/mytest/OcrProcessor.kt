package com.example.mytest

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OcrProcessor {
    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    fun recognize(bitmap: Bitmap): Task<Text> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image)
    }
}
