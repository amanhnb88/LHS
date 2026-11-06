package id.lhspapua

import id.lhspapua.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

class SalesDashboardTest {
    private val today=LocalDate.parse("2026-11-07")

    private fun report(date:String,quantity:Long?,price:Long?,sales:String="MAIKEL",status:ReportStatus=ReportStatus.FINAL) =
        DailyReport(
            identity=Identity(date=date,sales=sales),status=status,finalizedAt="2026-11-07T05:35:00Z",
            products=listOf(Stock(),Stock(sales=listOf(Sale(quantity,price),Sale(),Sale())),Stock())
        )

    @Test fun sumsUniqueFinalsByActivityDateAndSnapshotPriceAcrossPeriods() {
        val early=report("2026-10-28",1,100)
        val first=report("2026-11-05",1000,10000)
        val second=report("2026-11-05",2,15000)
        val todayReport=report("2026-11-07",1,15000)
        val draft=report("2026-11-07",1000,10000,status=ReportStatus.DRAFT)
        val future=report("2026-11-08",1,15000)
        val rows=listOf(early,first,first,second,todayReport,draft,future)
        val week=SalesDashboard.summarize(rows,today,SalesPeriod.WEEK,null)
        assertEquals(7,week.days.size)
        assertEquals(BigInteger.valueOf(15000),week.today)
        assertEquals(BigInteger.valueOf(10045000),week.month)
        assertEquals(3,week.finalCount)
        assertEquals(BigInteger.valueOf(10030000),week.days.first { it.date.dayOfMonth==5 }.total)
        assertEquals(listOf(null,BigInteger.valueOf(10045000),null),week.products)
        val rolling=SalesDashboard.summarize(rows,today,SalesPeriod.MONTH_ROLLING,null)
        assertEquals(30,rolling.days.size)
        assertEquals(4,rolling.finalCount)
        assertEquals(BigInteger.valueOf(10045100),rolling.products[1])
        val month=SalesDashboard.summarize(rows,today,SalesPeriod.MONTH,null)
        assertEquals(today.withDayOfMonth(1),month.days.first().date)
        assertEquals(3,month.finalCount)
        val reopened=SalesDashboard.summarize(rows.map { if(it.id==first.id)it.copy(status=ReportStatus.DRAFT) else it },today,SalesPeriod.MONTH,null)
        assertEquals(BigInteger.valueOf(45000),reopened.month)
    }

    @Test fun distinguishesZeroAndMissingTotalsAndCanSeparateSales() {
        val date=today.toString()
        val zero=report(date,0,15000,"MAIKEL")
        val bad=report(date,5,null,"MAIKEL")
        val missing=report(date,null,15000,"MAIKEL")
        val other=report(date,10,500,"YOHANES")
        val rows=listOf(zero,bad,missing,other)
        val mine=SalesDashboard.summarize(rows,today,SalesPeriod.WEEK,"MAIKEL")
        assertEquals(BigInteger.ZERO,mine.today)
        assertEquals("Rp0",SalesDashboard.money(mine.today))
        assertEquals(2,mine.excludedCount)
        assertEquals(setOf("MAIKEL","YOHANES"),mine.salesNames.keys)
        assertNull(mine.days.first().total)
        assertEquals(BigInteger.valueOf(5000),SalesDashboard.summarize(rows,today,SalesPeriod.WEEK,"YOHANES").today)
        val empty=SalesDashboard.summarize(listOf(zero.copy(status=ReportStatus.DRAFT)),today,SalesPeriod.WEEK,null)
        assertFalse(empty.hasFinals)
        assertNull(empty.today)
        assertTrue(empty.days.all { it.total==null })
    }

    @Test fun witIgnoresDeviceTimezoneAndUnknownTimesStayUnknown() {
        val instant=Instant.parse("2026-11-06T15:35:00Z")
        assertEquals(today,ReportTime.date(instant))
        assertEquals("07/11/2026 · 00.35 WIT",ReportTime.display(instant.toString()))
        assertEquals("Waktu tidak tercatat",ReportTime.display(null))
        assertEquals("Waktu tidak tercatat",ReportTime.display("07/11/2026 09:15"))
    }
}
