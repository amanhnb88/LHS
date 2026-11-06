package id.lhspapua

import id.lhspapua.domain.*
import id.lhspapua.export.ExcelExport
import id.lhspapua.export.Layout
import id.lhspapua.export.PdfExport
import id.lhspapua.export.Sheet
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.*
import org.junit.Test

class IndependentDailyTest {
    private fun workbook(sheets: List<Sheet>): ByteArray =
        ByteArrayOutputStream().also { ExcelExport.write(sheets,it) }.toByteArray()

    @Test fun manualStockTesterAndFinanceAreIndependentInPdfAndEditableExcel() {
        val draft=newDaily(Identity())
        val sales=listOf(10L to 20L,20L to 50L,10L to 10L)
        val r=draft.copy(
            products=draft.products.mapIndexed { p,stock ->
                val (gross,retail)=sales[p]
                stock.copy(manualSold=100L.takeIf { p<2 }, sample=5L.takeIf { p==0 },
                    manualTg=25L.takeIf { p==0 },
                    sales=listOf(stock.sales[0].copy(quantity=gross),stock.sales[1],stock.sales[2].copy(quantity=retail)))
            },
            testers=listOf(Tester(),Tester(),Tester(delivery=10,sample=3,manualTg=5)),
            finance=Finance(previous=50000,settlement=0,manualBalance=12345)
        )
        Calculation.validate(r)
        assertTrue(Calculation.errors(r).isEmpty())
        assertEquals(100L,Calculation.stockSold(r,1))
        assertEquals(70L,Calculation.sold(r.products[1]))
        assertEquals(25L,Calculation.stockTg(r,0))
        assertEquals(5L,Calculation.testerTg(r,2))
        assertEquals(12345L,Calculation.balance(r))
        assertEquals(listOf(430000L,1075000L,355000L),r.products.map(Calculation::productTotal))
        assertEquals(1860000L,Calculation.total(r))

        val sheets=Layout.dailyWorkbook(r)
        val sheet=sheets.single()
        val oldLayout=Layout.daily(StockFixture.report())
        assertEquals(oldLayout.widths,sheet.widths)
        assertEquals(oldLayout.heights,sheet.heights)
        assertEquals(oldLayout.cells.map { it.copy(text="",number=null,formula=null) },
            sheet.cells.map { it.copy(text="",number=null,formula=null) })

        val pdf=ByteArrayOutputStream().also { PdfExport.write(sheets,it) }.toByteArray()
        PDDocument.load(pdf).use { doc ->
            assertEquals(1,doc.numberOfPages)
            assertEquals(595.2756f,doc.getPage(0).mediaBox.width,0.01f)
            assertEquals(841.8898f,doc.getPage(0).mediaBox.height,0.01f)
            val text=PDFTextStripper().getText(doc)
            listOf("1.860.000","1.075.000","430.000","355.000","12.345").forEach {
                assertTrue("PDF missing $it",text.contains(it))
            }
        }
        val excel=workbook(sheets)
        XSSFWorkbook(excel.inputStream()).use { book ->
            assertEquals(1,book.numberOfSheets)
            val ws=book.getSheetAt(0)
            assertEquals(9.toShort(),ws.printSetup.paperSize)
            assertFalse(ws.printSetup.landscape)
            assertNotNull(book.getPrintArea(0))
            val eval=book.creationHelper.createFormulaEvaluator()
            fun cell(row:Int,col:Int)=ws.getRow(row).getCell(col)
            fun number(row:Int,col:Int)=eval.evaluate(cell(row,col)).numberValue
            assertEquals(CellType.BLANK,cell(8,4).cellType)
            assertEquals(CellType.BLANK,cell(11,4).cellType)
            assertEquals(CellType.BLANK,cell(41,5).cellType)
            assertEquals("-",cell(9,16).stringCellValue)
            assertEquals(CellType.BLANK,cell(9,20).cellType)
            listOf(Triple(9,4,100.0),Triple(10,4,5.0),Triple(12,4,25.0),Triple(17,4,100.0),
                Triple(35,15,5.0),Triple(42,5,0.0),Triple(45,5,12345.0)).forEach { (row,col,expected) ->
                assertEquals(CellType.NUMERIC,cell(row,col).cellType)
                assertEquals(expected,cell(row,col).numericCellValue,0.0)
            }
            assertEquals(140000.0,number(8,20),0.0)
            assertEquals(290000.0,number(10,20),0.0)
            assertEquals(430000.0,number(11,20),0.0)
            assertEquals(1075000.0,number(19,20),0.0)
            assertEquals(355000.0,number(27,20),0.0)
            assertEquals(1860000.0,number(39,5),0.0)

            cell(17,4).setCellValue(125.0)
            eval.clearAllCachedResultValues()
            assertEquals(20.0,cell(16,13).numericCellValue,0.0)
            assertEquals(50.0,cell(18,13).numericCellValue,0.0)
            assertEquals(1860000.0,number(39,5),0.0)
            cell(16,13).setCellValue(21.0)
            cell(33,15).setCellValue(50.0)
            cell(40,5).setCellValue(80000.0)
            eval.clearAllCachedResultValues()
            assertEquals(1875000.0,number(39,5),0.0)
            assertEquals(125.0,cell(17,4).numericCellValue,0.0)
            assertEquals(25.0,cell(12,4).numericCellValue,0.0)
            assertEquals(5.0,cell(35,15).numericCellValue,0.0)
            assertEquals(12345.0,cell(45,5).numericCellValue,0.0)
        }
        val output=File("build/verification").apply { mkdirs() }
        File(output,"LHS_3_2.pdf").writeBytes(pdf)
        File(output,"LHS_3_2.xlsx").writeBytes(excel)
    }

