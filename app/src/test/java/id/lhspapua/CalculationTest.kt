package id.lhspapua

import id.lhspapua.domain.*
import org.junit.Assert.*
import org.junit.Test

class CalculationTest {
    @Test fun knownSalesAndBalance() {
        val stock=Stock(delivery=100,sample=2,bonus=1,sales=listOf(Sale(10,10000),Sale(5,11000),Sale(2,12000)))
        val r=DailyReport(products=listOf(stock,Stock(),Stock()),rules=Rules(acknowledged=true),finance=Finance(50000,20000,5000,100000,10000))
        assertEquals(17L,Calculation.sold(stock)); assertEquals(179000L,Calculation.productTotal(stock))
        assertEquals(80L,Calculation.tg(stock,r.rules)); assertEquals(179000L,Calculation.total(r)); assertEquals(104000L,Calculation.balance(r))
        assertTrue(Calculation.errors(r).isEmpty())
    }
    @Test fun blankAndZeroRemainDifferent() {
        assertNull(Calculation.sold(Stock())); assertNull(Calculation.subtotal(Sale())); assertEquals(0L,Calculation.subtotal(Sale(0,null)))
        assertNull(Calculation.total(DailyReport())); assertNull(Calculation.balance(DailyReport()))
        assertEquals(0L,Calculation.total(DailyReport(products=listOf(Stock(sales=listOf(Sale(0,null),Sale(),Sale())),Stock(),Stock()))))
    }
    @Test fun missingPriceDoesNotUnderstateTotal() {
        val s=Stock(delivery=10,sales=listOf(Sale(2,null),Sale(1,10000),Sale()))
        val r=DailyReport(products=listOf(s,Stock(),Stock()))
        assertNull(Calculation.productTotal(s)); assertNull(Calculation.total(r)); assertNull(Calculation.balance(r)); assertTrue(Calculation.errors(r).any { "harga" in it })
    }
    @Test fun stockManualRulesAndTester() {
        val s=Stock(delivery=10,sample=3,bonus=2,manualTg=9,sales=listOf(Sale(7,1),Sale(),Sale()))
        assertEquals(-2L,Calculation.tg(s,Rules())); assertEquals(3L,Calculation.tg(s,Rules(subtractSample=false,subtractBonus=false)))
        assertEquals(9L,Calculation.tg(s,Rules(automaticTg=false))); assertEquals(4L,Calculation.tg(Tester(10,6),Rules()))
        assertTrue(Calculation.errors(DailyReport(products=listOf(s,Stock(),Stock()))).any { "negatif" in it })
    }
    @Test fun exactLargeIntegersAndOverflow() {
        assertEquals(999999000000000L,Calculation.subtotal(Sale(999999,1000000000)))
        assertThrows(IllegalArgumentException::class.java) { Calculation.subtotal(Sale(Calculation.MAX,2)) }
    }
    @Test fun callsTotalsAndIndependence() {
        val call=CallReport(outlets=List(30) { Outlet(name="Outlet $it",quantities=List(3) { Quantity(10,3) }) })
        assertEquals(300L,Calculation.calls(call,0,true)); assertEquals(90L,Calculation.calls(call,2,false))
        val edited=call.copy(outlets=call.outlets.dropLast(1)); assertEquals(87L,Calculation.calls(edited,0,false)); assertNull(Calculation.total(DailyReport()))
    }
    @Test fun priceAndRulesSnapshots() {
        val report=DailyReport(products=List(3) { Stock(sales=List(3) { Sale(1,10000) }) })
        val settings=Settings(prices=List(3) { List(3) { 30000L } },rules=Rules(automaticTg=false))
        assertEquals(90000L,Calculation.total(report)); assertEquals(30000L,settings.prices[0][0]); assertTrue(report.rules.automaticTg)
    }

    @Test fun largeQuantitiesReportValidationInsteadOfCrashing() {
        val stock=Stock(sales=List(3) { Sale(Calculation.MAX,1) })
        val r=DailyReport(products=listOf(stock,Stock(),Stock()))
        assertTrue(Calculation.errors(r).any { "besar" in it })
        assertTrue(Calculation.errors(CallReport(outlets=listOf(Outlet()))).isEmpty())
    }
}
