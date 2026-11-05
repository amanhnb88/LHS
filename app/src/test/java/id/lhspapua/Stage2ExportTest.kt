package id.lhspapua

import id.lhspapua.domain.*
import id.lhspapua.export.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Stage2ExportTest {
    @Test fun openingStockLivesOnlyOnHiddenSheetAndFormulasRecalculate() {
        val r=StockFixture.report();val output=ByteArrayOutputStream()
        ExcelExport.write(Layout.dailyWorkbook(r),output)
        File("build/verification").mkdirs();File("build/verification/LHS_stage2.xlsx").writeBytes(output.toByteArray())
        XSSFWorkbook(output.toByteArray().inputStream()).use { wb->
            assertEquals(2,wb.numberOfSheets);assertTrue(wb.isSheetHidden(1));assertNull(wb.getPrintArea(1))
            val sh=wb.getSheetAt(0);val helper=wb.getSheetAt(1);val eval=wb.creationHelper.createFormulaEvaluator()
            fun num(row:Int,col:Int)=eval.evaluate(sh.getRow(row).getCell(col)).numberValue
            assertEquals(150.0,num(9,4),0.0);assertEquals(85.0,num(12,4),0.0);assertEquals(12.0,num(35,3),0.0)
            assertEquals(4500000.0,num(39,5),0.0);assertEquals(4500000.0,num(45,5),0.0)
            helper.getRow(1).getCell(1).setCellValue(85.0);sh.getRow(8).getCell(4).setCellValue(100.0)
            sh.getRow(8).getCell(13).setCellValue(100.0);sh.getRow(9).getCell(13).setCellValue(20.0);sh.getRow(10).getCell(13).setCellValue(0.0)
            sh.getRow(10).getCell(4).setCellValue(5.0);sh.getRow(11).getCell(4).setCellValue(0.0)
            helper.getRow(1).getCell(2).setCellValue(12.0);sh.getRow(33).getCell(3).setCellValue(1.0);sh.getRow(34).getCell(3).setCellValue(2.0)
            eval.clearAllCachedResultValues();assertEquals(60.0,num(12,4),0.0);assertEquals(11.0,num(35,3),0.0)
            for(row in sh)for(cell in row)if(cell.cellType==CellType.FORMULA)assertNotEquals(cell.address.toString(),CellType.ERROR,eval.evaluate(cell).cellType)
            assertEquals(9.toShort(),sh.printSetup.paperSize);assertFalse(sh.printSetup.landscape);assertTrue(wb.forceFormulaRecalculation)
            assertFalse(sh.any { row->row.any { cell->cell.cellType==CellType.STRING && cell.stringCellValue=="Stok Awal" } })
        }
    }
    @Test fun officialVisibleGridUnchangedAndA4HasOnePage() {
        val current=Layout.daily(StockFixture.report());val legacy=Layout.daily(DailyReport(identity=StockFixture.report().identity,finance=StockFixture.report().finance))
        assertEquals(legacy.widths,current.widths);assertEquals(legacy.heights,current.heights)
        assertEquals(legacy.cells.map { listOf(it.row,it.col,it.rows,it.cols,it.text,it.edges) },current.cells.map { listOf(it.row,it.col,it.rows,it.cols,it.text,it.edges) })
        val out=ByteArrayOutputStream();PdfExport.write(listOf(current),out)
        File("build/verification").mkdirs();File("build/verification/LHS_stage2.pdf").writeBytes(out.toByteArray())
        PDDocument.load(out.toByteArray()).use { pdf->assertEquals(1,pdf.numberOfPages);assertEquals(595.2756f,pdf.getPage(0).mediaBox.width,0.01f);assertEquals(841.8898f,pdf.getPage(0).mediaBox.height,0.01f) }
    }
    @Test fun manualFinanceAndIncompleteOpeningsMatchApp() {
        for(r in listOf(StockFixture.report().let { it.copy(rules=Rules(automaticFinance=false),finance=it.finance.copy(manualBalance=-25000)) },StockFixture.auto("2026-11-02"))) {
            val out=ByteArrayOutputStream();ExcelExport.write(Layout.dailyWorkbook(r),out)
            XSSFWorkbook(out.toByteArray().inputStream()).use { wb->val sh=wb.getSheetAt(0);val eval=wb.creationHelper.createFormulaEvaluator()
                fun nullable(row:Int,col:Int):Long? { val value=eval.evaluate(sh.getRow(row).getCell(col));return if(value?.cellType==CellType.NUMERIC)value.numberValue.toLong() else null }
                assertEquals(Calculation.tg(r.products[0],r.rules),nullable(12,4));assertEquals(Calculation.tg(r.testers[0],r.rules),nullable(35,3));assertEquals(Calculation.balance(r),nullable(45,5))
                if(!r.rules.automaticFinance)assertEquals(CellType.NUMERIC,sh.getRow(45).getCell(5).cellType)
            }
        }
    }
}
