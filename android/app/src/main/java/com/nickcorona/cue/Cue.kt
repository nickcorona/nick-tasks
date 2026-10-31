package com.nickcorona.cue

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// The app is a client of the same server as the web app: everything Cue decides (what's next,
// when to ping) stays there. The phone keeps the APP_KEY, signs requests with ?key=, and
// draws the result natively.
object Cue {
    const val BASE = BuildConfig.BASE_URL
    const val NOW_ID = 1
    const val NOW_TAG = "cue-now"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cue", Context.MODE_PRIVATE)
    fun key(c: Context): String? = prefs(c).getString("key", null)?.takeIf { it.isNotBlank() }

    // The last Now payload shown, so a capture or a failed tap can put it back.
    fun saveNow(c: Context, j: JSONObject?) = prefs(c).edit().putString("now", j?.toString()).apply()
    fun lastNow(c: Context): JSONObject? = prefs(c).getString("now", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
}

class HttpError(val code: Int, msg: String) : Exception(msg)

class Api(private val key: String) {
    constructor(c: Context) : this(Cue.key(c) ?: throw HttpError(401, "not signed in"))

    fun get(path: String, action: String? = null): JSONObject = call("GET", path, action, null)
    fun post(path: String, action: String?, body: JSONObject): JSONObject = call("POST", path, action, body)
    fun push(action: String, body: JSONObject) = post("/push", action, body)

    private fun call(method: String, path: String, action: String?, body: JSONObject?): JSONObject {
        val q = buildString {
            append("key=").append(URLEncoder.encode(key, "UTF-8"))
            if (action != null) append("&action=").append(action)
        }
        val conn = URL("${Cue.BASE}$path?$q").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, "$code ${text.take(160)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

// One notification's worth of what the server pushes (lib/push.js nowContent / pingContent).
data class Ping(
    val title: String,
    val body: String,
    val ctx: String,
    val icon: String?,
    val url: String,
    val tag: String?,
    val check: JSONObject?,
    val start: JSONObject?,
    val skip: String?,
    val loud: Boolean,
    val prog: Pair<Int, Int>?,
    val ends: Long?,
    val raw: JSONObject,
) {
    val isNow get() = tag == Cue.NOW_TAG

    // The web notification puts the context on the body's first line; the app shows it in the
    // header instead, so drop it from the text.
    val text: String
        get() = if (ctx.isNotEmpty() && body.startsWith(ctx)) body.removePrefix(ctx).trimStart('\n') else body

    companion object {
        fun parse(j: JSONObject): Ping {
            val s = { k: String -> if (j.isNull(k)) null else j.optString(k).takeIf { it.isNotEmpty() } }
            val p = j.optJSONArray("prog")
            return Ping(
                title = s("title") ?: "Cue",
                body = s("body") ?: "",
                ctx = s("ctx") ?: "",
                icon = s("icon"),
                url = s("url") ?: "/",
                tag = s("tag"),
                check = j.optJSONObject("check"),
                start = j.optJSONObject("start"),
                skip = s("skip"),
                loud = j.optBoolean("renotify", false) && !j.optBoolean("silent", false),
                prog = if (p != null && p.length() == 2) p.optInt(0) to p.optInt(1) else null,
                ends = if (j.has("ends") && !j.isNull("ends")) j.optLong("ends") else null,
                raw = j,
            )
        }
    }
}
