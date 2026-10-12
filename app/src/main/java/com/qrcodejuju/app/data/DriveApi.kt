package com.qrcodejuju.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class DriveApiException(message: String) : IOException(message)

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val OCTET_STREAM = "application/octet-stream".toMediaType()
private val MULTIPART_RELATED = "multipart/related".toMediaType()

/**
 * A minimal Drive v3 REST client -- just enough to find-or-create one folder and one file
 * in it, and read/replace that file's bytes. Calls the API directly over HTTP rather than
 * pulling in Google's full API client libraries, which are much heavier than this needs.
 * All methods are blocking; call from a background dispatcher.
 */
class DriveApi(private val accessToken: String) {
    private val http = OkHttpClient()
    private val filesUrl = "https://www.googleapis.com/drive/v3/files"
    private val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files"

    private fun Request.Builder.auth() = addHeader("Authorization", "Bearer $accessToken")

    private fun run(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DriveApiException("Drive API error ${resp.code}: $body")
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    /** Finds a non-trashed file/folder by exact name under [parentId] ("root" for My Drive), or null. */
    private fun find(name: String, mimeType: String?, parentId: String): String? {
        val safeName = name.replace("'", "\\'")
        val q = buildString {
            append("name = '$safeName' and '$parentId' in parents and trashed = false")
            if (mimeType != null) append(" and mimeType = '$mimeType'")
        }
        val url = "$filesUrl?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)&spaces=drive"
        val files = run(Request.Builder().url(url).auth().build()).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    /** Returns the "JujuCodes" folder's id under My Drive, creating it if it doesn't exist yet. */
    fun findOrCreateFolder(name: String): String {
        find(name, "application/vnd.google-apps.folder", "root")?.let { return it }
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray().put("root"))
        }
        val result = run(
            Request.Builder().url(filesUrl).auth().post(metadata.toString().toRequestBody(JSON_MEDIA)).build()
        )
        return result.getString("id")
    }

    /** Returns the file's id and current modifiedTime, or null if it doesn't exist in [folderId] yet. */
    fun findFile(name: String, folderId: String): Pair<String, String>? {
        val id = find(name, null, folderId) ?: return null
        val meta = run(Request.Builder().url("$filesUrl/$id?fields=id,modifiedTime").auth().build())
        return id to meta.getString("modifiedTime")
    }

    /** Creates [name] in [folderId] with [bytes] as its content; returns its id and modifiedTime. */
    fun createFile(name: String, folderId: String, bytes: ByteArray): Pair<String, String> {
        val metadata = JSONObject().apply { put("name", name); put("parents", JSONArray().put(folderId)) }
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(MultipartBody.Part.create(metadata.toString().toRequestBody(JSON_MEDIA)))
            .addPart(MultipartBody.Part.create(bytes.toRequestBody(OCTET_STREAM)))
            .build()
        val result = run(
            Request.Builder().url("$uploadUrl?uploadType=multipart&fields=id,modifiedTime").auth().post(body).build()
        )
        return result.getString("id") to result.getString("modifiedTime")
    }

    /** Overwrites [fileId]'s content with [bytes]; returns the new modifiedTime. */
    fun updateFile(fileId: String, bytes: ByteArray): String {
        val result = run(
            Request.Builder().url("$uploadUrl/$fileId?uploadType=media&fields=modifiedTime")
                .auth().patch(bytes.toRequestBody(OCTET_STREAM)).build()
        )
        return result.getString("modifiedTime")
    }

    fun downloadFile(fileId: String): ByteArray {
        val request = Request.Builder().url("$filesUrl/$fileId?alt=media").auth().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw DriveApiException("Drive download error ${resp.code}")
            return resp.body?.bytes() ?: throw DriveApiException("Empty response body")
        }
    }

    fun fileModifiedTime(fileId: String): String =
        run(Request.Builder().url("$filesUrl/$fileId?fields=modifiedTime").auth().build()).getString("modifiedTime")
}
