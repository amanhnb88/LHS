package id.lhspapua.domain

import java.math.BigInteger
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

enum class SalesPeriod(val label:String) {
    WEEK("7 Hari"), MONTH_ROLLING("30 Hari"), MONTH("Bulan Ini");

    fun start(today:LocalDate):LocalDate = when(this) {
        WEEK -> today.minusDays(6)
        MONTH_ROLLING -> today.minusDays(29)
        MONTH -> today.withDayOfMonth(1)
    }
}

data class SalesPoint(val date:LocalDate,val total:BigInteger?)
data class SalesDashboard(
    val today:BigInteger?,
    val month:BigInteger?,
    val finalCount:Int,
    val excludedCount:Int,
    val days:List<SalesPoint>,
    val products:List<BigInteger?>,
    val salesNames:Map<String,String>,
    val hasFinals:Boolean
) {
    companion object {
        fun summarize(reports:List<DailyReport>,today:LocalDate,period:SalesPeriod,salesKey:String?):SalesDashboard {
            val finals=reports.filter { it.status==ReportStatus.FINAL }.distinctBy { it.id }
            val names=finals.associate { report ->
                keyFor(report.identity.sales) to report.identity.sales.trim().ifBlank { "Sales belum diisi" }
            }.toSortedMap()
            val monthStart=today.withDayOfMonth(1)
            val periodStart=period.start(today)
            val earliest=minOf(periodStart,monthStart)
            val matching=finals.filter { salesKey==null || keyFor(it.identity.sales)==salesKey }
            val entries=matching.mapNotNull { report ->
                val date=parseDate(report.identity.date) ?: return@mapNotNull null
                if(date !in earliest..today)return@mapNotNull null
                val totals=runCatching {
                    Calculation.total(report)?.let { total ->
                        total to report.products.indices.map { Calculation.productTotal(report,it) }
                    }
                }.getOrNull()
                Entry(date,totals?.first,totals?.second)
            }
            val selected=entries.filter { it.date>=periodStart }
            val byDate=selected.groupBy { it.date }
            val days=generateSequence(periodStart) { if(it<today)it.plusDays(1) else null }.map { date ->
                SalesPoint(date,sum(byDate[date].orEmpty().map { it.total }))
            }.toList()
            return SalesDashboard(
                today=sum(entries.filter { it.date==today }.map { it.total }),
                month=sum(entries.filter { it.date>=monthStart }.map { it.total }),
                finalCount=selected.size,
                excludedCount=entries.count { it.total==null }+matching.count { parseDate(it.identity.date)==null },
                days=days,
                products=Product.entries.indices.map { index -> sum(selected.map { it.products?.getOrNull(index) }) },
                salesNames=names,
                hasFinals=matching.isNotEmpty()
            )
        }

        private data class Entry(val date:LocalDate,val total:Long?,val products:List<Long?>?)
        private fun parseDate(value:String):LocalDate? = try {
            LocalDate.parse(value)
        } catch(_:DateTimeParseException) {
            null
        }
        private fun keyFor(value:String) = value.trim().uppercase(Locale.ROOT)
        private fun sum(values:List<Long?>):BigInteger? =
            values.filterNotNull().map(BigInteger::valueOf).reduceOrNull(BigInteger::add)

        fun money(value:BigInteger?):String = value?.let {
            "Rp"+NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).format(it)
        } ?: "—"
    }
}
