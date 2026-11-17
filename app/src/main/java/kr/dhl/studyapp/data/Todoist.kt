package kr.dhl.studyapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Todoist API v1 with a personal API token. */
class Todoist(private val token: String) {
    private val base = "https://api.todoist.com/api/v1"
    private val auth get() = mapOf("Authorization" to "Bearer $token")

    fun tasks(): List<TaskItem> {
        val projects = pages("/projects").associate { it.str("id") to it.str("name") }
        return pages("/tasks").map { o -> o.toTask(projects) }
    }

    /** Moves a task to [date], keeping [time] if it has one. A null date clears it. */
    fun setDue(id: String, date: LocalDate?, time: LocalTime?) {
        val body = buildJsonObject {
            when {
                date == null -> put("due_string", "no date")
                time == null -> put("due_date", date.toString())
                else -> put("due_datetime", utc(date.atTime(time)))
            }
        }
        post("/tasks/$id", body.toString())
    }

    fun close(id: String) = post("/tasks/$id/close")

    fun reopen(id: String) = post("/tasks/$id/reopen")

    private fun post(path: String, body: String? = null) {
        Http.request(base + path, "POST", body, auth)
    }

    private fun pages(path: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val url = "$base$path?limit=200" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val root = Json.parseToJsonElement(Http.request(url, headers = auth)).jsonObject
            root["results"]?.jsonArray?.forEach { out.add(it.jsonObject) }
            cursor = (root["next_cursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null && out.size < 5000)
        return out
    }

    private fun JsonObject.toTask(projects: Map<String, String>): TaskItem {
        val due = this["due"] as? JsonObject
        val (date, time) = due?.let { it.strOrNull("datetime") ?: it.strOrNull("date") }?.let { parseDue(it) } ?: (null to null)
        return TaskItem(
            id = str("id"),
            content = str("content"),
            description = str("description"),
            projectId = str("project_id"),
            projectName = projects[str("project_id")] ?: "",
            labels = this["labels"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            priority = (this["priority"] as? JsonPrimitive)?.intOrNull ?: 1,
            dueDate = date,
            dueTime = time,
            recurring = (due?.get("is_recurring") as? JsonPrimitive)?.booleanOrNull ?: false,
            order = (this["child_order"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    companion object {
        private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        private fun utc(local: LocalDateTime): String =
            local.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(UTC_FORMAT)

        /** "2026-11-20", "2026-11-20T23:59:00" (floating) or "2026-11-20T14:59:00Z" (fixed) → local date and HH:mm. */
        fun parseDue(raw: String): Pair<String, String?> {
            if (!raw.contains('T')) return raw.take(10) to null
            val local = runCatching {
                OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            }.getOrElse { LocalDateTime.parse(raw.take(19)) }
            return local.toLocalDate().toString() to "%02d:%02d".format(local.hour, local.minute)
        }
    }
}
