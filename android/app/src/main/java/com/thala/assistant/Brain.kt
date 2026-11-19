package com.thala.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One answer in two languages. MainActivity says the one that fits. */
class Reply(val en: String, val ta: String)

/**
 * Thala's brain: plain Kotlin rules. No AI service, no API key, no internet needed.
 * handle() reads one sentence, does the job on the phone, and returns what Thala should say.
 */
class Brain(private val act: Activity) {

    private var t = ""            // the sentence after clean(): lowercase, numbers as digits
    private var torchOn = false

    // ================= 1. WHAT DOES THE PERSON WANT? (first match wins) =================
    fun handle(raw: String): Reply {
        t = clean(raw)
        return when {
            has("timer", "டைமர்") -> timer()
            has("alarm", "அலாரம்", "wake me", "எழுப்பு") -> alarm()
            hasWord("open", "launch") || has("திற", "ஓபன்", "ஓப்பன்", "லாஞ்ச்") -> openApp()
            has("calendar", "event", "meeting", "remind", "schedule", "நாட்காட்டி", "காலண்டர்", "நிகழ்வு", "கூட்டம்", "நினைவூட்டு", "ரிமைண்டர்") -> calendar()
            hasWord("call", "dial") || has("அழை", "கால் பண்ண", "கால் செய்", "போன் பண்ண", "போன் செய்", "டயல்") -> call()
            has("torch", "flashlight", "flash light", "டார்ச்") -> torch()
            has("wifi", "wi-fi", "வைஃபை", "வைபை") -> screen(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings", "வைஃபை அமைப்புகளை")
            has("bluetooth", "புளூடூத்", "ப்ளூடூத்") -> screen(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings", "புளூடூத் அமைப்புகளை")
            has("location", "gps", "இருப்பிடம்") -> screen(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location settings", "இருப்பிட அமைப்புகளை")
            has("volume", "louder", "quieter", "mute", "வால்யூம்", "சத்தம்", "ஒலி") -> volume()
            has("battery", "பேட்டரி", "சார்ஜ்") -> battery()
            hasWord("play") || has("பாடல்", "பாட்டு", "பாடு") -> play()
            has("navigate", "direction", "வழி") -> navigate()
            has("time", "நேரம்", "மணி என்ன") -> clock()
            hasWord("date") || has("what day", "தேதி", "கிழமை") -> date()
            else -> chat()
        }
    }

    // ================= 2. THE JOBS =================

    /** "set a timer for 5 minutes" / "5 நிமிடம் டைமர் வை" */
    private fun timer(): Reply {
        var total = num(RX_H) * 3600 + num(RX_M) * 60 + num(RX_S)
        if (total == 0 && has("half an hour", "half hour", "அரை மணி")) total = 1800
        if (total == 0) {
            return Reply("For how long? Say: set a timer for 5 minutes.", "எவ்வளவு நேரம்? உதாரணம்: 5 நிமிடம் டைமர் வை.")
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Thala")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!go(i)) return noApp()
        return Reply("Timer set for ${dur(total, false)}.", "${dur(total, true)} டைமர் வைத்துவிட்டேன்.")
    }

    /** "set alarm for 6:30 am" / "காலை 6 மணிக்கு அலாரம் வை" */
    private fun alarm(): Reply {
        val tm = parseTime(true)
            ?: return Reply("For what time? Say: set alarm for 6:30 AM.", "எத்தனை மணிக்கு? உதாரணம்: காலை 6:30 மணிக்கு அலாரம் வை.")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, tm.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, tm.second)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Thala")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!go(i)) return noApp()
        return Reply(
            "Alarm set for ${hm(tm.first, tm.second)}.",
            "${part(tm.first)} ${hm12(tm.first, tm.second)} மணிக்கு அலாரம் வைத்துவிட்டேன்."
        )
    }

    /** "open whatsapp" / "யூடியூப் திற" */
    private fun openApp(): Reply {
        val name = appName()
        if (name.isEmpty()) return Reply("Which app should I open?", "எந்த ஆப்பைத் திறக்க வேண்டும்?")
        val shown = name.substringBefore("|")
        val app = findApp(name, true)
            ?: return Reply("I couldn't find $shown on this phone.", "$shown ஆப் இந்த போனில் இல்லை.")
        return launch(app)
    }

    private fun launch(app: Pair<String, String>): Reply {
        val i = act.packageManager.getLaunchIntentForPackage(app.second)
        return if (i != null && go(i)) {
            Reply("Opening ${app.first}.", "${app.first} திறக்கிறேன்.")
        } else {
            Reply("I couldn't open ${app.first}.", "${app.first} ஐ திறக்க முடியவில்லை.")
        }
    }

    /** "add meeting tomorrow 4 pm" -> opens your Calendar with everything filled in; you tap Save */
    private fun calendar(): Reply {
        val c = Calendar.getInstance()
        val tm = parseTime(false)
        val tomorrow = has("tomorrow", "நாளை")
        if (tomorrow) c.add(Calendar.DAY_OF_YEAR, 1)
        if (tm != null) {
            c.set(Calendar.HOUR_OF_DAY, tm.first)
            c.set(Calendar.MINUTE, tm.second)
            // time already passed today and no day was said -> use tomorrow
            if (!tomorrow && !has("today", "இன்று") && c.timeInMillis < System.currentTimeMillis()) {
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            c.add(Calendar.HOUR_OF_DAY, 1)
            c.set(Calendar.MINUTE, 0)
        }
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val title = t.split(" ")
            .filter { it.isNotEmpty() && it !in CAL_STOP && !it.first().isDigit() }
            .joinToString(" ")
            .ifEmpty { "Thala reminder" }
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.timeInMillis + 3600000L)
        if (!go(i)) return noApp()
        val whenText = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(c.time)
        return Reply(
            "Opening your calendar: $title, $whenText. Tap Save.",
            "காலண்டரில் $title நிகழ்வைத் திறக்கிறேன். சேமி என்பதைத் தட்டுங்கள்."
        )
    }

    /** "call 98765 43210" / "call mom" / "அம்மாவுக்கு போன் பண்ணு" */
    private fun call(): Reply {
        val typed = RX_PHONE.find(t)?.value?.filter { it.isDigit() || it == '+' }
        val label: String
        val number: String
        if (typed != null) {
            label = typed
            number = typed
        } else {
            val name = t.split(" ").filter { it.isNotEmpty() && it !in CALL_STOP }.joinToString(" ")
            if (name.isEmpty()) return Reply("Whom should I call?", "யாரை அழைக்க வேண்டும்?")
            if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return Reply("Please allow the Contacts permission, or say the phone number.", "தொடர்புகள் அனுமதியை வழங்குங்கள், அல்லது எண்ணைச் சொல்லுங்கள்.")
            }
            val hit = findContact(name)
                ?: return Reply("I couldn't find $name in your contacts.", "$name தொடர்புகளில் இல்லை.")
            label = hit.first
            number = hit.second
        }
        val allowed = act.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL
        if (!go(Intent(action, Uri.fromParts("tel", number, null)))) return noApp()
        return Reply("Calling $label.", "$label ஐ அழைக்கிறேன்.")
    }

    /** "torch on" / "torch off" / "டார்ச் ஆன்" */
    private fun torch(): Reply {
        val on = when {
            has("off", "ஆஃப்", "அணை", "நிறுத்து") -> false
            hasWord("on") || has("ஆன்", "எரி", "போடு") -> true
            else -> !torchOn
        }
        return try {
            val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) Reply("Torch on.", "டார்ச் ஆன் செய்தேன்.") else Reply("Torch off.", "டார்ச் ஆஃப் செய்தேன்.")
        } catch (e: Exception) {
            Reply("I couldn't use the torch on this phone.", "இந்த போனில் டார்ச்சை இயக்க முடியவில்லை.")
        }
    }

    /** Opens a Settings screen (Android no longer lets apps switch Wi-Fi/Bluetooth silently). */
    private fun screen(action: String, en: String, ta: String): Reply =
        if (go(Intent(action))) Reply("Opening $en.", "$ta திறக்கிறேன்.") else noApp()

    /** "volume up" / "volume down" / "mute" / "ஒலி கூட்டு" / "ஒலி குறை" */
    private fun volume(): Reply {
        val up = hasWord("up", "increase", "raise", "louder", "high") || has("கூட்டு", "அதிகரி", "அதிகம்")
        val down = hasWord("down", "decrease", "lower", "quieter", "low") || has("குறை", "கம்மி")
        val mute = hasWord("mute", "silent") || has("சைலண்ட்", "அமைதி")
        if (!up && !down && !mute) {
            return Reply("Say volume up, volume down, or mute.", "ஒலி கூட்டு, ஒலி குறை, அல்லது அமைதி என்று சொல்லுங்கள்.")
        }
        val dir = if (mute) AudioManager.ADJUST_MUTE else if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            repeat(if (mute) 1 else 3) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            }
            if (mute) Reply("Muted.", "ஒலியை அமைதியாக்கினேன்.")
            else if (up) Reply("Volume up.", "ஒலியை அதிகரித்தேன்.")
            else Reply("Volume down.", "ஒலியைக் குறைத்தேன்.")
        } catch (e: Exception) {
            Reply("I couldn't change the volume.", "ஒலியை மாற்ற முடியவில்லை.")
        }
    }

    private fun battery(): Reply {
        val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Reply(
            "Battery is $p percent" + (if (charging) ", charging." else "."),
            "பேட்டரி $p சதவீதம்" + (if (charging) ", சார்ஜ் ஆகிறது." else " உள்ளது.")
        )
    }

    /** "play ilayaraja songs" -> searches YouTube (app if installed, else browser) */
    private fun play(): Reply {
        val q = t.split(" ").filter { it.isNotEmpty() && it !in PLAY_STOP }.joinToString(" ")
        if (q.isEmpty()) return Reply("What should I play?", "என்ன பாடலை இயக்க வேண்டும்?")
        val u = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        if (!go(Intent(Intent.ACTION_VIEW, u))) return noApp()
        return Reply("Searching YouTube for $q.", "$q யூடியூபில் தேடுகிறேன்.")
    }

    /** "navigate to chennai airport" -> shows the place in your maps app */
    private fun navigate(): Reply {
        val q = t.split(" ").filter { it.isNotEmpty() && it !in NAV_STOP }.joinToString(" ")
        if (q.isEmpty()) return Reply("Where do you want to go?", "எங்கே செல்ல வேண்டும்?")
        if (!go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))) return noApp()
        return Reply("Showing $q on the map.", "$q வரைபடத்தில் காட்டுகிறேன்.")
    }

    private fun clock(): Reply {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val taMin = if (m == 0) "" else " $m நிமிடம்"
        return Reply("It's ${hm(h, m)}.", "இப்போது ${part(h)} $h12 மணி$taMin.")
    }

    private fun date(): Reply {
        val now = Date()
        val en = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)
        val ta = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.forLanguageTag("ta-IN")).format(now)
        return Reply("Today is $en.", "இன்று $ta.")
    }

    /** Small talk, then web search for questions, then "maybe you just said an app name". */
    private fun chat(): Reply {
        if (t == "thala" || t == "தல") return Reply("Yes, I'm listening.", "சொல்லுங்கள், கேட்கிறேன்.")
        if (has("your name", "who are you", "உன் பெயர்", "உங்கள் பெயர்", "நீ யார்", "நீங்கள் யார்")) {
            return Reply("I'm Thala, your voice assistant.", "நான் தல, உங்கள் குரல் உதவியாளர்.")
        }
        if (hasWord("hello", "hi", "hey") || has("வணக்கம்", "ஹலோ", "ஹாய்")) {
            return Reply("Hi! I'm Thala. How can I help you?", "வணக்கம்! நான் தல. எப்படி உதவலாம்?")
        }
        if (has("thank", "நன்றி")) return Reply("You're welcome!", "பரவாயில்லை!")
        if (hasWord("help") || has("what can you do", "உதவி", "என்ன செய்ய")) return Reply(HELP_EN, HELP_TA)
        if (hasWord("search", "google", "what", "who", "why", "how", "when", "where") ||
            has("தேடு", "தேட", "என்றால் என்ன", "பற்றி", "யார்", "என்ன", "ஏன்", "எப்படி")
        ) {
            return web()
        }
        val app = findApp(appName(), false)
        if (app != null) return launch(app)
        return Reply(
            "Sorry, I didn't understand. Say help to hear what I can do.",
            "மன்னிக்கவும், புரியவில்லை. உதவி என்று சொன்னால் நான் என்ன செய்வேன் என்று சொல்வேன்."
        )
    }

    /** Questions go to a normal Google search in your browser (no AI service involved). */
    private fun web(): Reply {
        val q = t.split(" ").filter { it.isNotEmpty() && it !in SEARCH_STOP }.joinToString(" ")
        val u = Uri.parse("https://www.google.com/search?q=" + Uri.encode(q.ifEmpty { t }))
        if (!go(Intent(Intent.ACTION_VIEW, u))) return noApp()
        return Reply("Searching the web.", "இணையத்தில் தேடுகிறேன்.")
    }

    // ================= 3. FINDING APPS AND CONTACTS =================

    /** The app name inside the sentence: a known Tamil spelling, or whatever is left after dropping filler words. */
    private fun appName(): String {
        for ((key, app) in ALIAS) if (t.contains(key)) return app
        return t.split(" ").filter { it.isNotEmpty() && it !in OPEN_STOP }.joinToString(" ")
    }

    /** All apps that have a launcher icon: (label, package name). */
    private fun loadApps(): List<Pair<String, String>> {
        val pm = act.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
    }

    /** Best match for the name. loose = also accept "name is somewhere inside the label". */
    private fun findApp(query: String, loose: Boolean): Pair<String, String>? {
        val apps = loadApps().map { Pair(it, squash(it.first)) }
        for (cand in query.split("|")) {
            val q = squash(cand)
            if (q.length < 2) continue
            apps.firstOrNull { it.second == q }?.let { return it.first }
            apps.firstOrNull { it.second.startsWith(q) }?.let { return it.first }
            if (loose) {
                if (q.length >= 3) apps.firstOrNull { it.second.contains(q) }?.let { return it.first }
                apps.firstOrNull { it.second.length >= 4 && q.contains(it.second) }?.let { return it.first }
            }
        }
        return null
    }

    /** Looks in your contacts. Tamil adds endings (அம்மாவுக்கு), so we also try shorter versions of the name. */
    private fun findContact(name: String): Pair<String, String>? {
        queryContact(name)?.let { return it }
        var n = name.dropLast(1)
        var tries = 0
        while (n.length >= 4 && tries < 8) {
            queryContact(n)?.let { return it }
            n = n.dropLast(1)
            tries++
        }
        return null
    }

    private fun queryContact(n: String): Pair<String, String>? {
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sel = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        act.contentResolver.query(phone, cols, sel, arrayOf("%$n%"), null)?.use { c ->
            if (c.moveToFirst()) return Pair(c.getString(0), c.getString(1))
        }
        return null
    }

    // ================= 4. SMALL HELPERS =================

    private fun has(vararg w: String) = w.any { t.contains(it) }

    private fun hasWord(vararg w: String) = w.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }

    private fun num(rx: Regex) = rx.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun go(i: Intent): Boolean = try {
        act.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun noApp() = Reply("Sorry, no app on this phone can do that.", "மன்னிக்கவும், இதைச் செய்ய இந்த போனில் ஆப் இல்லை.")

    /** Lowercase, drop punctuation, turn number words into digits: "five minutes" -> "5 minutes". */
    private fun clean(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).replace(Regex("""[,!?"'’]"""), " ")
        s = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ") { NUM[it] ?: it }
        // "a minute", "an hour", "ஒரு நிமிடம்" -> "1 minute"
        s = Regex("""(^|\s)(a|an|ஒரு)\s+(?=hour|minute|min|second|sec|மணி|நிமிட|வினாடி|விநாடி|நொடி)""")
            .replace(s) { it.groupValues[1] + "1 " }
        // "twenty five" -> "20 5" -> "25"
        s = Regex("""\b([2-9]0) ([1-9])\b""")
            .replace(s) { (it.groupValues[1].toInt() + it.groupValues[2].toInt()).toString() }
        return s.trim().trimEnd('.')
    }

    /** Letters and digits only, so "what's app" and "WhatsApp" look the same. Keeps Tamil letters. */
    private fun squash(s: String) =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it in '\u0B80'..'\u0BFF' }

    /** Finds a clock time: "6:30 pm", "at 5", "காலை 6 மணிக்கு". lenient = accept any bare number. */
    private fun parseTime(lenient: Boolean): Pair<Int, Int>? {
        val all = RX_TIME.findAll(t).toList()
        val strong = all.firstOrNull { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() }
        val hinted = all.firstOrNull {
            val before = t.substring(0, it.range.first)
            val after = t.substring(it.range.last + 1).trimStart()
            before.endsWith("at ") || before.endsWith("for ") || before.endsWith("@") || after.startsWith("மணி")
        }
        val m = strong ?: hinted ?: (if (lenient) all.firstOrNull() else null) ?: return null
        var h = m.groupValues[1].toInt()
        val min = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val ap = m.groupValues[3]
        val pm = ap.startsWith("p") || has("மாலை", "இரவு", "மதியம்", "evening", "night", "afternoon")
        val am = ap.startsWith("a") || has("காலை", "morning")
        if (pm && h in 1..11) h += 12
        if (am && h == 12) h = 0
        if (h == 12 && has("இரவு", "night")) h = 0
        return Pair(h, min)
    }

    private fun hm12(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:" + "%02d".format(m)
    }

    private fun hm(h: Int, m: Int) = hm12(h, m) + (if (h < 12) " AM" else " PM")

    /** Tamil part of the day. */
    private fun part(h: Int) = when (h) {
        in 4..5 -> "அதிகாலை"
        in 6..11 -> "காலை"
        in 12..15 -> "மதியம்"
        in 16..18 -> "மாலை"
        else -> "இரவு"
    }

    /** 3900 seconds -> "1 hour 5 minutes" (or in Tamil). */
    private fun dur(total: Int, ta: Boolean): String {
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        val p = ArrayList<String>()
        if (h > 0) p.add(if (ta) "$h மணி நேரம்" else "$h hour" + (if (h > 1) "s" else ""))
        if (m > 0) p.add(if (ta) "$m நிமிடம்" else "$m minute" + (if (m > 1) "s" else ""))
        if (s > 0) p.add(if (ta) "$s வினாடி" else "$s second" + (if (s > 1) "s" else ""))
        return p.joinToString(" ")
    }
}

