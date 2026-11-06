package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*

internal data class ReportEntry(
    val id:String,val daily:Boolean,val identity:Identity,val draft:Boolean,
    val createdAt:String?,val updatedAt:String?,val completedAt:String?,val detail:String,
    val open:()->Unit,val delete:(Boolean)->Unit
) {
    val type:String get()=if(daily) "LHS" else "Callsheet"
    val key:String get()="$type:$id"
    val sales:String get()=identity.sales.ifBlank { "Sales belum diisi" }
}

@Composable internal fun reportEntries(state:UiState,vm:LhsViewModel):List<ReportEntry> =
    remember(state.calls,state.days,vm) {
        state.calls.map { report ->
            ReportEntry(report.id,false,report.identity,!report.completed,report.createdAt,report.updatedAt,
                report.completedAt,"${report.activeOutlets().size} outlet",
                { vm.open(report) },{ vm.removeCall(report,it) })
        }+state.days.map { report ->
            ReportEntry(report.id,true,report.identity,report.status==ReportStatus.DRAFT,report.createdAt,
                report.updatedAt,report.finalizedAt,
                when {
                    report.stockStale -> "Stok perlu diperiksa"
                    report.status==ReportStatus.DRAFT -> "Sementara · ${Calculation.safeMoney(empty="—") { Calculation.total(report) }}"
                    else -> ""
                },{ vm.open(report) },{ vm.removeDay(report,it) })
        }
    }

internal val recentDrafts=compareByDescending<ReportEntry> { ReportTime.parse(it.updatedAt) }
    .thenByDescending { it.identity.date }.thenBy { it.key }
private val recentFinals=compareByDescending<ReportEntry> { ReportTime.parse(it.completedAt) }
    .thenByDescending { it.identity.date }.thenBy { it.key }

@Composable internal fun ReportHub(daily:Boolean,vm:LhsViewModel) {
    ScrollPage {
        Button(onClick={ if(daily)vm.newDay() else vm.newCall() },modifier=Modifier.fillMaxWidth()) {
            Text("Buat Laporan Baru")
        }
        TextButton(onClick={ vm.selectTab("drafts") }) { Text("Lihat Draft") }
    }
}

@Composable internal fun ReportsScreen(state:UiState,vm:LhsViewModel,drafts:Boolean) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val entries=reportEntries(state,vm)
    val rows=remember(entries,filter,query,drafts) {
        entries.filter {
            it.draft==drafts && (filter==0 || it.daily==(filter==2)) &&
                "${it.identity.date} ${it.identity.displayDate()} ${it.sales} ${it.identity.route}".contains(query.trim(),true)
        }.sortedWith(if(drafts)recentDrafts else recentFinals)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding=PaddingValues(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        item {
            EqualTabs(listOf(if(drafts) "Semua Draft" else "Semua","Callsheet","LHS"),filter,"report-filter") { filter=it }
        }
        if(!drafts)item { TextInput("Cari tanggal, sales, atau rute",query) { query=it } }
        if(rows.isEmpty())item { Text(if(drafts) "Belum ada draft yang sesuai." else "Belum ada laporan final yang sesuai.") }
        items(rows,key={ it.key }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("report-${entry.id}")) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("${entry.type} — ${entry.sales}",fontWeight=FontWeight.Bold)
                    Text("${entry.identity.displayDate()} · ${if(drafts) "Draft" else "Final"}",style=MaterialTheme.typography.bodyMedium)
                    if(drafts) {
                        Text("Dibuat: ${ReportTime.display(entry.createdAt)}",style=MaterialTheme.typography.bodySmall)
                        Text("Terakhir diedit: ${ReportTime.display(entry.updatedAt)}",style=MaterialTheme.typography.bodySmall)
                    } else Text("Diselesaikan: ${ReportTime.display(entry.completedAt)}",style=MaterialTheme.typography.bodySmall)
                    if(entry.detail.isNotBlank())Text(entry.detail,style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onClick=entry.open,enabled=!state.busy) { Text(if(drafts) "Lanjutkan" else "Buka Laporan") }
                        ReportDelete(entry,drafts,state.busy)
                    }
                }
            }
        }
    }
}

@Composable private fun ReportDelete(entry:ReportEntry,draft:Boolean,busy:Boolean) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val action=if(draft) "Hapus Draft" else "Hapus"
    TextButton(onClick={ confirm=true },enabled=!busy) { Text(action) }
    if(confirm) {
        val details="${entry.type}, ${entry.identity.displayDate()}, ${entry.sales}."
        val message=if(draft) "$details Draft ini akan dihapus secara permanen dari aplikasi. Laporan yang telah dihapus tidak dapat dipulihkan tanpa backup."
            else "$details Hapus laporan ini? Stok laporan terkait akan diperiksa ulang jika memakai sumber stok."
        Confirmation(if(draft) "Hapus Draft?" else "Hapus Laporan?",message,action,
            { confirm=false;entry.delete(draft) },{ confirm=false })
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
