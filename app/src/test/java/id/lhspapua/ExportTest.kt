package id.lhspapua

import id.lhspapua.domain.*
import id.lhspapua.export.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ExportTest {
    private val fixture=DailyReport(identity=Identity(sales="Aldry",area="Jayapura",route="Sentani",vehicle="Motor",plate="PA 1234",join="01/01/2026"),products=List(3) { Stock(100,2,1,sales=listOf(Sale(10,10000),Sale(5,11000),Sale(2,12000))) },testers=List(3) { Tester(10,3) },finance=Finance(50000,20000,5000,100000,10000,"Biaya operasional sales","Aldry"),rules=Rules(acknowledged=true))
    @Test fun pdfPhysicalA4AndText() {
        val output=ByteArrayOutputStream(); PdfExport.write(listOf(Layout.daily(fixture)),output)
        PDDocument.load(output.toByteArray()).use { pdf ->
            assertEquals(1,pdf.numberOfPages); assertEquals(595.2756f,pdf.getPage(0).mediaBox.width,0.01f); assertEquals(841.8898f,pdf.getPage(0).mediaBox.height,0.01f)
            val text=PDFTextStripper().getText(pdf); listOf("MARTIL 12","MARTIL","HD LIGHT","TESTER","Saldo Akhir","Keterangan","Dibuat Oleh").forEach { assertTrue(it,text.contains(it)) }
            assertTrue(text.contains("537.000")); assertTrue(text.contains("462.000"))
        }
        val dir=File("build/verification").apply { mkdirs() }; File(dir,"LHS_sample.pdf").writeBytes(output.toByteArray())
    }
    @Test fun callsPaginationAndExcelInjectionSafety() {
        for(count in listOf(1,30,31,61)) {
            val report=CallReport(outlets=List(count) { Outlet(name=if(it==0) "=1+1" else "Outlet ${it+1}",address="Jalan Sentani Jayapura Papua",quantities=List(3) { Quantity(10,3) }) })
            val sheets=Layout.calls(report); assertEquals((count+29)/30,sheets.size)
            val pdf=ByteArrayOutputStream(); PdfExport.write(sheets,pdf)
            PDDocument.load(pdf.toByteArray()).use { doc -> assertEquals(sheets.size,doc.numberOfPages); assertEquals(841.8898f,doc.getPage(0).mediaBox.width,0.01f) }
            val xlsx=ByteArrayOutputStream(); ExcelExport.write(sheets,xlsx)
            XSSFWorkbook(xlsx.toByteArray().inputStream()).use { wb ->
                assertEquals(CellType.STRING,wb.getSheetAt(0).getRow(5).getCell(1).cellType); assertEquals("=1+1",wb.getSheetAt(0).getRow(5).getCell(1).stringCellValue)
                val sheet=wb.getSheetAt(0); sheets.first().widths.indices.forEach { assertTrue("Positive Excel column width",sheet.getColumnWidth(it)>0) }; assertEquals(9.toShort(),sheet.printSetup.paperSize); assertTrue(sheet.printSetup.landscape)
                val eval=wb.creationHelper.createFormulaEvaluator(); val cell=sheet.getRow(35).getCell(Layout.callColumn(3))
                assertEquals(minOf(count,30)*10.0,eval.evaluate(cell).numberValue,0.0)
                sheet.getRow(5).getCell(Layout.callColumn(3)).setCellValue(20.0); eval.clearAllCachedResultValues(); assertEquals(minOf(count,30)*10.0+10,eval.evaluate(cell).numberValue,0.0)
            }
            if(count==30) { val dir=File("build/verification").apply { mkdirs() }; File(dir,"CALLSHEET_sample.pdf").writeBytes(pdf.toByteArray()); File(dir,"CALLSHEET_sample.xlsx").writeBytes(xlsx.toByteArray()) }
        }
    }
    @Test fun salesOnlyCallsheetKeepsBlankStockColumnsAndHistoricalValues() {
        val visits=listOf(100L,200L,50L).mapIndexed { index,sold ->
            Outlet(name="TOKO ${index+1}",quantities=listOf(Quantity(),Quantity(sold=sold),Quantity()))
        }
        for(historicalStock in listOf(null,42L)) {
            val first=visits.first().copy(quantities=listOf(Quantity(),Quantity(stock=historicalStock,sold=100),Quantity()))
            val sheet=Layout.calls(CallReport(outlets=listOf(first)+visits.drop(1))).single()
            val pdf=ByteArrayOutputStream().also { PdfExport.write(listOf(sheet),it) }
            PDDocument.load(pdf.toByteArray()).use { doc ->
                assertEquals(841.8898f,doc.getPage(0).mediaBox.width,0.01f)
                assertEquals(595.2756f,doc.getPage(0).mediaBox.height,0.01f)
                val text=PDFTextStripper().getText(doc)
                assertEquals(3,Regex("Stok").findAll(text).count())
                assertTrue(text.contains("350"))
                assertEquals(historicalStock!=null,text.contains("42"))
            }
            val xlsx=ByteArrayOutputStream().also { ExcelExport.write(listOf(sheet),it) }
            XSSFWorkbook(xlsx.toByteArray().inputStream()).use { workbook ->
                val cells=workbook.getSheetAt(0)
                val evaluator=workbook.creationHelper.createFormulaEvaluator()
                assertEquals(9.toShort(),cells.printSetup.paperSize)
                assertTrue(cells.printSetup.landscape)
                for(product in Product.entries.indices) {
                    val stock=Layout.callColumn(3+product*2)
                    val sold=Layout.callColumn(4+product*2)
                    assertEquals("Stok",cells.getRow(4).getCell(stock).stringCellValue)
                    val stockTotal=cells.getRow(35).getCell(stock)
                    val soldTotal=cells.getRow(35).getCell(sold)
                    assertEquals(CellType.FORMULA,stockTotal.cellType)
                    assertEquals(CellType.FORMULA,soldTotal.cellType)
                    if(product==1 && historicalStock!=null) {
                        assertEquals(historicalStock.toDouble(),cells.getRow(5).getCell(stock).numericCellValue,0.0)
                        assertEquals(historicalStock.toDouble(),evaluator.evaluate(stockTotal).numberValue,0.0)
                    } else {
                        assertEquals(CellType.BLANK,cells.getRow(5).getCell(stock).cellType)
                        assertEquals("",evaluator.evaluate(stockTotal).stringValue)
                    }
                    if(product==1) assertEquals(350.0,evaluator.evaluate(soldTotal).numberValue,0.0)
                    else assertEquals("",evaluator.evaluate(soldTotal).stringValue)
                }
            }
            if(historicalStock==null) {
                val dir=File("build/verification").apply { mkdirs() }
                File(dir,"CALLSHEET_sales_only.xlsx").writeBytes(xlsx.toByteArray())
            }
        }
    }
    @Test fun excelRecalculatesAllDailyFormulaFamilies() {
        val output=ByteArrayOutputStream(); ExcelExport.write(listOf(Layout.daily(fixture)),output)
        val dir=File("build/verification").apply { mkdirs() }; File(dir,"LHS_sample.xlsx").writeBytes(output.toByteArray())
        XSSFWorkbook(output.toByteArray().inputStream()).use { wb ->
            val sh=wb.getSheetAt(0); val eval=wb.creationHelper.createFormulaEvaluator()
            fun value(row:Int,col:Int)=eval.evaluate(sh.getRow(row).getCell(col)).numberValue
            assertEquals(17.0,value(9,4),0.0); assertEquals(80.0,value(12,4),0.0); assertEquals(179000.0,value(11,20),0.0)
            assertEquals(7.0,value(35,3),0.0); assertEquals(537000.0,value(39,5),0.0); assertEquals(462000.0,value(45,5),0.0)
            sh.getRow(8).getCell(13).setCellValue(20.0); sh.getRow(8).getCell(16).setCellValue(15000.0); eval.clearAllCachedResultValues()
            assertEquals(27.0,value(9,4),0.0); assertEquals(70.0,value(12,4),0.0); assertEquals(379000.0,value(11,20),0.0); assertEquals(737000.0,value(39,5),0.0); assertEquals(662000.0,value(45,5),0.0)
            assertEquals(9.toShort(),sh.printSetup.paperSize); assertFalse(sh.printSetup.landscape); assertEquals(1.toShort(),sh.printSetup.fitWidth)
            assertNotNull(wb.getPrintArea(0)); assertTrue(wb.forceFormulaRecalculation)
            for(row in sh) for(cell in row) if(cell.cellType==CellType.FORMULA) assertNotEquals("${cell.address}",CellType.ERROR,eval.evaluate(cell).cellType)
        }
    }
    @Test fun excelBlankMissingPriceAndManualTg() {
        for(r in listOf(DailyReport(),DailyReport(products=listOf(Stock(delivery=10,sales=listOf(Sale(0,null),Sale(),Sale())),Stock(),Stock())),DailyReport(products=listOf(Stock(delivery=10,sales=listOf(Sale(2,null),Sale(),Sale())),Stock(),Stock())),fixture.copy(rules=Rules(automaticTg=false),products=fixture.products.map { it.copy(manualTg=42) },testers=fixture.testers.map { it.copy(manualTg=6) }))) {
            val output=ByteArrayOutputStream(); ExcelExport.write(listOf(Layout.daily(r)),output)
            XSSFWorkbook(output.toByteArray().inputStream()).use { wb ->
                val eval=wb.creationHelper.createFormulaEvaluator(); val sh=wb.getSheetAt(0)
                for(row in sh) for(cell in row) if(cell.cellType==CellType.FORMULA) assertNotEquals("${cell.address}",CellType.ERROR,eval.evaluate(cell).cellType)
                fun nullable(row:Int,col:Int):Long? { val c=sh.getRow(row).getCell(col); val v=eval.evaluate(c); return if(v?.cellType==CellType.NUMERIC) v.numberValue.toLong() else null }
                assertEquals(Calculation.total(r),nullable(39,5)); assertEquals(Calculation.balance(r),nullable(45,5)); assertEquals(Calculation.tg(r.products[0],r.rules),nullable(12,4))
            }
        }
    }
    @Test fun longNamesAddressesWrapWithoutTruncation() {
        val name="Toko Berkat Papua Cabang Sentani Jayapura"
        val address="Jalan Raya Sentani Nomor 123, dekat pasar tradisional, Kabupaten Jayapura, Papua"
        val out=ByteArrayOutputStream(); PdfExport.write(Layout.calls(CallReport(outlets=listOf(Outlet(name=name,address=address)))),out)
        PDDocument.load(out.toByteArray()).use { pdf -> val text=PDFTextStripper().getText(pdf); assertTrue(text.contains("Berkat")); assertTrue(text.contains("Kabupaten")) }
    }
}
