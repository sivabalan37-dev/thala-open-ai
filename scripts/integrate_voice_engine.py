from pathlib import Path
import sys

project = Path(sys.argv[1])
main = project / "app/src/main/java/com/thala/assistant/MainActivity.kt"
s = main.read_text(encoding="utf-8")

def replace_once(old, new):
    global s
    if old not in s:
        raise SystemExit("Expected source section was not found; refusing a partial voice migration.")
    s = s.replace(old, new, 1)

s = s.replace("import android.speech.tts.UtteranceProgressListener\n", "")
s = s.replace("import android.speech.tts.Voice\n", "")
replace_once(
    "    private var tts: TextToSpeech? = null\n    private var ttsReady = false\n",
    "    private var voiceEngine: VoiceEngine? = null\n    private var ttsReady = false\n"
)
replace_once(
    "        tts = TextToSpeech(this) { code -> runOnUiThread { onTtsReady(code) } }\n",
    """        voiceEngine = VoiceEngine(this) { ready -> runOnUiThread { onTtsReady(ready) } }.also { engine ->
            engine.pitch = pitch
            engine.rate = rate
            engine.onFinished = {
                runOnUiThread {
                    if (!listening) {
                        stopPulse()
                        setStatus(idleText())
                    }
                }
            }
        }
"""
)
replace_once(
    "        tts?.stop()\n        tts?.shutdown()\n        tts = null\n",
    "        voiceEngine?.shutdown()\n        voiceEngine = null\n"
)
s = s.replace("        tts?.stop()\n", "        voiceEngine?.stop()\n")
start = s.index("    // ================= 7. THALA'S VOICE =================")
end = s.index("    // ================= 8. THE CLOUD", start)
voice_block = r'''    // ================= 7. THALA'S VOICE =================

    private fun onTtsReady(ready: Boolean) {
        ttsReady = ready
        if (!ready) {
            addBubble(
                "I can't find a text-to-speech engine on this phone, so I can show answers but not say them. " +
                    "Install a Tamil/English speech engine in Android settings.",
                false
            )
            return
        }
        speakReply(greeting, tamilMode)
    }

    private fun speakReply(r: Reply, tamil: Boolean) {
        if (!ttsReady) return
        if (tamil) {
            if (voiceEngine?.hasLanguage(true) == true) {
                say(r.ta, "ta")
                return
            }
            if (!warnedNoTamilVoice) {
                warnedNoTamilVoice = true
                toast("Tamil voice data is not installed. Open Voice > More voices and install Tamil.")
            }
        }
        say(r.en, "en")
    }

    /** VoiceEngine automatically selects Tamil or English voices for mixed-script text. */
    private fun say(line: String, lang: String): Boolean {
        val engine = voiceEngine ?: return false
        if (!ttsReady) return false
        if (lang == "ta" && !engine.hasLanguage(true)) return false
        if (lang == "en" && !engine.hasLanguage(false)) return false
        setStatus(tr("Speaking…", "பேசுகிறேன்…"))
        startPulse()
        engine.speak(line)
        return true
    }

    private fun sample(lang: String) {
        say(
            if (lang == "ta") "வணக்கம்! நான் தல. என் குரல் எப்படி இருக்கிறது?" else "Hi! I'm Thala. How do I sound?",
            lang
        )
    }

    /** Voice menu: choose an installed system voice and adjust speech pitch/speed. */
    private fun showVoiceDialog() {
        val engine = voiceEngine
        if (!ttsReady || engine == null) {
            toast(tr("My voice is not ready yet. Try again in a moment.", "என் குரல் இன்னும் தயாராகவில்லை. சிறிது நேரம் கழித்து முயலுங்கள்."))
            return
        }
        val tamil = tamilMode
        val locale = if (tamil) Locale.forLanguageTag("ta-IN") else Locale.forLanguageTag("en-IN")
        val lang = if (tamil) "ta" else "en"
        val voices = engine.voicesFor(locale)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(TextView(this).apply {
            text = if (tamil) "தமிழ் குரல்கள் (தட்டிக் கேளுங்கள்)" else "English voices (tap to hear)"
            textSize = 13f
            setTextColor(cSub)
        })

        if (voices.isEmpty()) {
            box.addView(TextView(this).apply {
                text = tr(
                    "No installed voice found for this language. Tap “More voices” to install one.",
                    "இந்த மொழிக்கு நிறுவப்பட்ட குரல் இல்லை. “மேலும் குரல்கள்” என்பதைத் தட்டி நிறுவுங்கள்."
                )
                textSize = 15f
                setTextColor(cText)
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            val names = voices.mapIndexed { i, v ->
                "Voice " + (i + 1) + "  ·  " + v.locale.toLanguageTag() + (if (v.isNetworkConnectionRequired) "  (online)" else "  (offline)")
            }
            val list = ListView(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_single_choice, names)
                choiceMode = ListView.CHOICE_MODE_SINGLE
                setOnItemClickListener { _, _, pos, _ ->
                    engine.selectVoice(tamil, voices[pos].name)
                    prefs.edit().putString("voice_$lang", voices[pos].name).apply()
                    sample(lang)
                }
            }
            box.addView(list, LinearLayout.LayoutParams(MATCH, dp(220)))
        }

        addSlider(box, tr("Pitch (higher = brighter)", "சுருதி (அதிகம் = உயர்ந்த குரல்)"), 0.8f, 1.8f, pitch) { v ->
            pitch = v
            engine.pitch = v
            prefs.edit().putFloat("pitch", v).apply()
            sample(lang)
        }
        addSlider(box, tr("Speed", "வேகம்"), 0.7f, 1.5f, rate) { v ->
            rate = v
            engine.rate = v
            prefs.edit().putFloat("rate", v).apply()
            sample(lang)
        }

        val body = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr("Voice", "குரல்"))
            .setView(body)
            .setPositiveButton(tr("Done", "சரி"), null)
            .setNeutralButton(tr("More voices", "மேலும் குரல்கள்")) { _, _ -> openVoiceSettings() }
            .show()
    }

    private fun addSlider(parent: LinearLayout, title: String, lo: Float, hi: Float, startValue: Float, onDone: (Float) -> Unit) {
        val label = TextView(this).apply {
            textSize = 14f
            setTextColor(cText)
            setPadding(0, dp(14), 0, 0)
        }
        fun showValue(v: Float) {
            label.text = title + ": " + String.format(Locale.US, "%.2f", v)
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = (((startValue - lo) / (hi - lo)) * 100f).toInt().coerceIn(0, 100)
        }
        showValue(startValue)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                showValue(lo + (hi - lo) * value / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onDone(lo + (hi - lo) * (sb?.progress ?: 0) / 100f)
            }
        })
        parent.addView(label)
        parent.addView(bar)
    }

    /** Opens Android's system text-to-speech settings to install Tamil or English voice data. */
    private fun openVoiceSettings() {
        if (go(Intent("com.android.settings.TTS_SETTINGS"))) return
        if (go(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))) return
        go(Intent(Settings.ACTION_SETTINGS))
    }

'''
s = s[:start] + voice_block + s[end:]
if "tts?" in s or "private var tts:" in s:
    raise SystemExit("Old TTS implementation remains; refusing to finish migration.")
main.write_text(s, encoding="utf-8")
print("VoiceEngine migration applied to", main)
