package id.lhspapua

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import id.lhspapua.domain.*
import id.lhspapua.export.JpegExport
import id.lhspapua.export.Layout
import id.lhspapua.export.PdfExport
import id.lhspapua.export.Sheet
import id.lhspapua.ui.ShareFiles
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class JpegExportTest {
    @Test fun rendersOfficialPortraitAndAllLandscapePagesAndBuildsSharesheetPayload() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val folder=File(context.cacheDir,"exports/jpg-proof").apply { mkdirs() }
        fun export(name:String,sheets:List<Sheet>):List<File> {
            val pdf=File(folder,"$name.pdf")
            pdf.outputStream().use { PdfExport.write(sheets,it) }
            return JpegExport.write(pdf,folder,name)
        }
        fun visits(count:Int)=CallReport(
            identity=Identity(sales="Maikel"),
            outlets=List(count) { Outlet(name="Outlet ${it+1}",address="Jayapura",quantities=listOf(Quantity(),Quantity(sold=(it+1).toLong()),Quantity())) }
        )
        fun verify(file:File,width:Int,height:Int) {
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(file.path,options)
            assertEquals("image/jpeg",options.outMimeType)
            assertEquals(width,options.outWidth)
            assertEquals(height,options.outHeight)
            val sample=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=4 })
            try {
                assertEquals(-1,sample.getPixel(0,0))
                val blackPixels=(0 until sample.height step 2).sumOf { y->
                    (0 until sample.width step 2).count { x->(sample.getPixel(x,y) and 255)<120 }
                }
                assertTrue("Missing form in ${file.name}",blackPixels>300)
            } finally { sample.recycle() }
        }

        val three=visits(3).let { report ->
            report.copy(outlets=report.outlets.zip(listOf(100L,200L,50L)) { outlet,sold ->
                outlet.copy(quantities=listOf(Quantity(),Quantity(sold=sold),Quantity()))
            })
        }
        assertEquals(350L,Calculation.calls(three,1,false))
        assertNull(Calculation.calls(three,1,true))
        val single=export("CALLSHEET_3",Layout.calls(three))
        verify(single.single(),3508,2480)
        val pages=export("CALLSHEET_65",Layout.calls(visits(65)))
        assertEquals(listOf("CALLSHEET_65_01.jpg","CALLSHEET_65_02.jpg","CALLSHEET_65_03.jpg"),pages.map { it.name })
        pages.forEach { verify(it,3508,2480) }
        assertEquals(3,pages.map { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }.distinct().size)
        val multiple=ShareFiles.intent(context,pages)
        assertEquals(Intent.ACTION_SEND_MULTIPLE,multiple.action)
        assertEquals("image/jpeg",multiple.type)
        assertEquals(3,multiple.clipData?.itemCount)
        assertTrue(multiple.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val one=ShareFiles.intent(context,single)
        assertEquals(Intent.ACTION_SEND,one.action)
        assertEquals("image/jpeg",one.type)

        val draft=newDaily(Identity(sales="Maikel"))
        val martil=draft.products[1]
        val products=draft.products.toMutableList()
        products[1]=martil.copy(sales=martil.sales.mapIndexed { i,sale->when(i) { 0->sale.copy(quantity=200);2->sale.copy(quantity=500);else->sale } })
        val report=draft.copy(products=products)
        assertEquals(10750000L,Calculation.total(report))
        assertEquals(700L,Calculation.stockSold(report,1))
        verify(export("LHS_700",listOf(Layout.daily(report))).single(),2480,3508)

        // Publish only fixture output so CI can retrieve it after instrumentation cleanup.
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val resolver=context.contentResolver
            for(file in folder.listFiles().orEmpty()) {
                val values=ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE,if(file.extension=="jpg")"image/jpeg" else "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/LHSProof")
                    put(MediaStore.MediaColumns.IS_PENDING,1)
                }
                val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                requireNotNull(resolver.openOutputStream(uri)).use { out->file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            }
        }
    }
}
