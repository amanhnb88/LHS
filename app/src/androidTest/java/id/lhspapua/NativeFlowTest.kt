@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package id.lhspapua

import androidx.compose.ui.semantics.SemanticsProperties
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
import id.lhspapua.export.ExportFormat
import java.io.File
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import id.lhspapua.domain.Calculation
import id.lhspapua.domain.LhsFeatureSettings
import id.lhspapua.domain.Product
import id.lhspapua.domain.untouched
import id.lhspapua.ui.LhsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import id.lhspapua.ui.NativePdfPreview

/** Device acceptance smoke; not counted as physical-device verification until connectedDebugAndroidTest runs. */
class NativeFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    private fun tab(route:String)=compose.onNodeWithTag("tab-$route")
    private fun start()=compose.waitUntil(15000) { compose.onAllNodesWithText("Ringkasan Penjualan").fetchSemanticsNodes().isNotEmpty() }
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
        val vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        compose.runOnIdle { vm.rememberProfile(vm.state.value.settings.profile.copy(sales="Sales Uji Perangkat")) }
        compose.waitUntil(15000) { vm.state.value.pending==0 && !vm.state.value.saveFailed }
        tab("calls").performClick()
        click("Buat Laporan Baru")
        click("Tambah Outlet")
        compose.onNodeWithTag("Nama Outlet").performScrollTo().performTextReplacement("Toko Uji")
        val address=compose.onNodeWithTag("Alamat").performScrollTo()
        address.performTextReplacement("jalan sorong")
        address.performTextInputSelection(androidx.compose.ui.text.TextRange(6))
        address.performTextInput("aimas ")
        address.assertTextContains("JALAN AIMAS SORONG")
        for(product in Product.entries.indices) {
            compose.onNodeWithTag("call-$product-stock").assertDoesNotExist()
            compose.onNodeWithTag("call-$product-sold").assertExists()
        }
        val field=compose.onNodeWithTag("call-2-sold").performScrollTo()
        field.performTextReplacement("200")
        field.performTouchInput { click() }
        compose.waitUntil(15000) { imeVisible() }
        compose.onNodeWithText("Simpan & Tambah Outlet").assertIsDisplayed()
        back()
        compose.waitUntil(10000) { !imeVisible() }
        field.assertExists()
        field.performScrollTo().performTouchInput { click() }
        compose.waitUntil(15000) { imeVisible() }
        field.performScrollTo().assertIsDisplayed()
        tab("calls").assertIsDisplayed().assertIsSelected()
        listOf("home","days","drafts","history").forEach { tab(it).assertIsDisplayed() }
        compose.onNode(hasText("Simpan & Tambah Outlet") and hasClickAction()).performTouchInput { click() }
        compose.waitUntil(15000) { !imeVisible() }
        compose.onNodeWithTag("Nama Outlet").assertExists()
        assertTrue(vm.state.value.call!!.outlets.last().untouched())
        val visit=vm.state.value.call!!.outlets.first()
        assertEquals("TOKO UJI",visit.name)
        assertEquals("JALAN AIMAS SORONG",visit.address)
        assertEquals("SALES UJI PERANGKAT",vm.state.value.call!!.identity.sales)
        tab("home").performClick()
        tab("calls").performClick()
        compose.onNodeWithText("Kunjungan Baru").assertExists()
        back()
        compose.onNodeWithText("1. TOKO UJI").performScrollTo().assertExists()
        click("Periksa Laporan")
        compose.onNodeWithTag("call-sold-2").assertTextEquals("200 bungkus")
        compose.onNodeWithTag("call-total-sold").assertTextEquals("200 BUNGKUS")
        compose.onNodeWithText("Stok",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Pratinjau JPG").performScrollTo().assertIsEnabled()
        assertNull(vm.state.value.preview)
        click("PDF")
        click("Pratinjau Draf PDF")
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Halaman 1 dari 1").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.onNodeWithText("Pratinjau PDF").assertDoesNotExist()
        compose.onNodeWithText("Periksa Laporan").assertExists()
        back()
        compose.onNodeWithText("1. TOKO UJI").performScrollTo().assertExists()
        back()
        tab("calls").assertIsSelected()
    }

    @Test fun tabsSettingsRecreationAndSafeExit() {
        val vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        compose.waitUntil(20000) { vm.state.value.ready && vm.state.value.pending==0 }
        val counts=runBlocking { vm.repository.calls.first().size to vm.repository.allDaily().size }
        for(route in listOf("calls","days","drafts","history","home")) {
            tab(route).performClick()
            tab(route).assertIsSelected()
        }
        assertEquals(counts,runBlocking { vm.repository.calls.first().size to vm.repository.allDaily().size })
        for(route in listOf("home","calls","days")) {
            tab(route).performClick()
            compose.onNodeWithContentDescription("Pengaturan").performClick()
            click("Integrasi Laporan")
            compose.onNodeWithTag("integration-mode-0").assertExists()
            back()
            click("Pengaturan Fitur LHS")
            compose.onNodeWithTag("feature-stock").assertExists()
            back()
            back()
            tab(route).assertIsSelected()
        }
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
        tab("days").assertIsDisplayed().assertIsSelected()
        val vm=compose.runOnIdle { ViewModelProvider(compose.activity)[LhsViewModel::class.java] }
        fun field(tag:String)=compose.onNodeWithTag(tag).performScrollTo()
        fun input(tag:String,value:String) { field(tag).performTextReplacement(value) }
        fun saved()=compose.waitUntil(20000) { vm.state.value.let { it.pending==0 && !it.busy && !it.saveFailed } }
        val id=vm.state.value.day!!.id

        for(group in listOf("daily-section","daily-product")) {
            val bounds=(0..2).map { compose.onNodeWithTag("$group-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            assertTrue(bounds.maxOf { it.width } - bounds.minOf { it.width } <= 1f)
            assertTrue(bounds.all { it.height==bounds.first().height && it.top==bounds.first().top })
            compose.onNodeWithTag("$group-0").assertIsSelected()
        }
        listOf("do","sold-auto","sample","bonus","tg").forEach { suffix ->
            compose.onNodeWithTag("stock-0-$suffix").assertExists()
        }
        compose.runOnIdle { vm.settings { it.copy(lhsFeatures=LhsFeatureSettings()) } }
        saved()
        val disabledDelivery=field("stock-0-do").assertIsNotEnabled()
        disabledDelivery.performTouchInput { click() }
        disabledDelivery.assert(!SemanticsMatcher.expectValue(SemanticsProperties.Focused,true))
        assertFalse(imeVisible())
        compose.runOnIdle { vm.settings { it.copy(lhsFeatures=LhsFeatureSettings(true,true,true)) } }
        saved()
        val delivery=field("stock-0-do")
        delivery.performTouchInput { click() }
        compose.waitUntil(15000) {
            compose.runOnIdle {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        delivery.assertIsDisplayed()
        tab("days").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Periksa Laporan").assertIsDisplayed()
        delivery.performImeAction()
        field("stock-0-sample").assertIsFocused()
        compose.onNodeWithTag("stock-0-sold").assertDoesNotExist()
        input("stock-0-sample","5")
        input("stock-0-tg","25")
        input("sale-0-G","10")
        input("sale-0-RTL","20")
        field("sale-0-RTL").performImeAction()
        hideIme()
        click(Product.MARTIL.label)
        input("sale-1-G","200")
        input("sale-1-RTL","500")
        field("stock-1-sold-auto").assertTextContains("700 BUNGKUS")
        input("sale-1-RTL","600")
        field("stock-1-sold-auto").assertTextContains("800 BUNGKUS")
        input("sale-1-G","100")
        field("stock-1-sold-auto").assertTextContains("700 BUNGKUS")
        input("sale-1-G","200")
        input("sale-1-RTL","500")
        field("sale-1-RTL").performImeAction()
        hideIme()
        click(Product.HD_LIGHT.label)
        input("sale-2-G","10")
        input("sale-2-RTL","10")
        field("sale-2-RTL").performImeAction()

        hideIme()
        click("Tester")
        tab("days").assertIsDisplayed().assertIsSelected()
        input("tester-2-do","10")
        input("tester-2-sample","3")
        input("tester-2-tg","5")
        field("tester-2-tg").performImeAction()
        hideIme()
        click("Keuangan")
        tab("days").assertIsDisplayed().assertIsSelected()
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
        compose.onNodeWithText("Lanjutkan Laporan").assertDoesNotExist()
        tab("drafts").performClick()
        compose.onNodeWithTag("report-filter-2").performClick()
        compose.onNode(hasText("Lanjutkan") and hasAnyAncestor(hasTestTag("report-$id"))).performClick()
        assertEquals(id,vm.state.value.day!!.id)
        click("Stok & Penjualan")
        hideIme()
        click(Product.MARTIL.label)
        field("stock-1-sold-auto").assertTextContains("700 BUNGKUS")
        hideIme()
        click(Product.MARTIL_12.label)
        field("stock-0-sold-auto").assertTextContains("30 BUNGKUS")
        click("Periksa Laporan")
        compose.onNodeWithText("Total Penjualan: Rp 11.535.000").performScrollTo().assertExists()
        compose.onNodeWithText("Saldo Akhir: Rp 12.345").assertExists()
        compose.onNodeWithText("Simpan JPG").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Bagikan JPG").assertIsEnabled()
        click("Pratinjau JPG")
        compose.waitUntil(15000) { vm.state.value.preview!=null }
        assertEquals("Pratinjau JPG",vm.state.value.previewTitle)
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Halaman 1 dari 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Kembali") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000) { vm.state.value.preview==null }
        compose.onNodeWithText("Simpan JPG").performScrollTo().assertIsEnabled()
        click("PDF")
        compose.onNodeWithText("Simpan PDF").performScrollTo().assertIsEnabled()
        click("Excel")
        compose.onNodeWithText("Simpan Excel").performScrollTo().assertIsEnabled()
        compose.runOnIdle { vm.export() }
        saved()
        compose.runOnIdle { vm.export(ExportFormat.EXCEL) }
        saved()
        assertTrue(vm.state.value.pdf?.isFile==true)
        assertTrue(vm.state.value.xlsx?.isFile==true)
        click("JPG")
        compose.onNodeWithText("Simpan JPG").performScrollTo().assertIsEnabled()
        var jpg:File?=null
        compose.runOnIdle { vm.export(ExportFormat.JPG) { jpg=it.single() } }
        saved()
        val jpeg=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeFile(requireNotNull(jpg).path,jpeg)
        assertEquals("image/jpeg",jpeg.outMimeType)
        assertEquals(2480,jpeg.outWidth)
        assertEquals(3508,jpeg.outHeight)
        val persisted=runBlocking { requireNotNull(vm.repository.daily(id)) }
        assertNull(persisted.products[0].delivery)
        assertEquals(25L,Calculation.stockTg(persisted,0))
        assertEquals(700L,Calculation.stockSold(persisted,1))
        assertNull(persisted.products[1].manualSold)
        assertEquals(5L,Calculation.testerTg(persisted,2))
        assertEquals(12345L,Calculation.balance(persisted))
    }

}
