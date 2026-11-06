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
import id.lhspapua.export.ExportFormat
import java.io.File

@Composable internal fun ReportReview(state:UiState,vm:LhsViewModel,save:(List<File>)->Unit,share:(List<File>)->Unit,call:CallReport?=null,day:DailyReport?=null,onEdit:()->Unit={},onIssue:(ReportIssue)->Unit={},onLegacy:()->Unit={}) {
    val issues=if(call!=null)ReportIssues.call(call) else ReportIssues.daily(day!!)
    val complete=call?.completed ?: (day!!.status==ReportStatus.FINAL)
    val independent=day?.independent==true
    val legacy=day!=null&&day.formatVersion<2
    val identity=call?.identity ?: day!!.identity
    val context=LocalContext.current
    var finish by remember { mutableStateOf(false) };var reopen by remember { mutableStateOf(false) };var allErrors by rememberSaveable { mutableStateOf(false) }
    var formatIndex by rememberSaveable { mutableIntStateOf(0) }
    val format=ExportFormat.entries[formatIndex]
    val distributable=issues.isEmpty()&&(complete||legacy||independent)
    ScrollPage(pageKey="review-${call?.id ?: day?.id}") {
        Text("Periksa Laporan",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text(if(call!=null)"Callsheet" else "Laporan Harian",fontWeight=FontWeight.SemiBold)
        Text("${identity.displayDate()} · ${identity.sales.ifBlank { "Sales belum diisi" }}")
        Text(if(complete)"Laporan selesai" else if(legacy)"Laporan lama" else "Draf",color=MaterialTheme.colorScheme.primary)
        SaveStatus(state,vm)
        if(call!=null) {
            Text("${call.activeOutlets().size} outlet",fontWeight=FontWeight.Bold)
            Product.entries.forEachIndexed { p,product->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(product.label,fontWeight=FontWeight.Bold)
                Text("Stok ${runCatching { Calculation.calls(call,p,true)?.let(Calculation::grouped) ?: "—" }.getOrDefault("—")} · Jual ${runCatching { Calculation.calls(call,p,false)?.let(Calculation::grouped) ?: "—" }.getOrDefault("—")}",style=MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick=onEdit) { Text("Edit Callsheet") }
        } else if(day!=null) {
            Product.entries.forEachIndexed { p,product->
                Text(product.label,fontWeight=FontWeight.Bold)
                val stock=runCatching { Calculation.stockSold(day,p) }.getOrNull()
                val tg=runCatching { Calculation.stockTg(day,p) }.getOrNull()
                val tester=runCatching { Calculation.testerTg(day,p) }.getOrNull()
                val details=listOfNotNull(stock?.let { "Jual Stok ${Calculation.grouped(it)}" },tg?.let { "TG $it" },tester?.let { "Tester $it" })
                if(details.isNotEmpty())Text(details.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
                Text(Calculation.safeMoney(empty="—") { Calculation.productTotal(day.products[p]) })
            }
            TotalLine("Total Penjualan",Calculation.safeMoney(empty="—") { Calculation.total(day) })
            runCatching { Calculation.balance(day) }.getOrNull()?.let { TotalLine("Saldo Akhir",Calculation.rupiah(it)) }
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
        Text("Ekspor",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ExportFormat.entries.forEach { option ->
                FilterChip(selected=format==option,onClick={ formatIndex=option.ordinal },label={ Text(option.label) })
            }
        }
        Text(when(format) {
            ExportFormat.EXCEL -> "File .xlsx dengan rumus aktif"
            ExportFormat.JPG -> "Satu gambar 300 DPI untuk setiap halaman"
            ExportFormat.PDF -> if(call!=null)"A4 landscape · 30 baris per halaman" else "A4 portrait"
        },style=MaterialTheme.typography.bodySmall)
        if(format==ExportFormat.EXCEL) {
            TextButton(onClick={ vm.export(format) { files ->
                runCatching { ShareFiles.view(context,files.single()) }.onFailure { vm.error("Pasang aplikasi pembuka Excel untuk melihat file .xlsx.") }
            } },enabled=distributable&&!state.busy) { Text("Buka Excel") }
        } else {
            OutlinedButton(onClick={ vm.previewReport(format==ExportFormat.JPG) },enabled=issues.isEmpty()&&!state.busy,modifier=Modifier.fillMaxWidth()) {
                Text(if(complete||legacy||independent)"Pratinjau ${format.label}" else "Pratinjau Draf ${format.label}")
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ vm.export(format,onReady=save) },enabled=distributable&&!state.busy,modifier=Modifier.weight(1f)) { Text("Simpan ${format.label}") }
            OutlinedButton(onClick={ vm.export(format,onReady=share) },enabled=distributable&&!state.busy,modifier=Modifier.weight(1f)) { Text("Bagikan ${format.label}") }
        }
        if(!complete&&!legacy&&!independent)Text("Selesaikan laporan untuk menyimpan atau membagikan hasil final.",style=MaterialTheme.typography.bodySmall)
        if(format==ExportFormat.PDF&&day!=null&&day.attachments.isNotEmpty())TextButton(
            onClick={ vm.export { files->share(files+day.attachments.map(vm.repository::attachment)) } },
            enabled=distributable&&!state.busy
        ) { Text("Bagikan PDF + ${day.attachments.size} Bukti") }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if(finish)Confirmation("Selesaikan laporan?",if(call!=null)"Pastikan kunjungan dan angka sudah sesuai." else "Pastikan angka sudah sesuai. Laporan harian yang selesai dapat menjadi sumber stok berikutnya.","Selesaikan",{ finish=false;if(call!=null)vm.completeCall {} else vm.finalizeDay {} },{ finish=false })
    if(reopen)Confirmation("Buka kembali laporan?",if(independent) "Laporan akan dibuka sebagai draf untuk diedit." else "Perubahan stok dapat memengaruhi laporan berikutnya. Laporan final terkait akan ditandai untuk diperiksa.","Buka kembali",{ reopen=false;vm.reopen(onEdit) },{ reopen=false })
}
