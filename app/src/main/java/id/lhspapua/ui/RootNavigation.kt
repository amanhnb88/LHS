package id.lhspapua.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import id.lhspapua.R

internal enum class RootTab(val route:String,val label:String,val title:String,val icon:Int) {
    HOME("home","Beranda","LHS Papua",R.drawable.nav_home),
    CALLSHEET("calls","Callsheet","Callsheet",R.drawable.nav_store),
    DAILY("days","LHS","Laporan Harian",R.drawable.nav_report),
    HISTORY("history","Riwayat","Riwayat",R.drawable.nav_history);

    companion object {
        val routes=entries.map { it.route }.toSet()
    }
}

@Composable internal fun RootNavigation(selected:String,select:(String)->Unit) {
    NavigationBar {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                modifier=Modifier.testTag("tab-${tab.route}"),
                selected=selected==tab.route,
                onClick={ if(selected!=tab.route)select(tab.route) },
                icon={ Icon(painterResource(tab.icon),contentDescription=null) },
                label={ Text(tab.label,maxLines=1) }
            )
        }
    }
}

@Composable internal fun KeyboardAwareBack(enabled:Boolean=true,onBack:()->Unit) {
    val imeOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    BackHandler(enabled) {
        if(imeOpen) {
            focus.clearFocus()
            keyboard?.hide()
        } else onBack()
    }
}
