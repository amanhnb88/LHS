@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package id.lhspapua

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import id.lhspapua.ui.*
import id.lhspapua.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import androidx.room.Room
import id.lhspapua.data.LhsDatabase
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-rUS-w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:LhsViewModel
    private lateinit var database:LhsDatabase
    private val databaseInstance get()=LhsDatabase::class.java.getDeclaredField("instance").apply { isAccessible=true }

    // StateFlow reads alone do not drain Robolectric's paused Android main queue.
    // runOnIdle synchronizes both Compose and Android callbacks before checking IO completion.
    private fun awaitState(description:String,condition:()->Boolean) {
        try { compose.waitUntil(20000) { compose.runOnIdle { condition() } } }
        catch(e:ComposeTimeoutException) {
            val s=vm.state.value
            throw AssertionError("$description: ready=${s.ready}, pending=${s.pending}, busy=${s.busy}, saved=${s.saved}, error=${s.error}",e)
        }
    }
    @Before fun isolate() {
        compose.runOnIdle {
            compose.activity.viewModelStore.clear()
            // Each scenario gets a real, isolated Room database, never a previous activity's singleton.
            (databaseInstance.get(null) as? LhsDatabase)?.close()
            database=Room.inMemoryDatabaseBuilder(compose.activity.application,LhsDatabase::class.java).build()
            databaseInstance.set(null,database)
            compose.activity.application.getSharedPreferences("navigation",0).edit().clear().commit()
            compose.activity.application.getSharedPreferences("draft-journal",0).edit().clear().commit()
            vm=LhsViewModel(compose.activity.application)
            compose.activity.setContent { LhsApp(vm) }
        }
        awaitState("Application initialization") { vm.state.value.ready }
        vm.navigate("home")
    }
    @After fun releaseScenario() {
        compose.runOnIdle { if(::vm.isInitialized)vm.viewModelScope.cancel() }
        if(::database.isInitialized)database.close()
        databaseInstance.set(null,null)
    }
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    private fun text(tag:String,value:String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun saved()=awaitState("Durable save") { vm.state.value.pending==0&&!vm.state.value.busy }
    private fun snapshot(name:String) {
        compose.runOnIdle { val view=compose.activity.window.decorView;val b=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b));val f=java.io.File("build/verification/$name.png");f.parentFile!!.mkdirs();f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun newCall() { click("CALLSHEET");click("Buat Laporan Baru");if(vm.state.value.call!!.identity.sales.isNotBlank())click("Ubah tanggal / identitas");text("Nama Sales","Sales Lapangan");click("Gunakan Identitas");saved() }
    @Test fun masterOutletIsFoundDirectlyAndDraftResumesWithoutDuplicates() {
        vm.settings { it.copy(outlets=listOf(SavedOutlet("Toko Aimas","Jalan Aimas"))) };saved()
        newCall();val id=vm.state.value.call!!.id
        awaitState("Callsheet appears in persisted history") { vm.state.value.calls.any { it.id==id } }
        val count=vm.state.value.calls.size
        text("outlet-search","aimas");click("Toko Aimas")
        compose.onNodeWithTag("Alamat").assertTextContains("Jalan Aimas")
        assertTrue(vm.state.value.call!!.outlets.last().quantities.all { it.stock==null&&it.sold==null })
        text("call-1-sold","200")
        snapshot("STAGE3_CALLSHEET");click("Simpan & Tambah Outlet");click("Daftar Kunjungan")
        // Finish the list recomposition before sending the real Android Back event.
        // Otherwise Back can still invoke the previous outlet editor callback.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitState("Android Back returns to home") { vm.state.value.screen=="home" }
        click("CALLSHEET")
        saved();assertEquals(count,vm.state.value.calls.size)
        compose.onAllNodesWithText("Lanjutkan").onFirst().performClick();assertEquals(id,vm.state.value.call!!.id)
        compose.onNodeWithText("1. Toko Aimas").performScrollTo().assertExists()
        val restored = vm.state.value.call!!.activeOutlets().single().quantities
        assertEquals(Quantity(sold=200), restored[1])
        assertEquals(Quantity(), restored[0])
        snapshot("STAGE3_VISITS")
    }
    @Test fun tenVisitsAllowSoldAboveStockAndPreserveNumbers() {
        newCall();click("Tambah Outlet")
        repeat(10) { n->text("Nama Outlet","Outlet Uji ${n+1}");text("Alamat","Jayapura")
            repeat(3) { p->text("call-$p-stock","10");text("call-$p-sold","3") }
            click("Simpan & Tambah Outlet")
        }
        click("Daftar Kunjungan");text("outlet-search","Outlet Uji 10");click("Edit Kunjungan")
        text("call-0-sold","11")
        compose.onNodeWithText("Melebihi stok").assertDoesNotExist()
        click("Simpan Perubahan")
        text("outlet-search","");click("Periksa Laporan")
        compose.onNodeWithText("Stok 100 · Jual 38").performScrollTo().assertExists()
        click("Edit Callsheet");text("outlet-search","Outlet Uji 10");click("Hapus");click("Batal")
        compose.onNodeWithText("10. Outlet Uji 10").assertExists();click("Hapus")
        compose.onNode(hasText("Hapus") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        saved();assertEquals(9,vm.state.value.call!!.activeOutlets().size)
    }
    @Test fun dailyManualInputsStayIndependentAndExportAsDraft() {
        click("LAPORAN HARIAN");click("Buat Laporan Baru");saved()
        val quantities = listOf(10 to 20,20 to 50,10 to 10)
        quantities.forEachIndexed { p,(gross,retail) ->
            if(p>0) click(Product.entries[p].label)
            compose.onNodeWithTag("stock-$p-opening").assertDoesNotExist()
            compose.onNodeWithTag("sale-$p-SG").assertDoesNotExist()
            if(p<2)text("stock-$p-sold","100")
            if(p==0) {
                text("stock-0-sample","5")
                text("stock-0-tg","25")
            }
            text("sale-$p-G",gross.toString())
            text("sale-$p-RTL",retail.toString())
            if(p==0)snapshot("STAGE32_PRODUCT")
        }
        assertEquals(100L,vm.state.value.day!!.products[1].manualSold)
        assertEquals(25L,vm.state.value.day!!.products[0].manualTg)
        assertNull(vm.state.value.day!!.products[0].delivery)
        click("Tester")
        text("tester-2-do","10")
        text("tester-2-sample","3")
        text("tester-2-tg","5")
        snapshot("STAGE32_TESTER")
        click("Keuangan")
        text("Saldo Sebelum","50000")
        text("Saldo Akhir","12345")
        compose.onNodeWithText("Atur Keuangan").assertDoesNotExist()
        snapshot("STAGE32_FINANCE")
        click("Periksa Laporan")
        compose.onNodeWithText("Total Penjualan: Rp 1.860.000").performScrollTo().assertExists()
        compose.onNodeWithText("Saldo Akhir: Rp 12.345").assertExists()
        compose.onNodeWithText("Simpan PDF").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Simpan Excel").performScrollTo().assertIsEnabled()
        compose.runOnIdle { vm.export() };saved()
        compose.runOnIdle { vm.export(xlsx=true) };saved()
        assertTrue((vm.state.value.pdf?.length() ?: 0L) > 0L)
        assertTrue((vm.state.value.xlsx?.length() ?: 0L) > 0L)
        click("Selesaikan Laporan");saved()
        assertEquals(ReportStatus.FINAL,vm.state.value.day!!.status)
        assertEquals(5L,vm.state.value.day!!.testers[2].manualTg)
    }
    @Test fun imeNextDoneAndRupiahPasteRetainCursorAndFinalValue() {
        newCall();click("Tambah Outlet");text("Nama Outlet","Toko Fokus");text("call-0-stock","10")
        compose.onNodeWithTag("call-0-stock").performImeAction()
        compose.onNodeWithTag("call-0-sold").assertIsFocused()
        text("call-2-sold","0");compose.onNodeWithTag("call-2-sold").performImeAction();compose.onNodeWithTag("call-2-sold").assertIsNotFocused()
        compose.runOnIdle { vm.newDay() };saved()
        click("Keuangan")
        val balance = compose.onNodeWithTag("Saldo Sebelum").performScrollTo()
        balance.performTextReplacement("Rp 14.500")
        saved();assertEquals(14500L,vm.state.value.day!!.finance.previous)
        balance.performTextInputSelection(androidx.compose.ui.text.TextRange(1))
        balance.performTextInput("9")
        saved();assertEquals(194500L,vm.state.value.day!!.finance.previous)
    }
    @Test @Config(sdk=[34],qualifiers="en-rUS-w360dp-h640dp") fun smallScreenLargeFontAndSyntheticKeyboardUseInsetsOnce() {
        compose.runOnIdle { compose.activity.setContent { val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) { LhsApp(vm) } } }
        newCall();click("Tambah Outlet");text("Nama Outlet","Toko Layar Kecil");text("call-0-stock","250")
        compose.runOnIdle {
            val insets=androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,24,0,24)).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,280)).setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),true).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(compose.activity.findViewById(android.R.id.content),insets)
        }
        text("call-2-sold","3");compose.onNodeWithTag("call-2-sold").assertIsDisplayed()
        compose.onNodeWithText("Simpan & Tambah Outlet").assertIsDisplayed()
        snapshot("STAGE3_FONT_130_IME")
    }
}