// ================= 5. WORD LISTS (edit these to teach Thala new words) =================

private const val HELP_EN = "I can open apps, set timers and alarms, add calendar events, call your contacts, " +
    "switch the torch, tell the time, date and battery, change the volume, show places on the map, " +
    "search YouTube and search the web."

private const val HELP_TA = "ஆப்ஸ் திறக்க, டைமர் மற்றும் அலாரம் வைக்க, காலண்டரில் நிகழ்வு சேர்க்க, அழைக்க, " +
    "டார்ச் இயக்க, நேரம், தேதி, பேட்டரி சொல்ல, ஒலியை மாற்ற, வரைபடம் காட்ட, " +
    "யூடியூப் மற்றும் இணையத்தில் தேட என்னால் முடியும்."

private val RX_H = Regex("""(\d+)\s*(?:hours?|hrs?|மணி நேரம்|மணிநேரம்)""")
private val RX_M = Regex("""(\d+)\s*(?:minutes?|mins?|நிமிடங்கள்|நிமிடம்|நிமிடத்தில்|நிமிட)""")
private val RX_S = Regex("""(\d+)\s*(?:seconds?|secs?|வினாடிகள்|வினாடி|விநாடி|நொடி)""")
private val RX_TIME = Regex("""(\d{1,2})(?:[:. ](\d{2}))?\s*(am|pm|a\.m\.?|p\.m\.?)?""")
private val RX_PHONE = Regex("""\+?\d[\d\s-]{4,}\d""")

