package id.lhspapua.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*
import java.io.File
import java.util.Locale

@Composable internal fun CallsheetScreen(r:CallReport,state:UiState,vm:LhsViewModel,save:(List<File>)->Unit,share:(List<File>)->Unit) {
    var review by rememberSaveable(r.id) { mutableStateOf(r.completed) }
    var selected by rememberSaveable(r.id) { mutableStateOf<String?>(vm.rememberedOutlet(r.id)) }
    var editing by rememberSaveable(r.id) { mutableStateOf(false) }
    var query by rememberSaveable(r.id) { mutableStateOf("") }
    var identity by rememberSaveable(r.id) { mutableStateOf(r.identity.sales.isBlank()) }
    LaunchedEffect(selected) { vm.rememberOutlet(r.id,selected) }
    val listScroll=rememberScrollState()
    val outlet=r.outlets.find { it.id==selected }
    val directory=OutletSearch.matches(query,state.settings,state.calls.filterNot { it.id==r.id })
    fun add(saved:SavedOutlet?=null) {
        val current=vm.state.value.call ?: return
        val blank=current.outlets.firstOrNull { it.untouched() }
        val next=if(saved==null)Outlet(name=query.trim()) else OutletSearch.visit(saved)
        val chosen=if(blank!=null)next.copy(id=blank.id) else next
        vm.call { it.copy(outlets=if(blank==null)it.outlets+chosen else it.outlets.map { o->if(o.id==blank.id)chosen else o }) }
        selected=chosen.id;editing=false;query=""
    }
    fun list() { selected=null;query="";vm.flushPending() }
    fun saveOutlet() {
        val current=vm.state.value.call?.outlets?.find { it.id==selected } ?: return
        vm.saveOutlet(current)
        if(editing)list() else { add();vm.flushPending() }
    }
    KeyboardAwareBack { if(state.busy)return@KeyboardAwareBack;when { review->review=false;outlet!=null->list();else->vm.backToRoot() } }
    if(review) {
        ReportReview(state,vm,save,share,call=r,onEdit={ vm.call { it };review=false },onIssue={ tag->review=false;selected=tag.outletId;editing=true;vm.focus(tag.field) })
    } else FlowFrame(pageKey="call-${r.id}-${selected ?: "list"}",state=state,vm=vm,
        scroll=if(outlet==null)listScroll else null,primary=if(outlet==null)"Tambah Outlet" else if(editing)"Simpan Perubahan" else "Simpan & Tambah Outlet",
        onPrimary={ if(outlet==null)add() else saveOutlet() },secondary=if(outlet!=null)"Daftar Kunjungan" else "Periksa Laporan",onSecondary={ if(outlet!=null)list() else { review=true;vm.flushPending() } }) {
        if(outlet==null) {
            ReportIdentity(r.identity,identity,{ identity=it },{ i->vm.call { it.copy(identity=i) } }) {
                if(r.identity.sales.isBlank())vm.focus("Nama Sales") else { vm.rememberProfile(r.identity);identity=false }
            }
            TextInput("Cari atau tambah outlet",query,tag="outlet-search") { query=it }
            if(query.isNotBlank()) {
                val visits=r.activeOutlets().filter { OutletSearch.matchesText(it.name,query)||OutletSearch.matchesText(it.address,query) }
                if(visits.isNotEmpty())Text("Kunjungan hari ini",style=MaterialTheme.typography.titleSmall)
                visits.forEach { o->VisitRow(r.activeOutlets().indexOf(o)+1,o,{ selected=o.id;editing=true },{ vm.call { it.copy(outlets=it.outlets.filterNot { row->row.id==o.id }) } }) }
                val suggestions=directory.filterNot { saved->visits.any { it.name.equals(saved.name,true)&&it.address.equals(saved.address,true) } }
                if(suggestions.isNotEmpty())Text("Pilih outlet",style=MaterialTheme.typography.titleSmall)
                suggestions.take(12).forEach { o->OutletSuggestion(o) { add(o) } }
                OutlinedButton(onClick={ add() },modifier=Modifier.fillMaxWidth()) { Text("Tambah Outlet Baru “${query.trim()}”") }
            } else {
                Text("Kunjungan hari ini · ${r.activeOutlets().size} outlet",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                if(r.activeOutlets().isEmpty())Text("Cari outlet di atas atau pilih Tambah Outlet.")
                r.activeOutlets().forEachIndexed { index,o->VisitRow(index+1,o,{ selected=o.id;editing=true },{ vm.call { it.copy(outlets=it.outlets.filterNot { row->row.id==o.id }) } }) }
            }
        } else {
            Text(if(editing)"Edit Kunjungan" else "Kunjungan Baru",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            TextInput("Nama Outlet",outlet.name) { name->vm.call { it.copy(outlets=it.outlets.map { row->if(row.id==outlet.id)row.copy(name=name) else row }) } }
            val suggestions=OutletSearch.matches(outlet.name,state.settings,state.calls.filterNot { it.id==r.id }).filterNot { it.name.equals(outlet.name,true)&&it.address==outlet.address }
            if(outlet.name.isNotBlank())suggestions.take(3).forEach { saved->OutletSuggestion(saved) { vm.call { it.copy(outlets=it.outlets.map { row->if(row.id==outlet.id)row.copy(name=saved.name.uppercase(Locale.ROOT),address=saved.address.uppercase(Locale.ROOT)) else row }) } } }
            TextInput("Alamat",outlet.address,true) { address->vm.call { it.copy(outlets=it.outlets.map { row->if(row.id==outlet.id)row.copy(address=address) else row }) } }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("PRODUK",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                Text("JUAL (BUNGKUS)",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
            }
            Product.entries.forEachIndexed { p,product->
                val q=outlet.quantities[p]
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(product.label,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,softWrap=true)
                    Numeric("Jual ${product.label}",q.sold,modifier=Modifier.weight(1f),compact=true,tag="call-$p-sold",done=p==2) { n->vm.call { report->report.copy(outlets=report.outlets.map { o->if(o.id==outlet.id)o.copy(quantities=o.quantities.replace(p,o.quantities[p].copy(sold=n))) else o }) } }
                }
            }
            Text("Isi jumlah untuk produk yang terjual.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun OutletSuggestion(outlet:SavedOutlet,select:()->Unit) {
    Surface(onClick=select,modifier=Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small) {
        Column(Modifier.padding(12.dp)) { Text(outlet.name,fontWeight=FontWeight.SemiBold);Text(outlet.address,style=MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun VisitRow(number:Int,outlet:Outlet,edit:()->Unit,remove:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("$number. ${outlet.name.ifBlank { "Nama belum diisi" }}",fontWeight=FontWeight.SemiBold)
        if(outlet.address.isNotBlank())Text(outlet.address,style=MaterialTheme.typography.bodySmall,maxLines=2)
        Row { TextButton(onClick=edit) { Text("Edit Kunjungan") };ConfirmButton("Hapus","Hapus kunjungan ${outlet.name}?") { remove() } }
        HorizontalDivider()
    }
}
