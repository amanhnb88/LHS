package id.lhspapua

import id.lhspapua.domain.*
import id.lhspapua.export.ExcelExport
import id.lhspapua.export.Layout
import id.lhspapua.export.PdfExport
import id.lhspapua.export.Sheet
import id.lhspapua.ui.ReportIssues
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.*
import org.junit.Test

class AdminRulesRegressionTest {
    private fun workbook(sheets: List<Sheet>, sample: String? = null): XSSFWorkbook {
        val output = ByteArrayOutputStream()
        ExcelExport.write(sheets, output)
        val bytes = output.toByteArray()
        sample?.let { saveSample("$it.xlsx", bytes) }
        return XSSFWorkbook(bytes.inputStream())
    }

    private fun pdfText(sheet: Sheet, landscape: Boolean, sample: String? = null): String {
        val output = ByteArrayOutputStream()
        PdfExport.write(listOf(sheet), output)
        val bytes = output.toByteArray()
        sample?.let { saveSample("$it.pdf", bytes) }
        return PDDocument.load(bytes).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val page = pdf.getPage(0).mediaBox
            assertEquals(if(landscape) 841.8898f else 595.2756f, page.width, 0.01f)
            assertEquals(if(landscape) 595.2756f else 841.8898f, page.height, 0.01f)
            PDFTextStripper().getText(pdf)
        }
    }

    private fun saveSample(name: String, bytes: ByteArray) {
        val directory = File("build/verification").apply { mkdirs() }
        File(directory, name).writeBytes(bytes)
    }

    @Test fun sixCallsheetColumnsSumIndependentlyInAndroidPdfAndExcel() {
        val report = CallReport(outlets=listOf(
            Outlet(name="Kios A", quantities=listOf(Quantity(sold=3), Quantity(stock=100), Quantity(stock=4))),
            Outlet(name="Kios B", quantities=listOf(Quantity(stock=2), Quantity(sold=200), Quantity(sold=5))),
            Outlet(name="Kios C", quantities=listOf(Quantity(8,9), Quantity(50,75), Quantity(6,7)))
        ))
        val expected = listOf(10L,12L,150L,275L,10L,12L)
        Calculation.validate(report)
        assertTrue(Calculation.errors(report).isEmpty())
        assertTrue(ReportIssues.call(report).isEmpty())
        val sheets = Layout.calls(report)
        val pdf = pdfText(sheets.single(), landscape=true, sample="CALLSHEET_3_1")
        assertTrue(pdf.contains("150"))
        assertTrue(pdf.contains("275"))
        workbook(sheets, "CALLSHEET_3_1").use { book ->
            val sheet = book.getSheetAt(0)
            assertEquals(9.toShort(), sheet.printSetup.paperSize)
            assertTrue(sheet.printSetup.landscape)
            assertNotNull(book.getPrintArea(0))
            val evaluator = book.creationHelper.createFormulaEvaluator()
            expected.forEachIndexed { column, total ->
                assertEquals(total, Calculation.calls(report, column/2, column%2==0))
                val index = Layout.callColumn(column+3)
                assertEquals(total, sheets.single().cells.single { it.row==35 && it.col==index }.number)
                val cell = sheet.getRow(35).getCell(index)
                assertEquals(CellType.FORMULA, cell.cellType)
                assertEquals(total.toDouble(), evaluator.evaluate(cell).numberValue, 0.0)
            }
            val stockColumn = Layout.callColumn(5)
            val soldColumn = Layout.callColumn(6)
            assertEquals(CellType.BLANK, sheet.getRow(5).getCell(soldColumn).cellType)
            assertEquals(CellType.BLANK, sheet.getRow(6).getCell(stockColumn).cellType)
            sheet.getRow(5).getCell(stockColumn).setCellValue(125.0)
            evaluator.clearAllCachedResultValues()
            assertEquals(175.0, evaluator.evaluate(sheet.getRow(35).getCell(stockColumn)).numberValue, 0.0)
            assertEquals(275.0, evaluator.evaluate(sheet.getRow(35).getCell(soldColumn)).numberValue, 0.0)
        }
    }

    @Test fun allEmptyColumnsRemainBlankButExplicitZeroRemainsNumeric() {
        val report = CallReport(outlets=listOf(Outlet(name="Kios tanpa angka")))
        assertTrue(ReportIssues.call(report).isEmpty())
        val sheets = Layout.calls(report)
        val pdf = pdfText(sheets.single(), landscape=true)
        assertTrue(pdf.contains("Kios tanpa angka"))
        workbook(sheets).use { book ->
            val sheet = book.getSheetAt(0)
            val evaluator = book.creationHelper.createFormulaEvaluator()
            repeat(6) { column ->
                val index = Layout.callColumn(column+3)
                assertNull(Calculation.calls(report, column/2, column%2==0))
                assertNull(sheets.single().cells.single { it.row==35 && it.col==index }.number)
                assertEquals("", evaluator.evaluate(sheet.getRow(35).getCell(index)).stringValue)
            }
            val stock = Layout.callColumn(3)
            sheet.getRow(5).getCell(stock).setCellValue(0.0)
            evaluator.clearAllCachedResultValues()
            assertEquals(0.0, evaluator.evaluate(sheet.getRow(35).getCell(stock)).numberValue, 0.0)
        }
        val explicitZero = report.copy(outlets=listOf(Outlet(quantities=listOf(Quantity(stock=0), Quantity(), Quantity()))))
        assertEquals(0L, Calculation.calls(explicitZero, 0, true))
        assertEquals(1, explicitZero.activeOutlets().size)
    }

    @Test fun unnamedOutletWithOneProductCanBeExportedAndInvalidNumbersStillFail() {
        val partial = Outlet(quantities=listOf(Quantity(sold=200), Quantity(), Quantity()))
        val report = CallReport(outlets=listOf(partial))
        assertTrue(ReportIssues.call(report).isEmpty())
        assertEquals(1, report.activeOutlets().size)
        val sheets = Layout.calls(report)
        assertTrue(pdfText(sheets.single(), landscape=true).contains("200"))
        workbook(sheets).use { book ->
            val sheet = book.getSheetAt(0)
            assertEquals(200.0, sheet.getRow(5).getCell(Layout.callColumn(4)).numericCellValue, 0.0)
            assertEquals(CellType.BLANK, sheet.getRow(5).getCell(Layout.callColumn(5)).cellType)
        }
        val negative = report.copy(outlets=listOf(partial.copy(quantities=listOf(Quantity(stock=-1), Quantity(), Quantity()))))
        assertThrows(IllegalArgumentException::class.java) { Calculation.validate(negative) }
        assertTrue(Calculation.errors(negative).isNotEmpty())
        val large = partial.copy(quantities=listOf(Quantity(stock=Calculation.MAX), Quantity(), Quantity()))
        assertTrue(Calculation.errors(report.copy(outlets=listOf(large,large))).isNotEmpty())
    }

    @Test fun officialPricesDisableSgAndKeepDailyTotalsAndTgInSync() {
        val oldSettings = Settings(prices=List(3) { listOf(1L,2L,3L) },rules=Rules(acknowledged=true))
        val draft = newDaily(Identity(sales="Sales Uji",stockOwnerId="owner"),oldSettings)
        val report = draft.copy(
            products=draft.products.map { stock -> stock.copy(opening=50,delivery=200,sample=10,bonus=5,
                sales=stock.sales.mapIndexed { tier, sale ->
                    when(Tier.entries[tier]) {
                        Tier.G -> sale.copy(quantity=100)
                        Tier.RTL -> sale.copy(quantity=50)
                        Tier.SG -> sale
                    }
                }) },
            testers=draft.testers.map { it.copy(opening=5,delivery=10,sample=3) },
            finance=draft.finance.copy(previous=50000)
        )
        val gross = listOf(14000L,15000L,17500L)
        val retail = listOf(14500L,15500L,18000L)
        val totals = listOf(2125000L,2275000L,2650000L)
        Calculation.validate(report)
        assertTrue(Calculation.errors(report).isEmpty())
        assertEquals(7050000L, Calculation.total(report))
        assertEquals(7100000L, Calculation.balance(report))
        val finalReport = report.copy(status=ReportStatus.FINAL,openingMode=OpeningMode.MANUAL)
        val nextDate = java.time.LocalDate.parse(report.identity.date).plusDays(1).toString()
        val nextDraft = newDaily(report.identity.copy(date=nextDate),oldSettings)
        val next = nextDraft.copy(
            products=nextDraft.products.map { stock -> stock.copy(delivery=100,sample=5,
                sales=listOf(stock.sales[0].copy(quantity=100),stock.sales[1],stock.sales[2].copy(quantity=20))) },
            testers=nextDraft.testers.map { it.copy(delivery=1,sample=2) }
        )
        val carried = StockLedger.reconcile(listOf(finalReport),next).reports.single { it.id==next.id }
        assertEquals(finalReport.id, carried.stockSourceId)
        assertEquals(85L, carried.products[0].opening)
        assertEquals(60L, Calculation.tg(carried.products[0], carried.rules))
        assertEquals(11L, Calculation.tg(carried.testers[0], carried.rules))
        val sheets = Layout.dailyWorkbook(report)
        val pdf = pdfText(sheets.first(), landscape=false, sample="LHS_3_1")
        listOf("14.000","14.500","15.000","15.500","17.500","18.000","7.050.000").forEach {
            assertTrue("PDF must show $it", pdf.contains(it))
        }
        workbook(sheets, "LHS_3_1").use { book ->
            val sheet = book.getSheetAt(0)
            assertEquals(9.toShort(), sheet.printSetup.paperSize)
            assertFalse(sheet.printSetup.landscape)
            assertNotNull(book.getPrintArea(0))
            assertTrue(book.isSheetHidden(1))
            val evaluator = book.creationHelper.createFormulaEvaluator()
            fun number(row: Int, col: Int) = evaluator.evaluate(sheet.getRow(row).getCell(col)).numberValue
            report.products.forEachIndexed { product, stock ->
                assertEquals(gross[product], stock.sales[Tier.G.ordinal].price)
                assertEquals(retail[product], stock.sales[Tier.RTL.ordinal].price)
                val sg = stock.sales[Tier.SG.ordinal]
                assertFalse(sg.applicable)
                assertNull(sg.price)
                assertNull(Calculation.subtotal(sg))
                assertEquals(150L, Calculation.sold(stock))
                assertEquals(85L, Calculation.tg(stock, report.rules))
                assertEquals(totals[product], Calculation.productTotal(stock))
                val row = 8+product*8
                assertEquals(gross[product].toDouble(), number(row,16), 0.0)
                assertEquals(retail[product].toDouble(), number(row+2,16), 0.0)
                assertEquals("-", sheet.getRow(row+1).getCell(16).stringCellValue)
                assertEquals(CellType.BLANK, sheet.getRow(row+1).getCell(20).cellType)
                assertEquals(gross[product]*100.0, number(row,20), 0.0)
                assertEquals(retail[product]*50.0, number(row+2,20), 0.0)
                assertEquals(totals[product].toDouble(), number(row+3,20), 0.0)
                assertEquals(150.0, number(row+1,4), 0.0)
                assertEquals(85.0, number(row+4,4), 0.0)
                // Even if someone types into the printed SG row in Excel, it is outside the formulas.
                sheet.getRow(row+1).getCell(13).setCellValue(1000.0)
                sheet.getRow(row+1).getCell(20).setCellValue(1000000.0)
            }
            evaluator.clearAllCachedResultValues()
            assertEquals(7050000.0, number(39,5), 0.0)
            assertEquals(7100000.0, number(45,5), 0.0)
            assertEquals(85.0, number(12,4), 0.0)
            assertEquals(12.0, number(35,3), 0.0)
        }
    }

    @Test fun historicalDraftAndFinalJsonKeepSgPricesAndFormulas() {
        for(status in ReportStatus.entries) {
            val historical = StockFixture.report().copy(status=status)
            val legacyJson = Json.encodeToString(DailyReport.serializer(),historical)
            assertFalse(legacyJson.contains("\"applicable\""))
            val restored = Json.decodeFromString(DailyReport.serializer(),legacyJson)
            assertEquals(historical, restored)
            Calculation.validate(restored)
            assertTrue(restored.products[0].sales[Tier.SG.ordinal].applicable)
            assertEquals(400000L, Calculation.subtotal(restored.products[0].sales[Tier.SG.ordinal]))
            assertEquals(4500000L, Calculation.total(restored))
            assertEquals(85L, Calculation.tg(restored.products[0], restored.rules))
            workbook(Layout.dailyWorkbook(restored)).use { book ->
                val sheet = book.getSheetAt(0)
                val evaluator = book.creationHelper.createFormulaEvaluator()
                assertEquals(10000.0, sheet.getRow(9).getCell(16).numericCellValue, 0.0)
                assertEquals(400000.0, evaluator.evaluate(sheet.getRow(9).getCell(20)).numberValue, 0.0)
            }
        }
    }
}
