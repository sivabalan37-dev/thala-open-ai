package com.thala.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** One saved exchange: what you said and what Thala answered. */
class Exchange(val you: String, val thala: String, val tamil: Boolean)

/**
 * Thala's server: Firebase.
 *
 *  - Sign-in: anonymous (no account, no password). Every phone gets its own private space.
 *  - Storage: Cloud Firestore, at users/<your id>/chat. One document per exchange.
 *
 * It talks to Firebase over plain web requests (HTTPS), so there is still no extra library to add.
 * Everything runs in the background and never slows the screen.
 *
 * Optional: until the Project ID and Web API Key are filled in (CloudConfig.kt), [enabled] is false
 * and every function here quietly does nothing, so Thala works exactly as before.
 * No internet? Chats wait on the phone and are sent the next time there is a connection.
 */
class Cloud(context: Context) {

    private companion object {
        const val TAG = "ThalaCloud"
        const val TIMEOUT_MS = 15000
        const val KEEP_UNSENT = 50      // most unsent chats kept on the phone while offline
        const val MAX_YOU = 1500        // longest text saved for what you said
        const val MAX_THALA = 3500      // longest text saved for what Thala said (firestore.rules allows a bit more)
    }

    private class Problem(message: String) : Exception(message)
    private class Resp(val code: Int, val body: String)

