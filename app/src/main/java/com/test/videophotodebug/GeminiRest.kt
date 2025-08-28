package com.test.videophotodebug

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class UploadedFile(val name: String, val uri: String, val mimeType: String)

object GeminiRest {

    private const val API_KEY = "AIzaSyCdLsSiqaJrE5BhrvNubZ5nBronj89uKQo"

    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // ---- Public API ----

    suspend fun uploadVideo(context: Context, uri: Uri): UploadedFile =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val meta = getFileMeta(cr, uri)
            val uploadUrl = startResumable(meta.displayName, meta.mimeType, meta.size)
            finalizeUpload(uploadUrl, cr.openInputStream(uri) ?: error("Cannot open stream"), meta)
        }

    // --- Video + prompt (fixed) ---
    suspend fun askGeminiWithVideo(uploaded: UploadedFile, userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val model = "gemini-2.5-flash"
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$API_KEY"

            val systemInstruction = JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text",
                    """
                You are a cautious repair assistant. Start with safety checks.
                Use visible evidence from the video. Output concise numbered steps.
                Ask for make/model if missing. Warn before risky actions.
                """.trimIndent()
                ))
            )

            // Single USER message with file_data + text parts in one array
            val parts = JSONArray()
                .put(JSONObject().put("file_data", JSONObject()
                    .put("file_uri", uploaded.uri)
                    .put("mime_type", uploaded.mimeType)
                ))
                .put(JSONObject().put("text",
                    userPrompt.ifBlank { "Identify likely cause and provide concise numbered fixes." }
                ))

            val user = JSONObject().put("role", "user").put("parts", parts)

            val body = JSONObject()
                .put("contents", JSONArray().put(user))
                .put("system_instruction", systemInstruction)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url(url).post(body).build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "Gemini 400: ${resp.code} ${resp.message} • $raw" }
                extractText(raw)
            }
        }


    // ---- Files API (resumable upload) ----

    private fun startResumable(displayName: String, mime: String, size: Long): String {
        val url = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        val body = JSONObject()
            .put("file", JSONObject().put("display_name", displayName))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", API_KEY)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", size.toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mime)
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Upload start failed: ${resp.code} ${resp.message}" }
            return resp.header("X-Goog-Upload-Url")
                ?: error("No resumable upload URL returned")
        }
    }

    private fun finalizeUpload(
        uploadUrl: String,
        input: InputStream,
        meta: FileMeta
    ): UploadedFile {
        val streamBody = object : RequestBody() {
            override fun contentType() = meta.mimeType.toMediaType()
            override fun contentLength(): Long = if (meta.size >= 0) meta.size else -1
            override fun writeTo(sink: BufferedSink) {
                input.use { ins ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val r = ins.read(buf)
                        if (r == -1) break
                        sink.write(buf, 0, r)
                    }
                }
            }
        }

        val req = Request.Builder()
            .url(uploadUrl)
            .addHeader("x-goog-api-key", API_KEY)
            .addHeader("Content-Length", meta.size.toString())
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(streamBody)
            .build()

        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Upload finalize failed: ${resp.code} ${resp.message}" }
            val json = JSONObject(resp.body?.string() ?: error("Empty finalize response"))
            val file = json.getJSONObject("file")
            return UploadedFile(
                name = file.getString("name"),
                uri = file.getString("uri"),
                mimeType = file.optString("mimeType", meta.mimeType)
            )
        }
    }

    // ---- GenerateContent (prompt with video reference) ----
// --- Text-only generateContent ---
// --- Text-only generateContent (fixed) ---
    suspend fun askGeminiTextOnly(userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val model = "gemini-2.5-flash"
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$API_KEY"

            val systemInstruction = JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text",
                    """
                You are a careful step-by-step troubleshooting assistant.
                Return concise, numbered steps. If a step is risky, warn clearly first.
                """.trimIndent()
                ))
            )

            // contents[] should contain USER messages only
            val user = JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(
                    JSONObject().put("text",
                        userPrompt.ifBlank { "Diagnose the issue and provide numbered steps." })
                ))

            val body = JSONObject()
                .put("contents", JSONArray().put(user))
                .put("system_instruction", systemInstruction)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 512))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url(url).post(body).build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "Gemini 400: ${resp.code} ${resp.message} • $raw" }
                extractText(raw)
            }
        }

    private fun extractText(rawJson: String): String {
        val json = JSONObject(rawJson)
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val out = buildString {
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (t.isNotBlank()) append(t)
                }
            }
        }
        return out.ifBlank { "No response." }
    }

    private fun generateContent(
        model: String,
        systemInstruction: String,
        videoUri: String,
        videoMime: String,
        prompt: String
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$API_KEY"

        val contents = JSONArray().apply {
            put(JSONObject().put("role", "system").put("parts",
                JSONArray().put(JSONObject().put("text", systemInstruction))
            ))
            put(JSONObject().put("role", "user").put("parts", JSONArray().apply {
                put(JSONObject().put("file_data", JSONObject()
                    .put("file_uri", videoUri)
                    .put("mime_type", videoMime)
                ))
                put(JSONObject().put("text", prompt))
            }))
        }

        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url).post(body).build()

        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Gemini call failed: ${resp.code} ${resp.message}" }
            val json = JSONObject(resp.body?.string() ?: "{}")
            val sb = StringBuilder()
            val parts = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (t.isNotBlank()) sb.append(t)
                }
            }
            return sb.toString().ifBlank { "No response." }
        }
    }

    // ---- Helpers ----

    private data class FileMeta(val displayName: String, val mimeType: String, val size: Long)

    private fun getFileMeta(cr: ContentResolver, uri: Uri): FileMeta {
        var name = "video.mp4"
        var size = -1L
        val mime = cr.getType(uri) ?: "video/mp4"
        val c: Cursor? = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        c?.use {
            if (it.moveToFirst()) {
                val n = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = it.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = it.getString(n)
                if (s >= 0) size = it.getLong(s)
            }
        }
        return FileMeta(name, mime, size)
    }
}