package id.lhspapua

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-rUS-w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun opensIndependentNativeFormsAndSettings() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("CALLSHEET").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CALLSHEET").performClick()
        compose.onNodeWithText("Tambah Outlet").performScrollTo().performClick()
        compose.onNodeWithText("Nama Outlet").performScrollTo().performTextInput("Toko Uji")
        compose.onNodeWithText("Alamat").performScrollTo().performTextInput("Jayapura")
        compose.onNodeWithText("Daftar Outlet").performScrollTo().performClick()
        compose.onNodeWithText("1. Toko Uji").performScrollTo().assertExists()
        compose.onNodeWithText("Kembali").performClick()
        compose.onNodeWithText("LAPORAN HARIAN").performScrollTo().performClick()
        compose.onNodeWithText("Tester",substring=false).performClick()
        compose.onAllNodesWithText("DO Tester",substring=false).assertCountEquals(3)
        compose.onNodeWithText("Keuangan",substring=false).performClick()
        compose.onNodeWithText("Saldo Akhir:",substring=true).performScrollTo().assertExists()
    }

}
