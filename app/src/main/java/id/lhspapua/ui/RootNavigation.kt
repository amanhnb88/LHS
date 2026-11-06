package id.lhspapua.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarDefaults
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

@Composable internal fun RootNavigation(selected:String,compact:Boolean=false,select:(String)->Unit) {
    if(compact) {
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=3.dp) {
            Row(Modifier.fillMaxWidth().selectableGroup().windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
                RootTab.entries.forEach { tab ->
                    val active=selected==tab.route
                    Box(Modifier.weight(1f).height(48.dp).testTag("tab-${tab.route}")
                        .selectable(active,role=Role.Tab,onClick={ select(tab.route) }),contentAlignment=Alignment.Center) {
                        Icon(painterResource(tab.icon),contentDescription=tab.label,
                            tint=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else NavigationBar {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                modifier=Modifier.testTag("tab-${tab.route}"),
                selected=selected==tab.route,
                onClick={ select(tab.route) },
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
