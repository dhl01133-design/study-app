package kr.dhl.studyapp

import android.content.Context
import java.time.LocalTime

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var todoistToken: String
        get() = p.getString("todoist", "") ?: ""
        set(v) = p.edit().putString("todoist", v.trim()).apply()

    /** Google Calendar "secret address in iCal format", one per line. */
    var icsText: String
        get() = p.getString("ics", "") ?: ""
        set(v) = p.edit().putString("ics", v.trim()).apply()

    val icsUrls: List<String>
        get() = icsText.lines().map { it.trim() }.filter { it.startsWith("http") }

    var marketUrl: String
        get() = p.getString("market", DEFAULT_MARKET) ?: DEFAULT_MARKET
        set(v) = p.edit().putString("market", v.trim()).apply()

    var briefingText: String
        get() = p.getString("brief", "08:17") ?: "08:17"
        set(v) = p.edit().putString("brief", v.trim()).apply()

    val briefingTime: LocalTime
        get() = runCatching { LocalTime.parse(briefingText.padStart(5, '0')) }.getOrDefault(LocalTime.of(8, 17))

    var offsetsText: String
        get() = p.getString("offsets", "6,1") ?: "6,1"
        set(v) = p.edit().putString("offsets", v.trim()).apply()

    val alarmOffsets: List<Int>
        get() = offsetsText.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..72 }.distinct()
            .ifEmpty { listOf(6, 1) }

    var onlyIcampus: Boolean
        get() = p.getBoolean("only_icampus", false)
        set(v) = p.edit().putBoolean("only_icampus", v).apply()

    var alarmCodes: Set<String>
        get() = p.getStringSet("alarm_codes", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("alarm_codes", v).apply()

    companion object {
        const val DEFAULT_MARKET = "https://raw.githubusercontent.com/dhl01133-design/market-closes/main/markets.json"
    }
}
