package com.thala.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * THALA AI's offline-capable Android speech engine.
 * Uses installed Android TTS voices; no external API key or internet is required
 * for voices whose data is installed locally.
 */
class VoiceEngine(
    context: Context,
    private val onReady: (Boolean) -> Unit = {}
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("voice_engine", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val tamil = Locale.forLanguageTag("ta-IN")
    private val english = Locale.forLanguageTag("en-IN")
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    @Volatile private var lastId = ""
    private var counter = 0

    var pitch = 1.3f
    var rate = 1.05f
    var onFinished: (() -> Unit)? = null
    val isReady: Boolean get() = ready

    init {
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished(utteranceId)
                    @Deprecated("Required by the base class")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                })
                pending?.let { pending = null; speak(it) }
            }
            onReady(ready)
        }
    }

    private fun finished(id: String?) {
        if (id == lastId) main.post { onFinished?.invoke() }
    }

    /** Tamil script uses ta-IN; other text uses en-IN. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (!ready || engine == null) {
            pending = text
            return
        }
        splitByScript(text).forEachIndexed { i, (chunk, isTamil) ->
            val locale = if (isTamil) tamil else english
            val voice = pickVoice(locale)
            if (voice != null) engine.setVoice(voice) else engine.setLanguage(locale)
            engine.setPitch(pitch)
            engine.setSpeechRate(rate)
            lastId = "voice-" + (++counter)
            engine.speak(
                chunk,
                if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                lastId
            )
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun nextVoice(tamilVoice: Boolean): String? {
        val locale = if (tamilVoice) tamil else english
        val list = voicesFor(locale)
        if (list.isEmpty()) return null
        val current = prefs.getString("voice_" + locale.language, null) ?: list.first().name
        val next = list[(list.indexOfFirst { it.name == current } + 1) % list.size]
        prefs.edit().putString("voice_" + locale.language, next.name).apply()
        speak(if (tamilVoice) "வணக்கம், நான் உங்கள் உதவியாளர்" else "Hi, I am your assistant")
        return next.name
    }

    fun selectVoice(tamilVoice: Boolean, voiceName: String): Boolean {
        val locale = if (tamilVoice) tamil else english
        val selected = voicesFor(locale).firstOrNull { it.name == voiceName } ?: return false
        prefs.edit().putString("voice_" + locale.language, selected.name).apply()
        return true
    }

    fun hasLanguage(tamilVoice: Boolean): Boolean {
        val result = tts?.isLanguageAvailable(if (tamilVoice) tamil else english) ?: return false
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    fun installVoiceData() {
        try {
            app.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // The device may not provide a voice-data installer.
        }
    }

    fun voicesFor(locale: Locale): List<Voice> {
        val all = try { tts?.voices } catch (_: Exception) { null } ?: return emptyList()
        return all
            .filter { it.locale.language == locale.language }
            .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(
                compareBy<Voice> { it.locale.country != locale.country }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )
    }

    private fun pickVoice(locale: Locale): Voice? {
        val list = voicesFor(locale)
        val saved = prefs.getString("voice_" + locale.language, null)
        return list.firstOrNull { it.name == saved } ?: list.firstOrNull()
    }

    private fun splitByScript(text: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var tamilRun = false
        for (ch in text) {
            val isTamil = ch in '\u0B80'..'\u0BFF'
            val neutral = !isTamil && !ch.isLetter()
            if (!neutral && isTamil != tamilRun && sb.isNotBlank()) {
                out.add(sb.toString() to tamilRun)
                sb.setLength(0)
            }
            if (!neutral) tamilRun = isTamil
            sb.append(ch)
        }
        if (sb.isNotBlank()) out.add(sb.toString() to tamilRun)
        return out
    }
}
