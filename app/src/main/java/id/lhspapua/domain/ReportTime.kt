package id.lhspapua.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object ReportTime {
    private val zone=ZoneId.of("Asia/Jayapura")
    private val format=DateTimeFormatter.ofPattern("dd/MM/uuuu '·' HH.mm 'WIT'",Locale.forLanguageTag("id-ID")).withZone(zone)

    fun date(instant:Instant):LocalDate = instant.atZone(zone).toLocalDate()
    fun parse(value:String?):Instant? = try {
        value?.let(Instant::parse)
    } catch(_:DateTimeParseException) {
        null
    }
    fun display(value:String?):String = parse(value)?.let(format::format) ?: "Waktu tidak tercatat"
}
