package kr.dhl.studyapp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}

private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonObject.strOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Todoist API v1 (personal API token). */
class Todoist(private val token: String) {
    private val base = "https://api.todoist.com/api/v1"
    private val auth get() = mapOf("Authorization" to "Bearer $token")

    private fun pages(path: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val sep = if (path.contains('?')) '&' else '?'
            val url = base + path + sep + "limit=200" +
                (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val root = Json.parseToJsonElement(Http.request(url, headers = auth)).jsonObject
            root["results"]?.jsonArray?.forEach { out.add(it.jsonObject) }
            cursor = (root["next_cursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null && out.size < 5000)
        return out
    }

    fun tasks(): List<TaskItem> {
        val projects = pages("/projects").associate { it.str("id") to it.str("name") }
        return pages("/tasks").map { o ->
            val due = o["due"] as? JsonObject
            val raw = due?.let { it.strOrNull("datetime") ?: it.strOrNull("date") }
            val parsed = raw?.let { parseDue(it) }
            TaskItem(
                id = o.str("id"),
                content = o.str("content"),
                description = o.str("description"),
                projectId = o.str("project_id"),
                projectName = projects[o.str("project_id")] ?: "",
                labels = o["labels"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                priority = (o["priority"] as? JsonPrimitive)?.intOrNull ?: 1,
                dueDate = parsed?.first,
                dueTime = parsed?.second,
                recurring = (due?.get("is_recurring") as? JsonPrimitive)?.booleanOrNull ?: false,
                order = (o["child_order"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
    }

    /** Moves a task to [date], keeping [time] if it had one. null date = "no date". */
    fun setDue(id: String, date: LocalDate?, time: LocalTime?) {
        val body = buildJsonObject {
            when {
                date == null -> put("due_string", "no date")
                time == null -> put("due_date", date.toString())
                else -> put(
                    "due_datetime",
                    date.atTime(time).atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")),
                )
            }
        }
        Http.request("$base/tasks/$id", "POST", body.toString(), auth)
    }

    fun close(id: String) {
        Http.request("$base/tasks/$id/close", "POST", null, auth)
    }

    fun reopen(id: String) {
        Http.request("$base/tasks/$id/reopen", "POST", null, auth)
    }

    companion object {
        /** "2026-11-20", "2026-11-20T23:59:00" (floating) or "2026-11-20T14:59:00Z" (fixed). */
        fun parseDue(raw: String): Pair<String, String?> {
            if (!raw.contains('T')) return raw.take(10) to null
            val local: LocalDateTime = runCatching {
                OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            }.getOrElse { LocalDateTime.parse(raw.take(19)) }
            return local.toLocalDate().toString() to "%02d:%02d".format(local.hour, local.minute)
        }
    }
}

object Markets {
    private val NAMES = listOf("nasdaq" to "나스닥", "sp500" to "S&P500", "qld" to "QLD", "dxy" to "달러인덱스")

    fun fetch(url: String): Pair<List<MarketItem>, String?> {
        val root = Json.parseToJsonElement(Http.request(url)).jsonObject
        val data = root["data"] as? JsonObject ?: return emptyList<MarketItem>() to null
        var session: String? = null
        val items = NAMES.map { (k, n) ->
            val o = data[k] as? JsonObject
            if (session == null) session = o?.strOrNull("session_date")
            MarketItem(
                n,
                (o?.get("close") as? JsonPrimitive)?.doubleOrNull,
                (o?.get("change_pct") as? JsonPrimitive)?.doubleOrNull,
            )
        }
        val label = session?.let { s -> runCatching { LocalDate.parse(s).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrNull() }
        return items to label
    }
}
