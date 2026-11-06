package id.lhspapua

import id.lhspapua.domain.*
import org.junit.Assert.*
import org.junit.Test

class SalesReconciliationTest {
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