/** Number words -> digits (English and Tamil). */
private val NUM = mapOf(
    "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
    "twelve" to "12", "thirteen" to "13", "fourteen" to "14", "fifteen" to "15", "sixteen" to "16",
    "seventeen" to "17", "eighteen" to "18", "nineteen" to "19", "twenty" to "20", "thirty" to "30",
    "forty" to "40", "fifty" to "50", "sixty" to "60",
    "ஒன்று" to "1", "இரண்டு" to "2", "மூன்று" to "3", "நான்கு" to "4", "ஐந்து" to "5",
    "ஆறு" to "6", "ஏழு" to "7", "எட்டு" to "8", "ஒன்பது" to "9", "பத்து" to "10",
    "பதினொன்று" to "11", "பன்னிரண்டு" to "12", "பதிமூன்று" to "13", "பதினான்கு" to "14",
    "பதினைந்து" to "15", "பதினாறு" to "16", "பதினேழு" to "17", "பதினெட்டு" to "18",
    "பத்தொன்பது" to "19", "இருபது" to "20", "இருபத்தைந்து" to "25", "முப்பது" to "30",
    "நாற்பது" to "40", "நாற்பத்தைந்து" to "45", "ஐம்பது" to "50", "அறுபது" to "60"
)

/** Tamil (or short English) app names -> the name on the phone. "a|b" = try a, then b. */
private val ALIAS = listOf(
    "யூடியூப" to "youtube", "யூட்யூப" to "youtube",
    "வாட்ஸ்" to "whatsapp", "வாட்ஸப" to "whatsapp", "வாட்சப" to "whatsapp",
    "இன்ஸ்டா" to "instagram",
    "பேஸ்புக்" to "facebook", "ஃபேஸ்புக்" to "facebook",
    "டெலிகிராம்" to "telegram",
    "குரோம்" to "chrome",
    "மேப்" to "maps", "வரைபடம்" to "maps",
    "கூகுள்" to "google",
    "கேமரா" to "camera",
    "கேலரி" to "gallery|photos", "போட்டோ" to "photos|gallery",
    "கால்குலேட்டர்" to "calculator",
    "கடிகாரம்" to "clock",
    "செட்டிங்" to "settings", "அமைப்புகள்" to "settings",
    "ஜிமெயில்" to "gmail",
    "ப்ளே ஸ்டோர்" to "play store", "பிளே ஸ்டோர்" to "play store",
    "ஸ்பாடிஃபை" to "spotify",
    "போன்பே" to "phonepe", "ஃபோன்பே" to "phonepe",
    "ஜிபே" to "google pay|gpay", "gpay" to "google pay|gpay", "g pay" to "google pay|gpay",
    "மெசேஜ்" to "messages|messaging",
    "காண்டாக்ட்" to "contacts", "தொடர்புகள்" to "contacts",
    "நோட்ஸ்" to "notes|keep"
)

