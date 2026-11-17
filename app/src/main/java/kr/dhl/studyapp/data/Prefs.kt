package kr.dhl.studyapp.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var todoistToken by string("todoist", "", trim = true)
    var icsText by string("ics", "", trim = true)              // one iCal URL per line
    var marketUrl by string("market", DEFAULT_MARKET, trim = true)
    var briefingText by string("brief", "08:17", trim = true)
    var offsetsText by string("offsets", "6,1", trim = true)
    var themeMode by string("theme", "system")                 // system | light | dark
    var langMode by string("lang", "system")                   // system | ko | en
    var onlyIcampus by boolean("only_icampus", false)

    var alarmCodes: Set<String>
        get() = p.getStringSet("alarm_codes", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("alarm_codes", v).apply()

    val icsUrls: List<String>
        get() = icsText.lines().map { it.trim() }.filter { it.startsWith("http") }

    val briefingTime: LocalTime
        get() = runCatching { LocalTime.parse(briefingText.padStart(5, '0')) }.getOrDefault(LocalTime.of(8, 17))

    /** Hours before a deadline at which alarms ring. */
    val alarmOffsets: List<Int>
        get() = offsetsText.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..72 }.distinct().ifEmpty { listOf(6, 1) }

    private fun string(key: String, default: String, trim: Boolean = false) = pref(
        { getString(key, default) ?: default },
        { putString(key, if (trim) it.trim() else it) },
    )

    private fun boolean(key: String, default: Boolean) = pref({ getBoolean(key, default) }, { putBoolean(key, it) })

    private fun <T> pref(read: SharedPreferences.() -> T, write: SharedPreferences.Editor.(T) -> Unit) =
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = p.read()
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = p.edit().apply { write(value) }.apply()
        }

    companion object {
        const val DEFAULT_MARKET = "https://raw.githubusercontent.com/dhl01133-design/market-closes/main/markets.json"
    }
}