    @Test fun blankReportExportsAndExplicitZeroDiffersFromBlank() {
        val r=newDaily(Identity())
        assertTrue(Calculation.errors(r).isEmpty())
        assertNull(Calculation.total(r))
        assertNull(Calculation.balance(r))
        assertTrue(r.products.all { stock -> stock.sales.all { it.quantity==null } })
        XSSFWorkbook(workbook(Layout.dailyWorkbook(r)).inputStream()).use { book ->
            val ws=book.getSheetAt(0)
            val eval=book.creationHelper.createFormulaEvaluator()
            val quantity=ws.getRow(8).getCell(13)
            val subtotal=ws.getRow(8).getCell(20)
            val total=ws.getRow(39).getCell(5)
            assertEquals(CellType.BLANK,quantity.cellType)
            assertEquals("",eval.evaluate(subtotal).stringValue)
            assertEquals("",eval.evaluate(total).stringValue)
            quantity.setCellValue(0.0)
            eval.clearAllCachedResultValues()
            assertEquals(0.0,eval.evaluate(subtotal).numberValue,0.0)
            assertEquals(0.0,eval.evaluate(total).numberValue,0.0)
        }
        val martil=r.products[1]
        val fromPaper=r.copy(products=listOf(r.products[0],
            martil.copy(manualSold=1050,sales=listOf(martil.sales[0],martil.sales[1],martil.sales[2].copy(quantity=1050))),
            r.products[2]))
        assertEquals(16275000L,Calculation.total(fromPaper))
        assertEquals(1050L,Calculation.stockSold(fromPaper,1))
        val invalid=fromPaper.copy(products=fromPaper.products.map { it.copy(manualSold=-1) })
        assertTrue(Calculation.errors(invalid).isNotEmpty())
    }

    @Test fun newReportsStayOutsideHistoricalLedgerAndRoundTripManualValues() {
        val old=StockFixture.final(StockFixture.report())
        val fresh=newDaily(old.identity).copy(
            status=ReportStatus.FINAL,
            products=List(3) { Stock(sales=OfficialPrices.sales(Product.entries[it]),manualSold=0,manualTg=25) }
        )
        val restored=Json.decodeFromString(DailyReport.serializer(),Json.encodeToString(DailyReport.serializer(),fresh))
        assertEquals(fresh,restored)
        assertFalse(StockLedger.eligible(restored))
        assertNull(StockLedger.sourceFor(restored,listOf(old)))
        val mixed=StockLedger.reconcile(listOf(old),restored)
        assertEquals(old,mixed.reports.first { it.id==old.id })
        assertEquals(restored,mixed.reports.first { it.id==restored.id })
        val next=StockFixture.auto("2026-11-02")
        val oldNext=StockLedger.reconcile(mixed.reports,next).reports.first { it.id==next.id }
        assertEquals(old.id,oldNext.stockSourceId)
        assertEquals(85L,oldNext.products[0].opening)
        assertEquals(60L,Calculation.stockTg(oldNext,0))
    }
}
