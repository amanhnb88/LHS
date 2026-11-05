package id.lhspapua

import id.lhspapua.domain.*
import org.junit.Assert.*
import org.junit.Test

object StockFixture {
    fun report(date:String="2026-11-01",owner:String="sales-ansar") = newDaily(Identity(date=date,sales="Ansar",stockOwnerId=owner),Settings(rules=Rules(acknowledged=true),prices=List(3){List(3){10000L}})).let { r ->
        r.copy(openingMode=OpeningMode.MANUAL,products=r.products.map { it.copy(opening=50,delivery=200,sample=10,bonus=5,sales=listOf(Sale(100,10000),Sale(40,10000),Sale(10,10000))) },testers=r.testers.map { it.copy(opening=5,delivery=10,sample=3) },finance=r.finance.copy(previous=0))
    }
    fun final(r:DailyReport)=r.copy(status=ReportStatus.FINAL,wasFinalized=true,finalizedAt="2026-11-05T00:00:00Z")
    fun auto(date:String,owner:String="sales-ansar")=report(date,owner).let { r->r.copy(openingMode=OpeningMode.AUTO,products=r.products.map { it.copy(opening=null,delivery=100,sample=5,bonus=0,sales=listOf(Sale(100,10000),Sale(20,10000),Sale(0,null))) },testers=r.testers.map { it.copy(opening=null,delivery=1,sample=2) }) }
}
class StockLedgerTest {
    @Test fun confirmedTwoDayExampleAndIndependentTester() {
        val first=StockFixture.final(StockFixture.report())
        assertEquals(85L,Calculation.tg(first.products[1],first.rules));assertEquals(12L,Calculation.tg(first.testers[1],first.rules))
        val next=StockFixture.auto("2026-11-03")
        val saved=StockLedger.reconcile(listOf(first),next).reports.first { it.id==next.id }
        assertEquals(85L,saved.products[1].opening);assertEquals(60L,Calculation.tg(saved.products[1],saved.rules))
        assertEquals(12L,saved.testers[1].opening);assertEquals(11L,Calculation.tg(saved.testers[1],saved.rules));assertTrue(Calculation.errors(saved).isEmpty())
    }
    @Test fun missingSourceOwnerSeparationDraftAndLegacyAreNotZero() {
        val r=StockFixture.auto("2026-11-05")
        val foreign=StockFixture.final(StockFixture.report(owner="sales-other"))
        val draft=StockFixture.report();val legacy=DailyReport(identity=r.identity.copy(date="2026-11-01"))
        val result=StockLedger.reconcile(listOf(foreign,draft,legacy),r).reports.first { it.id==r.id }
        assertNull(result.stockSourceId);assertNull(result.products[0].opening);assertNull(result.testers[0].opening)
        assertTrue(Calculation.errors(result).any { "Stok Awal" in it })
    }
    @Test fun explicitSameDateOrderingAndDuplicateProtection() {
        val first=StockFixture.final(StockFixture.report())
        val r=StockFixture.auto(first.identity.date).copy(stockSequence=2)
        assertEquals(first.id,StockLedger.sourceFor(r,listOf(first))!!.id)
        val duplicate=StockFixture.report().copy(stockSequence=1)
        val result=StockLedger.reconcile(listOf(first),duplicate).reports
        assertTrue(result.all { it.stockStale });assertTrue(result.none(StockLedger::eligible))
        assertEquals(2,StockLedger.sequence(first.identity,listOf(first)))
    }
    @Test fun correctionsRecalculateDraftsButFreezeFinalizedNumbers() {
        val first=StockFixture.final(StockFixture.report())
        val next=StockFixture.auto("2026-11-02")
        val second=StockFixture.final(StockLedger.reconcile(listOf(first),next).reports.first { it.id==next.id })
        val third=StockFixture.auto("2026-11-04")
        val all=StockLedger.reconcile(listOf(first,second),third).reports
        val changed=first.copy(products=first.products.map { it.copy(delivery=210) })
        val draftOnly=StockLedger.reconcile(StockLedger.reconcile(listOf(first),next).reports,changed)
        assertEquals(95L,draftOnly.reports.first { it.id==next.id }.products[0].opening)
        val result=StockLedger.reconcile(all,changed)
        val preserved=result.reports.first { it.id==second.id }
        assertEquals(second.products,preserved.products);assertTrue(preserved.stockStale)
        assertNull(result.reports.first { it.id==third.id }.products[0].opening)
        assertTrue(second.id in result.affected)
        val reopened=preserved.copy(status=ReportStatus.DRAFT,finalizedAt=null)
        val refreshed=StockLedger.reconcile(result.reports,reopened).reports.first { it.id==second.id }
        assertEquals(95L,refreshed.products[0].opening);assertFalse(refreshed.stockStale)
        val finalized=StockLedger.reconcile(result.reports,StockFixture.final(refreshed)).reports
        assertEquals(70L,finalized.first { it.id==third.id }.products[0].opening)
    }
    @Test fun reopenedSourceBlocksSkippingBackwardsAndInvalidSources() {
        val old=StockFixture.final(StockFixture.report("2026-10-28"))
        val previous=StockFixture.report("2026-11-01").copy(wasFinalized=true)
        assertNull(StockLedger.sourceFor(StockFixture.auto("2026-11-05"),listOf(old,previous)))
        val negative=StockFixture.final(StockFixture.report().let { r->r.copy(products=r.products.map { it.copy(delivery=0,opening=0) }) })
        assertFalse(StockLedger.eligible(negative));assertTrue(Calculation.stockErrors(negative).any { "tidak mencukupi" in it })
    }
    @Test fun nullsPricesLargeNumbersAndManualFinanceRemainExplicit() {
        val first=StockFixture.report()
        assertEquals(4500000L,Calculation.total(first))
        val missing=first.copy(products=first.products.map { it.copy(opening=null) })
        assertNull(Calculation.tg(missing.products[0],missing.rules))
        val unset=first.copy(products=first.products.map { it.copy(sales=listOf(Sale(1,null),Sale(0,null),Sale(0,null))) })
        assertNull(Calculation.total(unset));assertFalse(StockLedger.eligible(StockFixture.final(unset)))
        val unverified=first.copy(rules=Rules())
        assertTrue(Calculation.errors(unverified).any { "belum disetujui" in it })
        val manual=unverified.copy(rules=Rules(automaticFinance=false),finance=first.finance.copy(manualBalance=-12345))
        assertEquals(-12345L,Calculation.balance(manual));assertTrue(Calculation.errors(manual).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { Calculation.available(Stock(opening=Calculation.MAX,delivery=1)) }
        assertTrue(Calculation.errors(first.copy(products=first.products.map { it.copy(opening=Calculation.MAX) })).any { "besar" in it })
    }
}
