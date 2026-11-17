package kr.dhl.studyapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

/** US market closes from the market-closes repo's markets.json. */
object Markets {
    private val SYMBOLS = listOf("nasdaq" to "나스닥", "sp500" to "S&P500", "qld" to "QLD", "dxy" to "달러인덱스")

    /** Returns the four markets and the session date as "M/d". */
    fun fetch(url: String): Pair<List<MarketItem>, String?> {
        val root = Json.parseToJsonElement(Http.request(url)).jsonObject
        val data = root["data"] as? JsonObject ?: return emptyList<MarketItem>() to null
        val items = SYMBOLS.map { (key, name) ->
            val o = data[key] as? JsonObject
            MarketItem(name, o?.number("close"), o?.number("change_pct"))
        }
        val session = SYMBOLS.firstNotNullOfOrNull { (data[it.first] as? JsonObject)?.strOrNull("session_date") }
            ?.let { s -> runCatching { LocalDate.parse(s).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrNull() }
        return items to session
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
