package com.comadrez.mailapp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Acct(val id: String, val address: String, val password: String, val created: Long = 0L)

data class Msg(
    val id: String,
    val from: String,
    val subject: String,
    val intro: String,
    val seen: Boolean,
    val date: Long
)

data class Att(val id: String, val name: String, val type: String, val size: Long, val url: String)

data class Detail(val text: String, val attachments: List<Att>)

/** Thin client for the free Mail.tm API (https://docs.mail.tm). */
object MailTm {
    private const val BASE = "https://api.mail.tm"
    private val client = OkHttpClient()
    private val sseClient = client.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json".toMediaType()
    private val MERGE_TYPE = "application/merge-patch+json".toMediaType()
    private val rnd = SecureRandom()

    class ApiError(val code: Int, message: String) : Exception(message)

    fun randomString(n: Int, chars: String = "abcdefghijklmnopqrstuvwxyz0123456789"): String =
        (1..n).map { chars[rnd.nextInt(chars.length)] }.joinToString("")

    private fun call(method: String, path: String, body: String? = null, token: String? = null): String {
        val rb = Request.Builder().url(BASE + path)
        if (token != null) rb.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            "PATCH" -> rb.patch((body ?: "{}").toRequestBody(MERGE_TYPE))
            else -> rb.post((body ?: "{}").toRequestBody(JSON_TYPE))
        }
        client.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw ApiError(
                    r.code,
                    when (r.code) {
                        401 -> "Session expired"
                        404 -> "Not found"
                        422 -> "That address is invalid or already taken"
                        429 -> "Too many requests, wait a few seconds"
                        else -> "Server error (${r.code})"
                    }
                )
            }
            return text
        }
    }

    private fun members(text: String): JSONArray {
        val t = text.trim()
        return if (t.startsWith("[")) JSONArray(t)
        else JSONObject(t).optJSONArray("hydra:member") ?: JSONArray()
    }

    fun domains(): List<String> {
        val arr = members(call("GET", "/domains"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optBoolean("isActive", true) && !it.optBoolean("isPrivate", false) }
            .map { it.getString("domain") }
    }

    fun createAccount(address: String, password: String): String {
        val b = JSONObject().put("address", address).put("password", password).toString()
        return JSONObject(call("POST", "/accounts", b)).getString("id")
    }

    fun token(address: String, password: String): String {
        val b = JSONObject().put("address", address).put("password", password).toString()
        return JSONObject(call("POST", "/token", b)).getString("token")
    }

    /** Returns (id, address, creation time in ms; 0 if unknown). */
    fun me(token: String): Triple<String, String, Long> {
        val o = JSONObject(call("GET", "/me", token = token))
        return Triple(o.getString("id"), o.getString("address"), parseDate(o.optString("createdAt")))
    }

    fun created(token: String): Long = me(token).third

    fun messages(token: String): List<Msg> {
        val arr = members(call("GET", "/messages", token = token))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val f = o.optJSONObject("from")
            val from = f?.optString("name").orEmpty()
                .ifBlank { f?.optString("address").orEmpty() }
                .ifBlank { "(unknown)" }
            Msg(
                id = o.getString("id"),
                from = from,
                subject = o.optString("subject").ifBlank { "(no subject)" },
                intro = o.optString("intro"),
                seen = o.optBoolean("seen"),
                date = parseDate(o.optString("createdAt"))
            )
        }.sortedByDescending { it.date }
    }

    fun detail(token: String, id: String): Detail {
        val o = JSONObject(call("GET", "/messages/$id", token = token))
        val text = o.optString("text")
        val body = if (text.isNotBlank()) text.trim() else {
            val html = o.optJSONArray("html")
            val joined = if (html != null) (0 until html.length()).joinToString("\n") { html.optString(it) } else ""
            stripHtml(joined).ifBlank { "(empty message)" }
        }
        val arr = o.optJSONArray("attachments") ?: JSONArray()
        val atts = (0 until arr.length()).map { arr.getJSONObject(it) }.map { a ->
            val aid = a.optString("id")
            Att(
                id = aid,
                name = a.optString("filename").ifBlank { "attachment" },
                type = a.optString("contentType"),
                size = a.optLong("size"),
                url = a.optString("downloadUrl").removePrefix(BASE).ifBlank { "/messages/$id/attachment/$aid" }
            )
        }
        return Detail(body, atts)
    }

    /** Streams an attachment into [out]. [path] is the API path, e.g. /messages/ID/attachment/ID. */
    fun download(token: String, path: String, out: java.io.OutputStream) {
        val req = Request.Builder().url(BASE + path).header("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiError(r.code, if (r.code == 401) "Session expired" else "Download failed (${r.code})")
            val src = r.body?.byteStream() ?: throw ApiError(0, "Empty download")
            src.use { it.copyTo(out) }
        }
    }

    /**
     * Live connection (Mercure server-sent events). Returns when the connection closes and throws if it fails.
     * [ping] is called for every event; we only use it as "something changed", then reload the list.
     */
    suspend fun listen(token: String, accountId: String, opened: () -> Unit = {}, ping: () -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val req = Request.Builder()
                .url("https://mercure.mail.tm/.well-known/mercure?topic=/accounts/$accountId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .build()
            val es = EventSources.createFactory(sseClient).newEventSource(req, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) { opened() }
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { ping() }
                override fun onClosed(eventSource: EventSource) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t ?: IOException("Live connection failed (${response?.code})"))
                }
            })
            cont.invokeOnCancellation { es.cancel() }
        }

    fun markSeen(token: String, id: String) { call("PATCH", "/messages/$id", token = token) }

    fun deleteMessage(token: String, id: String) { call("DELETE", "/messages/$id", token = token) }

    fun deleteAccount(token: String, id: String) { call("DELETE", "/accounts/$id", token = token) }

    private fun parseDate(s: String): Long = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(s.take(19))?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(style|script).*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
