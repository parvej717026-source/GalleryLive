package com.newsnest.gallerylive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Page(val id: String, val name: String, val token: String, val logoUrl: String)

object FacebookLive {
    private const val API = "https://graph.facebook.com/v19.0"

    private fun call(url: String, method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        if (method == "POST") {
            c.doOutput = true
            val body = params.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val json = JSONObject(s.bufferedReader().readText())
        if (json.has("error")) throw Exception(json.getJSONObject("error").optString("message"))
        return json
    }

    private fun logo(o: JSONObject) =
        o.optJSONObject("picture")?.optJSONObject("data")?.optString("url") ?: ""

    /** টোকেনের সব পেজ (নাম, আইডি, পেজ-টোকেন, লোগো) */
    fun pages(userToken: String): List<Page> {
        val f = URLEncoder.encode("id,name,access_token,picture.width(300).height(300){url}", "UTF-8")
        val arr = call("$API/me/accounts?fields=$f&limit=100&access_token=$userToken", "GET").optJSONArray("data")
        val list = mutableListOf<Page>()
        if (arr != null && arr.length() > 0) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Page(o.getString("id"), o.getString("name"), o.optString("access_token", userToken), logo(o)))
            }
        } else {
            // সরাসরি পেজ-টোকেন দিলে শুধু ওই পেজ আসবে
            val m = call("$API/me?fields=$f&access_token=$userToken", "GET")
            list.add(Page(m.getString("id"), m.getString("name"), userToken, logo(m)))
        }
        return list
    }

    fun bitmap(url: String): Bitmap? = try {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    /** returns Pair(liveVideoId, rtmpsUrl) */
    fun create(pageId: String, token: String, title: String): Pair<String, String> {
        val j = call("$API/$pageId/live_videos", "POST",
            mapOf("status" to "LIVE_NOW", "title" to title, "access_token" to token))
        val url = j.optString("secure_stream_url").ifEmpty { j.getString("stream_url") }
        return Pair(j.getString("id"), url)
    }

    fun end(liveId: String, token: String) {
        try { call("$API/$liveId", "POST", mapOf("end_live_video" to "true", "access_token" to token)) }
        catch (_: Exception) { }
    }
}
