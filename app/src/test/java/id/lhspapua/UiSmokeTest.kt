package id.lhspapua

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.activity.compose.setContent
import id.lhspapua.ui.LhsViewModel
import id.lhspapua.ui.LhsApp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-rUS-w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun isolateNavigation() {
        compose.runOnIdle {
            compose.activity.viewModelStore.clear()
            compose.activity.application.getSharedPreferences("navigation",0).edit().clear().commit()
            val vm=LhsViewModel(compose.activity.application)
            compose.activity.setContent { LhsApp(vm) }
        }
    }
    private fun ready()=compose.waitUntil(20000) { compose.onAllNodesWithText("CALLSHEET").fetchSemanticsNodes().isNotEmpty() }
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());runCatching { node.performScrollTo() };node.performClick() }
    private fun input(text:String,value:String) { compose.onNodeWithText(text).performScrollTo().performTextReplacement(value) }
    private fun snapshot(name:String) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file=java.io.File("build/verification/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun tenOutletsCompactInputAutosaveEditAndSummary() {
        ready();click("CALLSHEET");input("Nama Sales","Ansar Uji");click("Lanjut");click("Tambah Outlet")
        repeat(10) { index->
            input("Nama Outlet","Toko ${index+1}");input("Alamat","Jalan Jayapura ${index+1}")
            repeat(3) { p->compose.onNodeWithTag("call-$p-stock").performScrollTo().performTextReplacement("10");compose.onNodeWithTag("call-$p-sold").performScrollTo().performTextReplacement("3") }
            if(index==0)snapshot("UX_CALLSHEET")
            compose.onAllNodesWithText("Periksa Laporan").assertCountEquals(0)
            click("Simpan & Outlet Berikutnya")
        }
        click("Lihat Daftar Outlet");compose.onNodeWithText("10. Toko 10").performScrollTo().assertExists()
        click("Cari outlet");input("Cari outlet","Toko 10");click("Edit Outlet")
        compose.onNodeWithTag("call-0-sold").performScrollTo().performTextReplacement("4")
        click("Lihat Daftar Outlet");input("Cari outlet","");click("Lihat Ringkasan")
        compose.onNodeWithText("10 outlet").performScrollTo().assertExists();compose.onAllNodesWithText("Total Stok: 100").assertCountEquals(3)
        compose.onNodeWithText("Total Jual: 31").performScrollTo().assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        click("Tambah Outlet");compose.onNodeWithText("Outlet 11").assertExists()
        click("Lihat Daftar Outlet");click("Tambah Outlet");compose.onNodeWithText("Outlet 11").assertExists();click("Lihat Daftar Outlet")
        input("Cari outlet","Toko 10");click("Hapus");click("Batal")
        compose.onNodeWithText("10. Toko 10").performScrollTo().assertExists();click("Hapus")
        compose.onNode(hasText("Hapus") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        compose.onNodeWithText("Outlet tidak ditemukan.").assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed();compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(20000) { compose.onAllNodesWithText("LAPORAN HARIAN").fetchSemanticsNodes().isNotEmpty() }
        click("LAPORAN HARIAN");compose.onNodeWithText("Langkah 1 dari 5").assertExists()
    }
    @Test fun fiveDailyStepsKeepDataOnBackAndSeparateTester() {
        ready();click("LAPORAN HARIAN");input("Nama Sales","Sales Lima Langkah");click("Lanjut")
        compose.waitUntil(20000) { compose.onAllNodesWithText("Langkah 2 dari 5").fetchSemanticsNodes().isNotEmpty() }
        click("Isi Stok Awal");click("Isi langsung")
        repeat(3) { p->click(listOf("MARTIL 12","MARTIL","HD LIGHT")[p]);compose.onNodeWithTag("stock-$p-opening").performScrollTo().performTextReplacement("50");compose.onNodeWithTag("stock-$p-do").performScrollTo().performTextReplacement("200") }
        snapshot("UX_STOK");click("Lanjut");
        compose.onNodeWithText("Langkah 3 dari 5").assertExists();snapshot("UX_PENJUALAN")
        // Zero transactions remain valid even without a configured unit price.
        click("Lanjut");compose.onNodeWithText("Langkah 4 dari 5").assertExists()
        repeat(3) { p->click(listOf("MARTIL 12","MARTIL","HD LIGHT")[p]);input("Stok Awal Tester","5");compose.onNodeWithTag("tester-$p-do").performScrollTo().performTextReplacement("10");compose.onNodeWithTag("tester-$p-sample").performScrollTo().performTextReplacement("3") }
        click("Lanjut");compose.onNodeWithText("Langkah 5 dari 5").assertExists();snapshot("UX_KEUANGAN");input("Saldo Sebelum","50000")
        click("Kembali ke langkah sebelumnya");compose.onNodeWithText("Sisa Tester (TG): 12").performScrollTo().assertExists()
        click("Lanjut");compose.onNodeWithText("Saldo Sebelum").performScrollTo().assertExists();click("Periksa Laporan")
        compose.onNodeWithText("Periksa Laporan").assertExists();compose.onNodeWithText("Selesaikan Laporan").assertExists()
    }
}
