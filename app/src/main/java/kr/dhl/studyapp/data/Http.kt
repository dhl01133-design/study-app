package kr.dhl.studyapp.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server answered with an error status. Other IOExceptions mean the request never got through (e.g. offline). */
class HttpException(val code: Int, body: String) : IOException("HTTP $code ${body.take(160)}")

object Http {
    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }
}

internal fun JsonObject.str(key: String): String = strOrNull(key) ?: ""
internal fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