    private val prefs = context.applicationContext.getSharedPreferences("thala_cloud", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** True when the Project ID and Web API Key are filled in. */
    val enabled: Boolean = CloudConfig.PROJECT_ID.isNotBlank() && CloudConfig.API_KEY.isNotBlank()

    /** Tells the screen if the cloud is working (true) or in trouble (false). Called on the screen thread. */
    var onState: ((Boolean) -> Unit)? = null

    /** One short "what to fix" message for the owner. Called on the screen thread. */
    var onProblem: ((String) -> Unit)? = null

    // Used only on the worker thread, one job at a time.
    private var uid: String? = prefs.getString("uid", null)
    private var idToken: String? = null
    private var tokenUntil = 0L
    private var healthy: Boolean? = null

    // ================= 1. WHAT THE SCREEN USES =================

    /** Signs in, sends anything that was waiting, then gives back your earlier chats (oldest first). */
    fun start(limit: Int, onHistory: (List<Exchange>) -> Unit) {
        if (!enabled) return
        worker.execute {
            try {
                signIn()
                flushUnsent()
                val old = loadHistory(limit)
                setHealthy(true)
                ui.post { onHistory(old) }
            } catch (e: Problem) {
                trouble(e.message)
            } catch (e: IOException) {
                Log.i(TAG, "No connection yet: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Cloud start failed", e)
            }
        }
    }

    /** Saves one exchange. With no connection it waits on the phone and is sent next time. */
    fun save(you: String, thala: String, tamil: Boolean) {
        if (!enabled) return
        worker.execute {
            queue(you, thala, tamil)
            try {
                signIn()
                flushUnsent()
                setHealthy(true)
            } catch (e: Problem) {
                trouble(e.message)
            } catch (e: IOException) {
                Log.i(TAG, "Kept on the phone, will send later: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Cloud save failed", e)
            }
        }
    }

    /** Call when the screen closes: finishes what is already queued, then stops. */
    fun stop() {
        onState = null
        onProblem = null
        worker.shutdown()
    }

    // ================= 2. SIGNING IN =================

    /** Makes sure there is a working sign-in token. Same anonymous user every time (saved on the phone). */
    private fun signIn() {
        if (idToken != null && System.currentTimeMillis() < tokenUntil - 60_000) return

        // Same user as last time: swap the saved refresh token for a fresh sign-in token.
        val saved = prefs.getString("refresh", null)
        if (saved != null) {
            val r = http(
                "https://securetoken.googleapis.com/v1/token?key=" + CloudConfig.API_KEY,
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(saved, "UTF-8"),
                "application/x-www-form-urlencoded",
                null
            )
            if (r.code == 200) {
                val j = JSONObject(r.body)
                takeToken(j.getString("id_token"), j.getString("refresh_token"), j.getString("user_id"), j.optString("expires_in"))
                return
            }
            if (r.code == 429 || r.code >= 500) throw IOException("Firebase is busy (${r.code})")
            // Otherwise that user no longer exists: carry on and make a new anonymous one.
        }

        val r = http(
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + CloudConfig.API_KEY,
            "{\"returnSecureToken\":true}",
            "application/json",
            null
        )
        if (r.code == 429 || r.code >= 500) throw IOException("Firebase is busy (${r.code})")
        if (r.code != 200) throw Problem(explain(r))
        val j = JSONObject(r.body)
        takeToken(j.getString("idToken"), j.getString("refreshToken"), j.getString("localId"), j.optString("expiresIn"))
    }

    private fun takeToken(id: String, refresh: String, user: String, expiresInSeconds: String) {
        idToken = id
        uid = user
        tokenUntil = System.currentTimeMillis() + (expiresInSeconds.toLongOrNull() ?: 3600L) * 1000L
        prefs.edit().putString("refresh", refresh).putString("uid", user).apply()
    }

    // ================= 3. SAVING AND LOADING CHATS =================

    /** Puts a chat in the "waiting to be sent" list on the phone (newest 50 are kept). */
    private fun queue(you: String, thala: String, tamil: Boolean) {
        val all = unsent()
        all.put(
            JSONObject()
                .put("you", you.take(MAX_YOU))
                .put("thala", thala.take(MAX_THALA))
                .put("tamil", tamil)
                .put("time", System.currentTimeMillis())
        )
        val keep = JSONArray()
        for (i in maxOf(0, all.length() - KEEP_UNSENT) until all.length()) keep.put(all.getJSONObject(i))
        prefs.edit().putString("unsent", keep.toString()).apply()
    }

    private fun unsent(): JSONArray = try {
        JSONArray(prefs.getString("unsent", "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** Sends the waiting chats, oldest first, and takes each off the list once Firebase has it. */
    private fun flushUnsent() {
        while (true) {
            val list = unsent()
            if (list.length() == 0) return
            val r = firestore("/chat", docBody(list.getJSONObject(0)))
            when {
                r.code in 200..299 -> {}
                // This one chat can never be saved: drop it so it doesn't block the others.
                r.code == 400 -> Log.w(TAG, "Dropping a chat Firebase refused: ${r.body.take(200)}")
                r.code == 429 || r.code >= 500 -> throw IOException("Firebase is busy (${r.code})")
                else -> throw Problem(explain(r))
            }
            list.remove(0)
            prefs.edit().putString("unsent", list.toString()).apply()
        }
    }

    /** The newest [limit] chats, oldest first. */
    private fun loadHistory(limit: Int): List<Exchange> {
        val query = JSONObject().put(
            "structuredQuery",
            JSONObject()
                .put("from", JSONArray().put(JSONObject().put("collectionId", "chat")))
                .put(
                    "orderBy",
                    JSONArray().put(
                        JSONObject()
                            .put("field", JSONObject().put("fieldPath", "time"))
                            .put("direction", "DESCENDING")
                    )
                )
                .put("limit", limit)
        )
        val r = firestore(":runQuery", query.toString())
        if (r.code == 429 || r.code >= 500) throw IOException("Firebase is busy (${r.code})")
        if (r.code != 200) throw Problem(explain(r))

        val rows = JSONArray(r.body)
        val out = ArrayList<Exchange>()
        for (i in 0 until rows.length()) {
            // an empty answer comes back as one row with no "document" in it
            val f = rows.getJSONObject(i).optJSONObject("document")?.optJSONObject("fields") ?: continue
            val you = f.optJSONObject("you")?.optString("stringValue") ?: continue
            val thala = f.optJSONObject("thala")?.optString("stringValue") ?: continue
            val tamil = f.optJSONObject("tamil")?.optBoolean("booleanValue") ?: false
            out.add(Exchange(you, thala, tamil))
        }
        out.reverse()
        return out
    }

    /** One chat in Firestore's own JSON shape. */
    private fun docBody(x: JSONObject): String {
        fun text(s: String) = JSONObject().put("stringValue", s)
        val fields = JSONObject()
            .put("you", text(x.optString("you")))
            .put("thala", text(x.optString("thala")))
            .put("tamil", JSONObject().put("booleanValue", x.optBoolean("tamil")))
            .put("time", JSONObject().put("timestampValue", stamp(x.optLong("time", System.currentTimeMillis()))))
        return JSONObject().put("fields", fields).toString()
    }

    private fun stamp(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    // ================= 4. TALKING TO FIREBASE =================

    private fun userUrl(path: String) =
        "https://firestore.googleapis.com/v1/projects/" + CloudConfig.PROJECT_ID +
            "/databases/(default)/documents/users/" + uid + path

    /** A Firestore request with your sign-in token. If the token is refused, sign in again and retry once. */
    private fun firestore(path: String, body: String): Resp {
        var r = http(userUrl(path), body, "application/json", idToken)
        if (r.code == 401) {
            idToken = null
            signIn()
            r = http(userUrl(path), body, "application/json", idToken)
        }
        return r
    }

    /** One web request (always a POST). Returns the answer even when it is an error code. */
    private fun http(url: String, body: String, type: String, bearer: String?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                // some Android versions throw instead of returning 401
                if (e.message?.contains("authentication challenge", ignoreCase = true) == true) 401 else throw e
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            c.disconnect()
        }
    }

    // ================= 5. TELLING THE SCREEN =================

    private fun setHealthy(ok: Boolean) {
        if (healthy == ok) return
        healthy = ok
        ui.post { onState?.invoke(ok) }
    }

    private fun trouble(message: String?) {
        Log.w(TAG, "Cloud problem: $message")
        setHealthy(false)
        if (message != null) ui.post { onProblem?.invoke(message) }
    }

    /** Turns a Firebase error into one short thing to fix. */
    private fun explain(r: Resp): String {
        val b = r.body.uppercase(Locale.ROOT)
        return when {
            "ADMIN_ONLY_OPERATION" in b || "OPERATION_NOT_ALLOWED" in b || "CONFIGURATION_NOT_FOUND" in b ->
                "Cloud: turn on Anonymous sign-in (Firebase > Authentication > Sign-in method)."

            "API KEY NOT VALID" in b || "API_KEY_INVALID" in b ->
                "Cloud: the Web API Key in CloudConfig.kt is not valid."

            "BLOCKED" in b ->
                "Cloud: this API key is restricted. Use the Web API Key from Project settings."

            "SERVICE_DISABLED" in b || "DOES NOT EXIST" in b || r.code == 404 ->
                "Cloud: create the Firestore database (Firebase > Firestore Database > Create database)."

            r.code == 403 ->
                "Cloud: access denied. Check the Project ID in CloudConfig.kt and publish firestore.rules."

            else -> "Cloud: could not connect (error ${r.code})."
        }
    }
}
