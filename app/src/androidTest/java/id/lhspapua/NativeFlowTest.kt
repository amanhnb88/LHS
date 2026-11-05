package id.lhspapua

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Device acceptance smoke. Run with connectedDebugAndroidTest when a device/emulator is attached. */
class NativeFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nativeCallAndDailyNavigation() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("CALLSHEET").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CALLSHEET").performClick()
        compose.onNodeWithText("Tambah Outlet").performScrollTo().performClick()
        compose.onNodeWithText("Nama Outlet").performScrollTo().performTextInput("Toko Uji")
        compose.onNodeWithText("Alamat").performScrollTo().performTextInput("Jayapura")
        compose.onNodeWithText("Daftar Outlet").performScrollTo().performClick()
        compose.onNodeWithText("Kembali").performClick()
        compose.onNodeWithText("LAPORAN HARIAN").performScrollTo().performClick()
        compose.onNodeWithText("Tester",substring=false).performClick()
        compose.onNodeWithText("Keuangan",substring=false).performClick()
        compose.onNodeWithText("Saldo Akhir:",substring=true).performScrollTo().assertExists()
    }
}
