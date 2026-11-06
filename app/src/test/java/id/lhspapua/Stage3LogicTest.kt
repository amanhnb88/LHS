package id.lhspapua

import id.lhspapua.data.WriteBuffer
import id.lhspapua.domain.*
import id.lhspapua.ui.*
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class Stage3LogicTest {
    @Test fun unifiedOutletSearchFindsMasterAndNeverCopiesTransactions() {
        val settings=Settings(outlets=listOf(SavedOutlet("Toko Aimas","Jl. Aimas")))
        val matches=OutletSearch.matches("aimas",settings,emptyList())
        assertEquals(1,matches.size)
        val visit=OutletSearch.visit(matches.single());assertEquals("JL. AIMAS",visit.address)
        assertTrue(visit.quantities.all { it.stock==null&&it.sold==null })
        val old=CallReport(outlets=listOf(visit.copy(quantities=List(3){Quantity(500,200)})))
        assertEquals(1,OutletSearch.matches("aimas",settings,listOf(old)).size)
        assertTrue(OutletSearch.visit(OutletSearch.matches("aimas",settings,listOf(old)).single()).quantities.all { it.stock==null&&it.sold==null })
    }
    @Test fun numericPasteBlankZeroDecimalAndLargeValuesAreUnambiguous() {
        assertEquals("",NumberInput.normalize(""));assertEquals("0",NumberInput.normalize("0"))
        assertEquals("14500",NumberInput.normalize("Rp 14.500",money=true));assertEquals("1500000",NumberInput.normalize("1.500.000",money=true))
        assertEquals("-25000",NumberInput.normalize("-25.000",money=true,signed=true))
        assertNull(NumberInput.normalize("12,5"));assertNull(NumberInput.normalize("12.5"));assertNull(NumberInput.normalize("123x"))
        assertNull(NumberInput.normalize("-1"));assertEquals(Calculation.MAX.toString(),NumberInput.normalize(Calculation.MAX.toString()));assertNull(NumberInput.normalize("1000000000000000"))
    }
    @Test fun rupiahCursorOffsetsRemainMonotonicAndInBounds() {
        for(raw in listOf("","-","0","123","1234","1234567","-1234567")) {
            val t=RupiahTransformation.filter(AnnotatedString(raw))
            val offsets=(0..raw.length).map { t.offsetMapping.originalToTransformed(it) }
            assertEquals(offsets.sorted(),offsets);assertTrue(offsets.all { it in 0..t.text.length })
            for(i in 0..t.text.length)assertTrue(t.offsetMapping.transformedToOriginal(i) in 0..raw.length)
        }
    }
    @Test fun rapidSnapshotsAreCoalescedAndNewerEditsGetAnotherWriteBoundary() {
        val buffer=WriteBuffer<Int>();assertTrue(buffer.replace("day",1))
        repeat(200) { assertFalse(buffer.replace("day",it)) }
        assertEquals(1,buffer.size());assertEquals(199,buffer.take("day"));assertNull(buffer.take("day"))
        assertTrue(buffer.replace("day",250));assertTrue(buffer.replace("call",10));assertEquals(250,buffer.take("day"));assertEquals(10,buffer.take("call"))
    }
    @Test fun validationRoutesToTheMissingFieldAndCategoryPrice() {
        val r=StockFixture.report().let { it.copy(products=it.products.replace(1,it.products[1].copy(opening=null))) }
        assertEquals("stock-1-opening",ReportIssues.daily(r).first { it.product==1 }.field)
        val missing=StockFixture.report().let { it.copy(products=it.products.replace(0,it.products[0].copy(sales=listOf(Sale(0,null),Sale(0,null),Sale(5,null))))) }
        val price=ReportIssues.daily(missing).first { it.field.startsWith("price-") }
        assertEquals("price-0-2",price.field);assertEquals("Atur Harga Retail",price.action)
        val tester=r.copy(testers=r.testers.replace(2,r.testers[2].copy(opening=null)))
        assertEquals("tester-2-opening",ReportIssues.daily(tester).first { it.section==1 }.field)
    }
    @Test fun uppercasePreservesCompositionSelectionWhitespaceAndSearch() {
        val text=androidx.compose.ui.text.input.TextFieldValue("aß, kios\nmawar 07",
            selection=androidx.compose.ui.text.TextRange(2),
            composition=androidx.compose.ui.text.TextRange(1,2))
        val normalized=text.uppercaseInput()
        assertEquals("ASS, KIOS\nMAWAR 07",normalized.text)
        assertEquals(androidx.compose.ui.text.TextRange(3),normalized.selection)
        assertEquals(androidx.compose.ui.text.TextRange(1,3),normalized.composition)
        val settings=Settings(outlets=listOf(SavedOutlet("Kios Mawar Aimas","jalan sorong")))
        assertEquals(OutletSearch.matches("kios mawar",settings,emptyList()),
            OutletSearch.matches("KIOS MAWAR",settings,emptyList()))
        assertEquals("KIOS MAWAR AIMAS",OutletSearch.visit(settings.outlets.single()).name)
    }

}
