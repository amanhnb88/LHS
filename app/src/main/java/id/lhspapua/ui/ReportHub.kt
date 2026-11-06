package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*

@Composable internal fun HomeScreen(state:UiState,vm:LhsViewModel) {
    ScrollPage {
        val recentCall=state.calls.find { it.id==state.lastId&&!it.completed }
        val recentDay=state.days.find { it.id==state.lastId&&it.status==ReportStatus.DRAFT }
        val call=recentCall ?: if(recentDay==null)state.calls.firstOrNull { !it.completed } else null
        val day=recentDay ?: if(call==null)state.days.firstOrNull { it.status==ReportStatus.DRAFT } else null
        if(call!=null || day!=null) {
            val identity=call?.identity ?: day!!.identity
            OutlinedCard(modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${if(call!=null)"Callsheet" else "Laporan Harian"} · Draf")
                    Text("${identity.displayDate()} · ${identity.sales.ifBlank { "Sales belum diisi" }}",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ if(call!=null)vm.open(call) else vm.open(day!!) }) { Text("Lanjutkan Laporan") }
                    DraftDelete(identity,if(call!=null)"Callsheet" else "Laporan Harian",state.busy) {
                        if(call!=null)vm.removeCall(call,draftOnly=true) else vm.removeDay(day!!,draftOnly=true)
                    }
                }
            }
        } else {
            Text("Belum ada draf",style=MaterialTheme.typography.titleMedium)
            Text("Pilih Callsheet atau LHS untuk memulai laporan.")
        }
    }
}
@Composable internal fun ReportHub(daily:Boolean,state:UiState,vm:LhsViewModel) {
    ScrollPage {
        Button(onClick={ if(daily)vm.newDay() else vm.newCall() },modifier=Modifier.fillMaxWidth()) { Text("Buat Laporan Baru") }
        val calls=state.calls.filterNot { it.completed }
        val days=state.days.filter { it.status==ReportStatus.DRAFT }.sortedWith(compareByDescending<DailyReport> { it.identity.date }.thenByDescending { it.stockSequence })
        Text("Lanjutkan Laporan",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        if(daily)days.forEach { r->key(r.id) {
            ReportRow(r.identity,if(r.formatVersion<2)"Laporan lama" else "Draf") { vm.open(r) }
            DraftDelete(r.identity,"Laporan Harian",state.busy) { vm.removeDay(r,draftOnly=true) }
        } }
        else calls.forEach { r->key(r.id) {
            ReportRow(r.identity,"${r.activeOutlets().size} outlet") { vm.open(r) }
            DraftDelete(r.identity,"Callsheet",state.busy) { vm.removeCall(r,draftOnly=true) }
        } }
        if(if(daily)days.isEmpty() else calls.isEmpty())Text("Belum ada draf. Pilih Buat Laporan Baru untuk mulai.")
    }
}
@Composable private fun DraftDelete(identity:Identity,type:String,busy:Boolean,delete:()->Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick={ confirm=true },enabled=!busy) { Text("Hapus Draft") }
    if(confirm) {
        val name=identity.sales.ifBlank { "Sales belum diisi" }
        Confirmation("Hapus Draft?",
            "Draft $type tanggal ${identity.displayDate()} atas nama $name akan dihapus. Data yang dihapus tidak dapat dipulihkan tanpa backup.",
            "Hapus Draft",{ confirm=false;delete() },{ confirm=false })
    }
}

@Composable private fun ReportRow(identity:Identity,status:String,action:()->Unit) {
    Surface(onClick=action,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("${identity.displayDate()} · ${identity.sales.ifBlank { "Sales belum diisi" }}",fontWeight=FontWeight.Bold)
            Text(status,style=MaterialTheme.typography.bodySmall)
            Text("Lanjutkan",color=MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable internal fun HistoryScreen(state:UiState,vm:LhsViewModel) {
    var daily by rememberSaveable { mutableStateOf(false) };var query by rememberSaveable { mutableStateOf("") }
    ScrollPage {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!daily,onClick={ daily=false },label={ Text("Callsheet") });FilterChip(selected=daily,onClick={ daily=true },label={ Text("Laporan Harian") }) }
        TextInput("Cari tanggal, sales, atau rute",query) { query=it }
        if(daily) {
            val rows=state.days.filter { "${it.identity.date} ${it.identity.sales} ${it.identity.route}".contains(query,true) }.sortedWith(compareByDescending<DailyReport> { it.identity.date }.thenByDescending { it.stockSequence })
            rows.forEach { r->
                ReportRow(r.identity,if(r.stockStale)"Perlu diperiksa" else if(r.status==ReportStatus.FINAL)"Final" else "Draf") { vm.open(r) }
                ConfirmButton("Hapus","Hapus laporan ${r.identity.displayDate()}?${if(r.usesLedger) " Stok laporan terkait akan diperiksa ulang." else ""}") { vm.removeDay(r) };HorizontalDivider()
            }
            if(rows.isEmpty())Text("Belum ada laporan yang sesuai.")
        } else {
            val rows=state.calls.filter { "${it.identity.date} ${it.identity.sales} ${it.identity.route}".contains(query,true) }
            rows.forEach { r->ReportRow(r.identity,"${r.activeOutlets().size} outlet · ${if(r.completed)"Selesai" else "Draf"}") { vm.open(r) };ConfirmButton("Hapus","Hapus Callsheet ${r.identity.displayDate()}?") { vm.removeCall(r) };HorizontalDivider() }
            if(rows.isEmpty())Text("Belum ada laporan yang sesuai.")
        }
    }
}
@Composable internal fun ReportIdentity(identity:Identity,expanded:Boolean,onExpanded:(Boolean)->Unit,onChange:(Identity)->Unit,compact:Boolean=false,onSave:()->Unit) {
    if(expanded) {
        IdentityEditor(identity,onChange=onChange)
        Button(onClick=onSave,modifier=Modifier.fillMaxWidth()) { Text("Gunakan Identitas") }
    } else if(compact) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(identity.sales.ifBlank { "Isi nama Sales" },fontWeight=FontWeight.Bold)
                Text(identity.displayDate(),style=MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick={ onExpanded(true) }) { Text("Ubah Identitas") }
        }
    } else {
        Text(identity.sales.ifBlank { "Isi nama Sales" },fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(identity.displayDate(),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={ onExpanded(true) }) { Text("Ubah tanggal / identitas") }
        }
    }
}
