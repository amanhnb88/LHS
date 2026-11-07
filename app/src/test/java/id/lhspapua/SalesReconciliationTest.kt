package id.lhspapua

import id.lhspapua.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SalesReconciliationTest {
    @Test fun roleModesKeepPricesNullsAndIndependentFields() {
        for(mode in listOf(IntegrationMode.SALES,IntegrationMode.LEADER)) {
            val base=newDaily(Identity(sales="MAIKEL"),mode)
            val tier=if(mode==IntegrationMode.SALES)Tier.RTL else Tier.G
            val other=if(tier==Tier.G)Tier.RTL else Tier.G
            assertNull(Calculation.total(base))
            val report=base.copy(products=base.products.map { stock -> stock.copy(manualTg=50,
                sales=stock.sales.mapIndexed { index,sale -> if(index==tier.ordinal)sale.copy(quantity=500) else sale }) })
            val expected=if(mode==IntegrationMode.SALES)listOf(7250000L,7750000L,9000000L) else listOf(7000000L,7500000L,8750000L)
            for(p in Product.entries.indices) {
                assertEquals(500L,Calculation.stockSold(report,p))
                assertEquals(expected[p],Calculation.productTotal(report,p))
                assertEquals(50L,Calculation.stockTg(report,p))
                assertNull(report.products[p].sales[other.ordinal].quantity)
            }
            assertEquals(expected.sum(),Calculation.total(report))
            assertTrue(Calculation.errors(report).isEmpty())
            val blank=base.copy(products=base.products.map { stock -> stock.copy(sales=stock.sales.mapIndexed { t,s -> if(t==other.ordinal)s.copy(quantity=0) else s }) })
            assertNull(Calculation.stockSold(blank,1))
            assertNull(Calculation.total(blank))
            val conflict=report.copy(products=report.products.map { stock -> stock.copy(sales=stock.sales.mapIndexed { t,s -> if(t==other.ordinal)s.copy(quantity=12) else s }) })
            assertEquals(3,Calculation.modeConflicts(conflict).size)
            assertTrue(runCatching { Calculation.total(conflict) }.isFailure)
            assertTrue(Calculation.errors(conflict).isNotEmpty())
            assertEquals(12L,conflict.products[0].sales[other.ordinal].quantity)
            val overflow=report.copy(products=report.products.mapIndexed { p,stock -> if(p!=0)stock else stock.copy(sales=stock.sales.mapIndexed { t,s -> if(t==tier.ordinal)s.copy(quantity=Calculation.MAX) else s }) })
            assertTrue(runCatching { Calculation.total(overflow) }.isFailure)
        }
        val historical=newDaily(Identity()).copy(reportMode=null,formatVersion=3,products=List(3) { Stock(manualSold=99,sales=OfficialPrices.sales(Product.MARTIL)) })
        assertEquals(99L,Calculation.stockSold(historical,1))
        val json=Json { encodeDefaults=true }
        assertEquals(IntegrationMode.TERINTEGRASI,json.decodeFromString<Settings>("{\"integrationMode\":\"TERINTEGRASI\"}").integrationMode)
        assertEquals(IntegrationMode.MANDIRI,json.decodeFromString<Settings>("{}").integrationMode)
        assertEquals(historical,json.decodeFromString<DailyReport>(json.encodeToString(historical).replace(",\"reportMode\":null","")))
        assertEquals(historical,json.decodeFromString<DailyReport>(json.encodeToString(historical)))
    }

    @Test fun roleReconciliationRequiresOnlyItsActiveCategoryAndRejectsConflicts() {
        for(mode in listOf(IntegrationMode.SALES,IntegrationMode.LEADER,IntegrationMode.MANDIRI,IntegrationMode.TERINTEGRASI)) {
            val tier=if(mode==IntegrationMode.LEADER)Tier.G else Tier.RTL
            fun report(value:Long?,locked:Long?=null)=linked.copy(reportMode=mode,products=linked.products.mapIndexed { p,stock ->
                stock.copy(sales=stock.sales.mapIndexed { t,sale ->
                    if(t==Tier.SG.ordinal)sale else sale.copy(quantity=if(t==tier.ordinal) { if(p==1)value else 0L } else locked)
                })
            })
            fun match(r:DailyReport)=SalesReconciliation.review(r,source,false)!!.products[1]
            if(mode==IntegrationMode.SALES || mode==IntegrationMode.LEADER) {
                assertEquals(SalesReconciliation.Match.MATCH,match(report(650)).status)
                assertEquals(-50L,match(report(600)).difference)
                assertEquals(SalesReconciliation.Match.INCOMPLETE,match(report(null)).status)
                assertEquals(SalesReconciliation.Match.MATCH,match(report(650,0)).status)
                assertEquals(SalesReconciliation.Match.INVALID,match(report(650,1)).status)
                assertTrue(Calculation.errors(report(650,1)).isNotEmpty())
                assertEquals(0L,match(report(0)).total)
            } else {
                assertEquals(SalesReconciliation.Match.INCOMPLETE,match(report(650)).status)
                assertEquals(SalesReconciliation.Match.MATCH,match(report(650,0)).status)
            }
        }
    }
    private val identity=Identity(date="2026-11-07",sales="MAIKEL",area="SORONG",route="A")
    private val source=CallReport(identity=identity,completed=true,outlets=listOf(
        Outlet(quantities=listOf(Quantity(sold=0),Quantity(sold=350),Quantity(sold=0))),
        Outlet(quantities=listOf(Quantity(),Quantity(sold=300),Quantity()))
    ))
    private val base=newDaily(identity)
    private val linked=base.copy(callsheetLink=CallsheetLink(source.id,SalesReconciliation.fingerprint(source),"2026-11-07T04:00:00Z"))

    @Test fun candidatesRespectFinalIdentityAndExistingClaims() {
        val weak=source.copy(id="weak",identity=identity.copy(sales=" maikel ",area="",route=""))
        val final=source.copy(id="final")
        val rejected=listOf(source.copy(completed=false),source.copy(identity=identity.copy(route="B")),
            source.copy(identity=identity.copy(date="2026-11-06")),source.copy(identity=identity.copy(sales="RANI")))
        assertEquals(listOf(final,weak),SalesReconciliation.candidates(base,listOf(weak,final)+rejected,emptyList()))
        val other=linked.copy(id="other",callsheetLink=linked.callsheetLink!!.copy(sourceId=final.id))
        assertEquals(listOf(weak),SalesReconciliation.candidates(base,listOf(weak,final),listOf(other)))
    }

    @Test fun comparesGrossPlusRetailAndDistinguishesNullZeroAndOverflow() {
        fun check(gross:Long?,retail:Long?)=SalesReconciliation.review(linked.copy(
            products=linked.products.mapIndexed { p,stock ->
                val g=if(p==1)gross else 0L
                val r=if(p==1)retail else 0L
                stock.copy(sales=listOf(stock.sales[0].copy(quantity=g),stock.sales[1],stock.sales[2].copy(quantity=r)))
            }
        ),source,false)!!
        assertTrue(check(200,450).problems.isEmpty())
        assertEquals(0L,check(200,450).products[0].total)
        assertEquals(-50L,check(200,400).products[1].difference)
        assertEquals(SalesReconciliation.Match.DIFFERENT,check(200,400).products[1].status)
        assertEquals(SalesReconciliation.Match.INCOMPLETE,check(200,null).products[1].status)
        assertEquals(SalesReconciliation.Match.INCOMPLETE,check(null,650).products[1].status)
        assertEquals(SalesReconciliation.Match.INCOMPLETE,check(null,null).products[1].status)
        assertEquals(SalesReconciliation.Match.MATCH,check(0,650).products[1].status)
        assertEquals(SalesReconciliation.Match.MATCH,check(650,0).products[1].status)
        assertEquals(SalesReconciliation.Match.INCOMPLETE,
            SalesReconciliation.review(linked,source.copy(outlets=emptyList()),false)!!.products[1].status)
        assertEquals(SalesReconciliation.Match.INVALID,check(Calculation.MAX,1).products[1].status)
    }

    @Test fun sourceMustRemainFinalPresentAndUnchanged() {
        fun status(call:CallReport?,conflict:Boolean=false)=SalesReconciliation.review(linked,call,conflict)!!.source
        assertEquals(SalesReconciliation.SourceStatus.READY,status(source.copy(updatedAt="different")))
        assertEquals(SalesReconciliation.SourceStatus.MISSING,status(null))
        assertEquals(SalesReconciliation.SourceStatus.DRAFT,status(source.copy(completed=false)))
        assertEquals(SalesReconciliation.SourceStatus.IDENTITY,status(source.copy(identity=identity.copy(route="B"))))
        assertEquals(SalesReconciliation.SourceStatus.CONFLICT,status(source,true))
        assertEquals(SalesReconciliation.SourceStatus.CHANGED,status(source.copy(outlets=source.outlets.dropLast(1))))
        assertEquals(SalesReconciliation.SourceStatus.CHANGED,status(source.copy(completedAt="2026-11-07T04:10:00Z")))
    }
}
