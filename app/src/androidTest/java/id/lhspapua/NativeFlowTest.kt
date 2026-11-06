@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package id.lhspapua

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import id.lhspapua.data.Repository
import java.io.File
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import id.lhspapua.domain.Calculation
import id.lhspapua.domain.Product
import id.lhspapua.ui.LhsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import id.lhspapua.ui.NativePdfPreview

/** Device acceptance smoke; not counted as physical-device verification until connectedDebugAndroidTest runs. */
class NativeFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    private fun tab(route:String)=compose.onNodeWithTag("tab-$route")
    private fun start()=compose.waitUntil(15000) { compose.onAllNodesWithText("Belum ada draf").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Lanjutkan Laporan").fetchSemanticsNodes().isNotEmpty() }
    private fun back()=InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun imeVisible()=compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true
    }
    private fun hideIme() {
        if(imeVisible())back()
        compose.waitUntil(10000) { !imeVisible() }
    }

    @Test fun nativeCompactCallInput() {
        start()
        tab("calls").performClick()
        click("Buat Laporan Baru")
        if(compose.onAllNodesWithTag("Nama Sales").fetchSemanticsNodes().isEmpty())click("Ubah tanggal / identitas")
        compose.onNodeWithTag("Nama Sales").performScrollTo().performTextReplacement("Sales Uji Perangkat")
        hideIme()
        click("Gunakan Identitas");click("Tambah Outlet")
        compose.onNodeWithTag("Nama Outlet").performScrollTo().performTextReplacement("Toko Uji")
        compose.onNodeWithTag("Alamat").performScrollTo().performTextReplacement("Jayapura")
        val field=compose.onNodeWithTag("call-1-sold").performScrollTo()
        field.performTouchInput { click() }
        compose.waitUntil(15000) { imeVisible() }
        field.performTextReplacement("200")
        compose.onNodeWithText("Simpan & Tambah Outlet").assertDoesNotExist()
        back()
        compose.waitUntil(10000) { !imeVisible() }
        field.assertExists()
        back()
        compose.onNodeWithText("1. Toko Uji").performScrollTo().assertExists()
        click("Periksa Laporan")
        compose.onNodeWithText("Stok — · Jual 200").assertExists()
        click("Pratinjau Draf PDF")
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Halaman 1 dari 1").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.onNodeWithText("Pratinjau PDF").assertDoesNotExist()
        compose.onNodeWithText("Periksa Laporan").assertExists()
        back()
        compose.onNodeWithText("1. Toko Uji").performScrollTo().assertExists()
        back()
        tab("calls").assertIsSelected()
    }

    @Test fun tabsSettingsRecreationAndSafeExit() {
        val vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        compose.waitUntil(20000) { vm.state.value.ready && vm.state.value.pending==0 }
        val counts=runBlocking { vm.repository.calls.first().size to vm.repository.allDaily().size }
        for(route in listOf("calls","days","history","home")) {
            tab(route).performClick()
            tab(route).assertIsSelected()
        }
        assertEquals(counts,runBlocking { vm.repository.calls.first().size to vm.repository.allDaily().size })
        tab("history").performClick()
        compose.onNodeWithContentDescription("Pengaturan").performClick()
        click("Profil Sales")
        back()
        compose.onNode(hasText("Daftar Outlet") and hasClickAction()).assertExists()
        back()
        tab("history").assertIsSelected()
        back()
        tab("home").assertIsSelected()
        back()
        compose.onNodeWithText("Keluar dari LHS Papua?").assertExists()
        back()
        compose.onNodeWithText("Keluar dari LHS Papua?").assertDoesNotExist()
        back()
        click("Batal")
        tab("home").assertIsSelected()

        tab("calls").performClick()
        click("Buat Laporan Baru")
        val id=vm.state.value.call!!.id
        compose.runOnIdle { vm.call { it.copy(identity=it.identity.copy(sales="Simpan Saat Keluar")) } }
        compose.activityRule.scenario.recreate()
        assertEquals(id,vm.state.value.call!!.id)
        compose.onNodeWithTag("outlet-search").assertExists()
        back()
        tab("calls").assertIsSelected()
        back()
        back()
        click("Keluar")
        val deadline=android.os.SystemClock.uptimeMillis()+20000
        while(compose.activityRule.scenario.state!=Lifecycle.State.DESTROYED && android.os.SystemClock.uptimeMillis()<deadline) {
            android.os.SystemClock.sleep(50)
        }
        assertEquals(Lifecycle.State.DESTROYED,compose.activityRule.scenario.state)
        ActivityScenario.launch(MainActivity::class.java).use {
            val ctx=ApplicationProvider.getApplicationContext<Context>()
            val stored=runBlocking { Repository(ctx).call(id) }
            assertEquals("Simpan Saat Keluar",stored?.identity?.sales)
        }
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
        start()
        tab("days").performClick()
        click("Buat Laporan Baru")
        val vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        fun field(tag:String)=compose.onNodeWithTag(tag).performScrollTo()
        fun input(tag:String,value:String) { field(tag).performTextReplacement(value) }
        fun saved()=compose.waitUntil(20000) { vm.state.value.let { it.pending==0 && !it.busy && !it.saveFailed } }
        val id=vm.state.value.day!!.id

        val delivery=field("stock-0-do")
        delivery.performTouchInput { click() }
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
        hideIme()
        click(Product.MARTIL.label)
        input("stock-1-sold","100")
        input("sale-1-G","20")
        input("sale-1-RTL","50")
        field("sale-1-RTL").performImeAction()
        hideIme()
        click(Product.HD_LIGHT.label)
        input("sale-2-G","10")
        input("sale-2-RTL","10")
        field("sale-2-RTL").performImeAction()

        hideIme()
        click("Tester")
        input("tester-2-do","10")
        input("tester-2-sample","3")
        input("tester-2-tg","5")
        field("tester-2-tg").performImeAction()
        hideIme()
        click("Keuangan")
        input("Saldo Sebelum","50000")
        input("Saldo Akhir","12345")
        field("Saldo Akhir").performImeAction()
        saved()
        hideIme()
        back()
        compose.onNodeWithTag("tester-2-tg").assertExists()
        back()
        compose.onNodeWithTag("sale-2-RTL").assertExists()
        back()
        tab("days").assertIsSelected()
        tab("home").performClick()
        compose.waitUntil(15000) { vm.state.value.days.any { it.id==id } }
        click("Lanjutkan Laporan")
        assertEquals(id,vm.state.value.day!!.id)
        click("Stok & Penjualan")
        hideIme()
        click(Product.MARTIL.label)
        input("stock-1-sold","125")
        hideIme()
        click(Product.MARTIL_12.label)
        field("stock-0-sold").assertTextContains("100")
        click("Periksa Laporan")
        compose.onNodeWithText("Total Penjualan: Rp 1.860.000").performScrollTo().assertExists()
        compose.onNodeWithText("Saldo Akhir: Rp 12.345").assertExists()
        compose.onNodeWithText("Simpan PDF").performScrollTo().assertIsEnabled()
        click("Excel")
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
