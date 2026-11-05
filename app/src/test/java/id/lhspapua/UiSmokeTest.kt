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
import org.junit.Before
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
    @Before fun isolate() {
        compose.runOnIdle {
            compose.activity.viewModelStore.clear()
            compose.activity.application.getSharedPreferences("navigation",0).edit().clear().commit()
            vm=LhsViewModel(compose.activity.application)
            compose.activity.setContent { LhsApp(vm) }
        }
        compose.waitUntil(20000) { vm.state.value.ready }
        vm.navigate("home")
    }
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    private fun text(tag:String,value:String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun saved()=compose.waitUntil(20000) { vm.state.value.pending==0&&!vm.state.value.busy }
    private fun snapshot(name:String) {
        compose.runOnIdle { val view=compose.activity.window.decorView;val b=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b));val f=java.io.File("build/verification/$name.png");f.parentFile!!.mkdirs();f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun newCall() { click("CALLSHEET");click("Buat Laporan Baru");if(vm.state.value.call!!.identity.sales.isNotBlank())click("Ubah tanggal / identitas");text("Nama Sales","Sales Lapangan");click("Gunakan Identitas");saved() }
    @Test fun masterOutletIsFoundDirectlyAndDraftResumesWithoutDuplicates() {
        vm.settings { it.copy(outlets=listOf(SavedOutlet("Toko Aimas","Jalan Aimas"))) };saved()
        newCall();val id=vm.state.value.call!!.id
        val count=vm.state.value.calls.size
        text("outlet-search","aimas");click("Toko Aimas")
        compose.onNodeWithTag("Alamat").assertTextContains("Jalan Aimas")
        assertTrue(vm.state.value.call!!.outlets.last().quantities.all { it.stock==null&&it.sold==null })
        repeat(3) { p->text("call-$p-stock","10");text("call-$p-sold","3") }
        snapshot("STAGE3_CALLSHEET");click("Simpan & Tambah Outlet");click("Daftar Kunjungan")
        compose.activity.onBackPressedDispatcher.onBackPressed();click("CALLSHEET")
        saved();assertEquals(count,vm.state.value.calls.size)
        compose.onAllNodesWithText("Lanjutkan").onFirst().performClick();assertEquals(id,vm.state.value.call!!.id)
        compose.onNodeWithText("1. Toko Aimas").performScrollTo().assertExists()
        snapshot("STAGE3_VISITS")
    }
    @Test fun tenVisitsEditAndContextualValidationPreserveNumbers() {
        newCall();click("Tambah Outlet")
        repeat(10) { n->text("Nama Outlet","Outlet Uji ${n+1}");text("Alamat","Jayapura")
            repeat(3) { p->text("call-$p-stock","10");text("call-$p-sold","3") }
            click("Simpan & Tambah Outlet")
        }
        click("Daftar Kunjungan");text("outlet-search","Outlet Uji 10");click("Edit Kunjungan")
        text("call-0-sold","11");click("Simpan Perubahan")
        compose.onNodeWithTag("call-0-sold").assertTextContains("11")
        compose.onNodeWithText("Melebihi stok").assertExists()
        text("call-0-sold","4");click("Simpan Perubahan")
        text("outlet-search","");click("Periksa Laporan");compose.onNodeWithText("Stok 100 · Jual 31").performScrollTo().assertExists()
        click("Edit Callsheet");text("outlet-search","Outlet Uji 10");click("Hapus");click("Batal")
        compose.onNodeWithText("10. Outlet Uji 10").assertExists();click("Hapus")
        compose.onNode(hasText("Hapus") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        saved();assertEquals(9,vm.state.value.call!!.activeOutlets().size)
    }
    @Test fun dailyCombinesStockAndSalesAndShowsAllTesters() {
        vm.settings { it.copy(profile=Identity(sales="Sales Harian"),prices=List(3){List(3){10000L}},rules=Rules(acknowledged=true)) };saved()
        click("LAPORAN HARIAN");click("Buat Laporan Baru");saved()
        repeat(3) { p->click(Product.entries[p].label)
            text("stock-$p-opening","50");text("stock-$p-do","200")
            text("sale-$p-G","100");text("sale-$p-SG","40");text("sale-$p-RTL","10")
            click("Sampel & Bonus · 0 / 0");text("stock-$p-sample","10");text("stock-$p-bonus","5")
            if(p==0)snapshot("STAGE3_PRODUCT")
        }
        assertTrue(vm.state.value.day!!.products.all { Calculation.tg(it,vm.state.value.day!!.rules)==85L })
        click("Tester")
        repeat(3) { p->text("tester-$p-opening","5");text("tester-$p-do","10");text("tester-$p-sample","3") }
        snapshot("STAGE3_TESTER");click("Keuangan");text("Saldo Sebelum","50000")
        snapshot("STAGE3_FINANCE");click("Periksa Laporan")
        compose.onNodeWithText("Total Penjualan: Rp 4.500.000").performScrollTo().assertExists()
        compose.onNodeWithText("Saldo Akhir: Rp 4.550.000").assertExists()
        compose.onNodeWithText("Simpan PDF").performScrollTo().assertExists();compose.onNodeWithText("Simpan Excel").performScrollTo().assertExists()
        click("Selesaikan Laporan");compose.onNode(hasText("Selesaikan") and hasClickAction()).performClick();saved()
        assertEquals(ReportStatus.FINAL,vm.state.value.day!!.status)
    }
    @Test fun imeNextDoneAndRupiahPasteRetainCursorAndFinalValue() {
        newCall();click("Tambah Outlet");text("Nama Outlet","Toko Fokus");text("call-0-stock","10")
        compose.onNodeWithTag("call-0-stock").performImeAction()
        compose.onNodeWithTag("call-0-sold").assertIsFocused()
        text("call-2-sold","0");compose.onNodeWithTag("call-2-sold").performImeAction();compose.onNodeWithTag("call-2-sold").assertIsNotFocused()
        compose.runOnIdle { vm.navigate("settings") }
        click("Harga Produk");compose.onNodeWithTag("Harga Grosir (G)").performScrollTo().performTextReplacement("Rp 14.500")
        saved();assertEquals(14500L,vm.state.value.settings.prices[0][0])
        compose.onNodeWithTag("Harga Grosir (G)").performTextInputSelection(androidx.compose.ui.text.TextRange(1));compose.onNodeWithTag("Harga Grosir (G)").performTextInput("9")
        saved();assertEquals(194500L,vm.state.value.settings.prices[0][0])
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
