package tv.own.owntv.diplocine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object DiploSite {
    const val origin = "https://diplocine.vercel.app"
    private const val pairing = "/api/v1/tv/pairings/"
    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().followRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    val codeFormat = Regex("[A-HJ-NP-Z2-9]{8}")

    suspend fun startPairing(previousSecret: String? = null): JSONObject {
        val data = JSONObject()
        previousSecret?.let { data.put("replaceDeviceSecret", it) }
        return JSONObject(request(pairing + "start", data))
    }

    suspend fun pollPairing(secret: String): JSONObject =
        JSONObject(request(pairing + "poll", JSONObject().put("deviceSecret", secret)))

    suspend fun validate(token: String): Boolean = try {
        request("/api/v1/mobile/me", token = token)
        true
    } catch (error: HttpFailure) {
        if (error.status == 401) false else throw error
    }

    suspend fun logout(token: String) {
        try {
            request("/api/v1/mobile/logout", JSONObject(), token)
        } catch (error: HttpFailure) {
            if (error.status != 401) throw error
        }
    }

    fun devicePage(code: String) = "$origin/dispositivos?pair=$code"

    fun isFirstParty(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("diplocine.vercel.app", ignoreCase = true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)

    suspend fun channelPlaylist(context: Context): File = withContext(Dispatchers.IO) {
        val entries = JSONArray(request("/channels.json"))
        val directory = File(context.filesDir, "diplo-playlists").apply { mkdirs() }
        val playlist = File(directory, "canais.m3u")
        val temporary = File.createTempFile("canais-", ".m3u", directory)
        try {
            var written = 0
            temporary.bufferedWriter().use { output ->
                output.appendLine("#EXTM3U")
                for (index in 0 until entries.length()) {
                    val channel = entries.optJSONObject(index) ?: continue
                    if (channel.optString("kind") !in setOf("hls", "mp4")) continue
                    val stream = secureUrl(channel.optString("url")) ?: continue
                    val id = attribute(channel.optString("id"))
                    val name = attribute(channel.optString("name")).ifBlank { continue }
                    val category = attribute(channel.optString("group"))
                    val logo = secureUrl(channel.optString("logo")).orEmpty()
                    output.appendLine("#EXTINF:-1 tvg-type=\"live\" tvg-id=\"$id\" tvg-logo=\"$logo\" group-title=\"$category\",$name")
                    output.appendLine(stream)
                    written++
                }
            }
            if (written == 0) throw IOException("O site não retornou canais compatíveis.")
            if (!temporary.renameTo(playlist)) throw IOException("Não foi possível salvar a lista de canais.")
            playlist
        } finally {
            temporary.delete()
        }
    }

    suspend fun metadata(path: String): JSONObject =
        JSONObject(request("/api/v1/tmdb/" + path))

    suspend fun playback(token: String, type: String, id: Int, season: Int, episode: Int): String {
        val data = JSONObject(request(
            "/api/player/source?type=$type&id=$id&season=$season&episode=$episode", token = token,
        ))
        return secureUrl(data.getString("url")) ?: throw IOException("Fonte de vídeo incompatível.")
    }

    private fun attribute(value: String) = value.replace(Regex("[\"\\r\\n]"), " ").trim()

    private fun secureUrl(value: String): String? {
        if (value.any { it.isISOControl() || it == '"' }) return null
        val uri = Uri.parse(value)
        return value.takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }

    private suspend fun request(path: String, data: JSONObject? = null, token: String? = null): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(origin + path)
                .header("User-Agent", "DiploCineNative/${BuildConfig.VERSION_NAME}")
                .header("X-DiploCine-Request", "1")
                .header("Accept", "application/json")
            if (data != null) request.post(data.toString().toRequestBody(json))
            if (token != null) request.header("Authorization", "Bearer $token")
            client.newCall(request.build()).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw HttpFailure(response.code, message?.takeIf { it.isNotBlank() }
                        ?: "O DiploCine retornou HTTP ${response.code}.")
                }
                body
            }
        }

    class HttpFailure(val status: Int, message: String) : IOException(message)
}
