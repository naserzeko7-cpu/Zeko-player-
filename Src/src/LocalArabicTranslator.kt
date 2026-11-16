package com.zeko.player

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device English-to-Arabic translation.
 *
 * The language model is downloaded once by ML Kit. After download,
 * translation runs locally and subtitle text is not sent to a server.
 */
class LocalArabicTranslator(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ARABIC)
            .build()
    )

    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun prepare() {
        if (closed.get()) return
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (!closed.get()) {
                    ready.set(true)
                    onReady()
                }
            }
            .addOnFailureListener { error ->
                if (!closed.get()) {
                    onError(error.localizedMessage ?: "تعذر تنزيل نموذج الترجمة")
                }
            }
    }

    fun translate(text: String): Task<String> {
        require(text.isNotBlank()) { "Text must not be blank" }
        check(ready.get() && !closed.get()) { "Translator is not ready" }
        return translator.translate(text.trim())
    }

    fun isReady(): Boolean = ready.get() && !closed.get()

    fun close() {
        if (closed.compareAndSet(false, true)) {
            ready.set(false)
            translator.close()
        }
    }
}
