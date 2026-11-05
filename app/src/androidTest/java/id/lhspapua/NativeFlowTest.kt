package id.lhspapua

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import android.content.Context
import java.io.File
import androidx.test.core.app.ApplicationProvider
import id.lhspapua.ui.NativePdfPreview

/** Device acceptance smoke; not counted as physical-device verification until connectedDebugAndroidTest runs. */
class NativeFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    @Test fun nativeCompactCallInput() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("CALLSHEET").fetchSemanticsNodes().isNotEmpty() }
        click("CALLSHEET");click("Buat Laporan Baru")
        compose.onNodeWithText("Nama Sales").performScrollTo().performTextReplacement("Sales Uji Perangkat")
        click("Gunakan Identitas");click("Tambah Outlet")
        compose.onNodeWithText("Nama Outlet").performScrollTo().performTextInput("Toko Uji")
        compose.onNodeWithText("Alamat").performScrollTo().performTextInput("Jayapura")
        repeat(3) { p->compose.onNodeWithTag("call-$p-stock").performScrollTo().performTextReplacement("10");compose.onNodeWithTag("call-$p-sold").performScrollTo().performTextReplacement("3") }
        click("Simpan & Tambah Outlet");click("Daftar Kunjungan")
        compose.onNodeWithText("1. Toko Uji").performScrollTo().assertExists();click("Periksa Laporan")
        compose.onAllNodesWithText("Stok 10 · Jual 3").assertCountEquals(3)
    }    @Test fun nativePreviewRendersAndNavigatesTwoPages() {
        val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.cacheDir,"preview.pdf")
        val r=id.lhspapua.domain.CallReport(outlets=List(31) { id.lhspapua.domain.Outlet(name="Outlet $it") })
        file.outputStream().use { id.lhspapua.export.PdfExport.write(id.lhspapua.export.Layout.calls(r),it) }
        val first=NativePdfPreview.render(file,0,1024);val second=NativePdfPreview.render(file,1,1024)
        assertEquals(2,first.pageCount);assertEquals(2,second.pageCount);assertEquals(1024,first.bitmap.width)
        assertTrue(first.bitmap.height<first.bitmap.width)
        var ink=0
        for(y in 0 until first.bitmap.height step 4)for(x in 0 until first.bitmap.width step 4)if(first.bitmap.getPixel(x,y)!=android.graphics.Color.WHITE)ink++
        assertTrue("PDF page must contain rendered vector text and table lines",ink>100)
        first.bitmap.recycle();second.bitmap.recycle()
    }

}
