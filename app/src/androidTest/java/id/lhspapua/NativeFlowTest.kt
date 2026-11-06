@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package id.lhspapua

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import android.content.Context
import java.io.File
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import id.lhspapua.domain.Calculation
import id.lhspapua.domain.Product
import id.lhspapua.ui.LhsViewModel
import kotlinx.coroutines.runBlocking
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
        click("Simpan & Tambah Outlet")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Daftar Kunjungan").fetchSemanticsNodes().isNotEmpty() }
        click("Daftar Kunjungan")
        compose.onNodeWithText("1. Toko Uji").performScrollTo().assertExists();click("Periksa Laporan")
        compose.onAllNodesWithText("Stok 10 · Jual 3").assertCountEquals(3)
    }

    @Test fun nativePreviewRendersAndNavigatesTwoPages() {
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

    @Test fun nativeDailyKeepsManualValuesThroughResumeAndExport() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("LAPORAN HARIAN").fetchSemanticsNodes().isNotEmpty() }
        click("LAPORAN HARIAN")
        click("Buat Laporan Baru")
        val vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        fun field(tag:String)=compose.onNodeWithTag(tag).performScrollTo()
        fun input(tag:String,value:String) { field(tag).performTextReplacement(value) }
        fun saved()=compose.waitUntil(20000) { vm.state.value.let { it.pending==0 && !it.busy && !it.saveFailed } }
        val id=vm.state.value.day!!.id

        val delivery=field("stock-0-do")
        delivery.performClick()
        compose.waitUntil(15000) {
            compose.runOnIdle {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        delivery.assertIsDisplayed()
        delivery.performImeAction()
        field("stock-0-sold").assertIsFocused()
        input("stock-0-sold","100")
        input("stock-0-sample","5")
        input("stock-0-tg","25")
        input("sale-0-G","10")
        input("sale-0-RTL","20")
        field("sale-0-RTL").performImeAction()
        click(Product.MARTIL.label)
        input("stock-1-sold","100")
        input("sale-1-G","20")
        input("sale-1-RTL","50")
        field("sale-1-RTL").performImeAction()
        click(Product.HD_LIGHT.label)
        input("sale-2-G","10")
        input("sale-2-RTL","10")
        field("sale-2-RTL").performImeAction()

        click("Tester")
        input("tester-2-do","10")
        input("tester-2-sample","3")
        input("tester-2-tg","5")
        field("tester-2-tg").performImeAction()
        click("Keuangan")
        input("Saldo Sebelum","50000")
        input("Saldo Akhir","12345")
        field("Saldo Akhir").performImeAction()
        saved()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(15000) { vm.state.value.screen=="home" && vm.state.value.days.any { it.id==id } }
        click("Lanjutkan Laporan")
        assertEquals(id,vm.state.value.day!!.id)
        click("Stok & Penjualan")
        click(Product.MARTIL.label)
        input("stock-1-sold","125")
        click("Periksa Laporan")
        compose.onNodeWithText("Total Penjualan: Rp 1.860.000").performScrollTo().assertExists()
        compose.onNodeWithText("Saldo Akhir: Rp 12.345").assertExists()
        compose.onNodeWithText("Simpan PDF").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Simpan Excel").performScrollTo().assertIsEnabled()
        compose.runOnIdle { vm.export() }
        saved()
        compose.runOnIdle { vm.export(xlsx=true) }
        saved()
        assertTrue(vm.state.value.pdf?.isFile==true)
        assertTrue(vm.state.value.xlsx?.isFile==true)
        val persisted=runBlocking { requireNotNull(vm.repository.daily(id)) }
        assertNull(persisted.products[0].delivery)
        assertEquals(25L,Calculation.stockTg(persisted,0))
        assertEquals(125L,Calculation.stockSold(persisted,1))
        assertEquals(70L,Calculation.sold(persisted.products[1]))
        assertEquals(5L,Calculation.testerTg(persisted,2))
        assertEquals(12345L,Calculation.balance(persisted))
    }

}
