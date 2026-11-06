package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*
import java.io.File

@Composable internal fun ReportReview(state:UiState,vm:LhsViewModel,save:(File)->Unit,share:(List<File>)->Unit,call:CallReport?=null,day:DailyReport?=null,onEdit:()->Unit={},onIssue:(ReportIssue)->Unit={},onLegacy:()->Unit={}) {
    val issues=if(call!=null)ReportIssues.call(call) else ReportIssues.daily(day!!)
    val complete=call?.completed ?: (day!!.status==ReportStatus.FINAL)
    val independent=day?.independent==true
    val legacy=day!=null&&day.formatVersion<2
    val identity=call?.identity ?: day!!.identity
    val context=LocalContext.current
    var finish by remember { mutableStateOf(false) };var reopen by remember { mutableStateOf(false) };var allErrors by rememberSaveable { mutableStateOf(false) }
    val distributable=issues.isEmpty()&&(complete||legacy||independent)
    ScrollPage(pageKey="review-${call?.id ?: day?.id}") {
        Text("Periksa Laporan",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("${identity.displayDate()} · ${identity.sales}")
        Text(if(complete)"Laporan selesai" else if(legacy)"Laporan lama" else "Draf",color=MaterialTheme.colorScheme.primary)
        SaveStatus(state,vm)
        if(call!=null) {
            Text("${call.activeOutlets().size} outlet",fontWeight=FontWeight.Bold)
            Product.entries.forEachIndexed { p,product->
                Text(product.label,fontWeight=FontWeight.Bold)
                Text("Stok ${runCatching { Calculation.calls(call,p,true)?.toString() ?: "—" }.getOrDefault("—")} · Jual ${runCatching { Calculation.calls(call,p,false)?.toString() ?: "—" }.getOrDefault("—")}")
            }
            TextButton(onClick=onEdit) { Text("Edit Callsheet") }
        } else if(day!=null) {
            Product.entries.forEachIndexed { p,product->
                Text(product.label,fontWeight=FontWeight.Bold)
                Text("Jual Stok ${runCatching { Calculation.number(Calculation.stockSold(day,p)) }.getOrDefault("—")} · TG ${runCatching { Calculation.number(Calculation.stockTg(day,p)) }.getOrDefault("—")} · Tester ${runCatching { Calculation.number(Calculation.testerTg(day,p)) }.getOrDefault("—")}",style=MaterialTheme.typography.bodyMedium)
                Text(Calculation.safeMoney(empty="—") { Calculation.productTotal(day.products[p]) });HorizontalDivider()
            }
            TotalLine("Total Penjualan",Calculation.safeMoney(empty="—") { Calculation.total(day) })
            TotalLine("Saldo Akhir",Calculation.safeMoney(empty="—") { Calculation.balance(day) })
            if(legacy) {
                Notice("Stok Awal belum tercatat pada laporan lama. Transaksi dan hasil lama tetap disimpan.")
                OutlinedButton(onClick=onLegacy,modifier=Modifier.fillMaxWidth()) { Text("Lengkapi Stok Awal Laporan Lama") }
            } else TextButton(onClick={ if(complete)reopen=true else onEdit() }) { Text(if(complete)"Buka Kembali untuk Edit" else "Edit Laporan") }
        }
        if(issues.isNotEmpty()) {
            Text("Perlu dilengkapi (${issues.size})",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            val visible=if(allErrors)issues else issues.distinctBy { Triple(it.section,it.product,it.outletId) }.take(5)
            visible.forEach { issue->
                Text(issue.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick={ if(day?.status==ReportStatus.FINAL)reopen=true else onIssue(issue) },modifier=Modifier.fillMaxWidth()) { Text(issue.action) }
            }
            if(visible.size<issues.size)TextButton(onClick={ allErrors=true }) { Text("Lihat semua yang perlu dilengkapi") }
        }
        if(!complete&&!legacy)Button(onClick={ if(independent) vm.finalizeDay {} else finish=true },enabled=issues.isEmpty()&&!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Selesaikan Laporan") }
        HorizontalDivider()
        Text("PDF",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        Text(if(call!=null)"A4 landscape · 30 baris per halaman" else "A4 portrait",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={ vm.export(preview=true) },enabled=issues.isEmpty()&&!state.busy,modifier=Modifier.fillMaxWidth()) { Text(if(complete||legacy||independent)"Pratinjau PDF" else "Pratinjau Draf PDF") }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ vm.export(onReady=save) },enabled=distributable&&!state.busy,modifier=Modifier.weight(1f)) { Text("Simpan PDF") }
            OutlinedButton(onClick={ vm.export { share(listOf(it)) } },enabled=distributable&&!state.busy,modifier=Modifier.weight(1f)) { Text("Bagikan PDF") }
        }
        Text("Excel",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        Text("File .xlsx dengan rumus aktif",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ vm.export(xlsx=true,onReady=save) },enabled=distributable&&!state.busy,modifier=Modifier.weight(1f)) { Text("Simpan Excel") }
            OutlinedButton(onClick={ vm.export(xlsx=true) { share(listOf(it)) } },enabled=distributable&&!state.busy,modifier=Modifier.weight(1f)) { Text("Bagikan Excel") }
        }
        TextButton(onClick={ vm.export(xlsx=true) { file->runCatching { ShareFiles.view(context,file) }.onFailure { vm.error("Pasang aplikasi pembuka Excel untuk melihat file .xlsx.") } } },enabled=distributable&&!state.busy) { Text("Buka Excel") }
        if(!complete&&!legacy&&!independent)Text("Selesaikan laporan untuk menyimpan atau membagikan hasil final.",style=MaterialTheme.typography.bodySmall)
        if(day!=null&&day.attachments.isNotEmpty())OutlinedButton(onClick={ vm.export { file->share(listOf(file)+day.attachments.map(vm.repository::attachment)) } },enabled=distributable&&!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Bagikan PDF + ${day.attachments.size} Bukti") }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if(finish)Confirmation("Selesaikan laporan?","Pastikan angka sudah sesuai. Laporan harian yang selesai dapat menjadi sumber stok berikutnya.","Selesaikan",{ finish=false;if(call!=null)vm.completeCall {} else vm.finalizeDay {} },{ finish=false })
    if(reopen)Confirmation("Buka kembali laporan?",if(independent) "Laporan akan dibuka sebagai draf untuk diedit." else "Perubahan stok dapat memengaruhi laporan berikutnya. Laporan final terkait akan ditandai untuk diperiksa.","Buka kembali",{ reopen=false;vm.reopen(onEdit) },{ reopen=false })
}