private val OPEN_STOP = setOf(
    "open", "launch", "start", "run", "please", "the", "app", "application", "my", "me", "can", "you",
    "could", "for", "to", "thala", "தல", "திற", "திறக்க", "திறக்கவும்", "திறங்க", "திறங்கள்", "திறந்து",
    "திறப்பா", "ஓபன்", "ஓப்பன்", "செய்", "செய்யவும்", "பண்ணு", "பண்ணுங்க", "ஆப்", "ஆப்பை", "செயலி",
    "செயலியை", "தயவுசெய்து", "ப்ளீஸ்"
)

private val CALL_STOP = setOf(
    "call", "dial", "phone", "to", "please", "the", "my", "thala", "தல", "அழை", "அழைக்க", "அழைக்கவும்",
    "கால்", "போன்", "ஃபோன்", "பண்ணு", "பண்ணுங்க", "செய்", "செய்யவும்", "டயல்", "ப்ளீஸ்"
)

private val NAV_STOP = setOf(
    "navigate", "navigation", "direction", "directions", "route", "take", "me", "to", "go", "how", "get",
    "way", "the", "please", "start", "thala", "தல", "வழி", "காட்டு", "காட்டுங்க", "செல்ல", "போக", "ப்ளீஸ்"
)

private val PLAY_STOP = setOf(
    "play", "song", "songs", "music", "on", "youtube", "the", "please", "some", "a", "thala", "தல",
    "பாடல்", "பாடல்கள்", "பாட்டு", "பாடு", "போடு", "போடுங்க", "ப்ளே", "யூடியூபில்", "இயக்கு", "ப்ளீஸ்"
)

private val SEARCH_STOP = setOf(
    "search", "google", "for", "please", "thala", "தல", "தேடு", "தேட", "தேடுங்க", "இணையத்தில்", "ப்ளீஸ்"
)

private val CAL_STOP = setOf(
    "set", "add", "create", "new", "make", "put", "a", "an", "the", "my", "me", "to", "at", "on", "for", "in",
    "calendar", "event", "schedule", "remind", "reminder", "tomorrow", "today", "please", "thala", "தல",
    "am", "pm", "a.m.", "p.m.", "a.m", "p.m", "நாளை", "இன்று", "நாட்காட்டி", "நாட்காட்டியில்", "காலண்டர்",
    "காலண்டரில்", "நிகழ்வு", "சேர்", "சேர்க்க", "சேர்த்து", "வை", "வைக்க", "வைங்க", "நினைவூட்டு",
    "ரிமைண்டர்", "மணிக்கு", "மணி", "காலை", "மாலை", "இரவு", "மதியம்", "பண்ணு", "செய்", "உருவாக்கு"
)
