package com.speedcards.app.quickadd

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** ML Kit on-device language detection and translation. Text never leaves the phone. */
@Singleton
class OnDeviceLanguage @Inject constructor() {
    private val identifier by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build())
    }

    /** Two-letter language code ("es", "zh", …) or null when unsure. */
    suspend fun detect(text: String): String? = runCatching {
        identifier.identifyLanguage(text).await().takeIf { it != "und" }?.substringBefore('-')
    }.getOrNull()

    /**
     * Translates [text] from [fromTag] to [toTag] (BCP-47, e.g. "es-MX" → "en-US").
     * The first use of a language pair downloads its model (~30 MB).
     */
    suspend fun translate(text: String, fromTag: String, toTag: String): String {
        val from = TranslateLanguage.fromLanguageTag(fromTag.substringBefore('-'))
            ?: throw IllegalArgumentException("Translation isn't available for $fromTag")
        val to = TranslateLanguage.fromLanguageTag(toTag.substringBefore('-'))
            ?: throw IllegalArgumentException("Translation isn't available for $toTag")
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return translator.translate(text).await()
        } finally {
            translator.close()
        }
    }
}
