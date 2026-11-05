@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package id.lhspapua.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.lhspapua.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun <T> List<T>.replace(index:Int,value:T)=mapIndexed { i,v -> if(i==index)value else v }

@Composable fun LhsApp(vm:LhsViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val backDispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val scope=rememberCoroutineScope()

    var section by rememberSaveable(state.screen,state.call?.id,state.day?.id) { mutableStateOf("utama") }
    val pageScroll=remember(state.screen,section,state.call?.id,state.day?.id) { ScrollState(0) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    fun copyTo(uri:android.net.Uri?) {
        val file=pendingFile ?: return
        if(uri!=null) scope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Tujuan tidak bisa dibuka.") } }.onSuccess { vm.error("File berhasil disimpan.") }.onFailure { vm.error(it.message) } }
    }
    val savePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { copyTo(it) }
    val saveXlsx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { copyTo(it) }
    val saveZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { copyTo(it) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri=it }
    val attachment=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attach) }
    val title=when(state.screen) { "call"->"Callsheet"; "day"->"Laporan Harian"; "history"->"Riwayat Laporan"; "settings"->"Pengaturan"; else->"LHS Papua" }
    fun back() { if(section!="utama") section="utama" else vm.navigate("home") }
    BackHandler(state.screen!="home") { back() }
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF00695C),secondary=Color(0xFF47665F),surface=Color(0xFFFAFCFA))) {
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,topBar={ TopAppBar(title={ Text(title) },navigationIcon={ if(state.screen!="home") TextButton(onClick={ backDispatcher?.onBackPressed() ?: back() }) { Text("Kembali") } }) },bottomBar={
            if(state.screen=="call"||state.screen=="day") Surface(tonalElevation=3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal=16.dp,vertical=8.dp)) {
                    Text(state.saved,style=MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick=vm::saveNow,modifier=Modifier.weight(1f)) { Text("Simpan") }
                        Button(onClick={ section="ekspor" },modifier=Modifier.weight(1f)) { Text("Ringkasan & Ekspor") }
                    }
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(pageScroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(!state.ready) CircularProgressIndicator() else when(state.screen) {
                    "home" -> {
                        Text("Catat laporan sales",style=MaterialTheme.typography.headlineSmall)
                        Text("Dua formulir diisi dan disimpan secara terpisah. Bisa digunakan tanpa internet.")
                        MainCard("CALLSHEET","Kunjungan outlet, stok, dan penjualan",vm::newCall)
                        MainCard("LAPORAN HARIAN","Stock, penjualan, tester, dan keuangan",vm::newDay)
                        OutlinedButton(onClick={ vm.navigate("history") },modifier=Modifier.fillMaxWidth()) { Text("Riwayat Laporan") }
                        TextButton(onClick={ vm.navigate("settings") },modifier=Modifier.fillMaxWidth()) { Text("Pengaturan") }
                    }
                    "call" -> state.call?.let { r ->
                        if(section=="ekspor") {
                            SummaryCall(r)
                            ExportActions(state,vm,{ file -> pendingFile=file; if(file.extension=="pdf") savePdf.launch(file.name) else saveXlsx.launch(file.name) },{ file -> share(context,listOf(file)) })
                        } else CallEditor(r,state.settings,vm,pageScroll)
                    }
                    "day" -> state.day?.let { r ->
                        if(section=="ekspor") {
                            SummaryDaily(r)
                            ExportActions(state,vm,{ file -> pendingFile=file; if(file.extension=="pdf") savePdf.launch(file.name) else saveXlsx.launch(file.name) },{ file -> share(context,listOf(file)) })
                            if(r.attachments.isNotEmpty() && state.export!=null) OutlinedButton(onClick={ runCatching { share(context,listOf(state.export!!)+r.attachments.map { vm.repository.attachment(it) }) }.onFailure { vm.error(it.message) } },modifier=Modifier.fillMaxWidth()) { Text("Bagikan laporan + lampiran terpisah") }
                        } else {
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf("utama" to "Produk","tester" to "Tester","keuangan" to "Keuangan","identitas" to "Identitas","rumus" to "Rumus").forEach { (key,label) -> FilterChip(selected=section==key,onClick={ section=key },label={ Text(label) }) }
                            }
                            when(section) {
                                "utama" -> {
                                    Text("${r.identity.displayDate()} • ${r.identity.sales.ifBlank { "Sales belum diisi" }}")
                                    if(!r.rules.acknowledged) Notice("Makna TG dan aturan keuangan perlu diverifikasi. Buka Rumus sebelum ekspor final.")
                                    r.products.forEachIndexed { p,s -> ProductCard(p,s,r.rules,vm) }
                                    TotalLine("Total Penjualan",Calculation.safeMoney { Calculation.total(r) })
                                }
                                "tester" -> {
                                    Text("Tester",style=MaterialTheme.typography.titleLarge)
                                    r.testers.forEachIndexed { p,t -> Panel(Product.entries[p].label) {
                                        Numeric("DO Tester",t.delivery) { n -> vm.day { it.copy(testers=it.testers.replace(p,t.copy(delivery=n))) } }
                                        Numeric("Sampel Tester",t.sample) { n -> vm.day { it.copy(testers=it.testers.replace(p,t.copy(sample=n))) } }
                                        if(!r.rules.automaticTg) Numeric("TG Tester manual",t.manualTg) { n -> vm.day { it.copy(testers=it.testers.replace(p,t.copy(manualTg=n))) } }
                                        else Text("TG: ${Calculation.number(Calculation.tg(t,r.rules))}",color=if((Calculation.tg(t,r.rules)?:0)<0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    } }
                                }
                                "keuangan" -> {
                                    FinanceEditor(r,vm)
                                    Text("Lampiran bukti",style=MaterialTheme.typography.titleMedium)
                                    Text("Bukti transfer bank dan biaya operasional wajib dilampirkan. Foto disimpan di perangkat bersama laporan ini.")
                                    OutlinedButton(onClick={ attachment.launch(arrayOf("image/*","application/pdf")) }) { Text("Tambah Foto / PDF Bukti") }
                                    r.attachments.forEach { a ->
                                        Row(Modifier.fillMaxWidth()) {
                                            TextButton(onClick={ runCatching { share(context,listOf(vm.repository.attachment(a))) }.onFailure { vm.error(it.message) } },modifier=Modifier.weight(1f)) { Text(a.label) }
                                            ConfirmDelete("Hapus lampiran ini?") { vm.day { it.copy(attachments=it.attachments-a) } }
                                        }
                                    }
                                }
                                "identitas" -> IdentityEditor(r.identity) { identity -> vm.day { it.copy(identity=identity) } }
                                "rumus" -> RulesEditor(r.rules) { rules -> vm.day { it.copy(rules=rules) } }
                            }
                        }
                    }
                    "history" -> History(state,vm)
                    "settings" -> {
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("utama" to "Profil","harga" to "Harga Produk","rumus" to "Rumus","outlet" to "Outlet","backup" to "Backup").forEach { (key,label) -> FilterChip(selected=section==key,onClick={ section=key },label={ Text(label) }) } }
                        when(section) {
                            "utama" -> IdentityEditor(state.settings.profile,profile=true) { identity -> vm.settings { it.copy(profile=identity) } }
                            "harga" -> {
                                Notice("Harga ini digunakan saat membuat Laporan Harian baru. Laporan lama menyimpan harga sendiri.")
                                Product.entries.forEachIndexed { p,product -> Panel(product.label) {
                                    Tier.entries.forEachIndexed { t,tier -> Numeric("Harga ${tier.label}",state.settings.prices[p][t],money=true) { n -> vm.settings { it.copy(prices=it.prices.replace(p,it.prices[p].replace(t,n))) } } }
                                } }
                            }
                            "rumus" -> {
                                Notice("Pengaturan ini berlaku untuk laporan baru. Aturan laporan lama dapat diedit melalui menu Rumus dalam laporan tersebut.")
                                RulesEditor(state.settings.rules) { rules -> vm.settings { it.copy(rules=rules) } }
                            }
                            "outlet" -> SavedOutlets(state.settings,vm)
                            "backup" -> {
                                Text("Backup lokal",style=MaterialTheme.typography.titleLarge)
                                Text("Backup ZIP mencakup kedua formulir, profil, harga, aturan, daftar outlet, dan lampiran. Simpan salinannya di lokasi yang aman.")
                                Button(onClick={ vm.backup { file -> pendingFile=file; saveZip.launch(file.name) } },modifier=Modifier.fillMaxWidth()) { Text("Simpan Backup ZIP") }
                                OutlinedButton(onClick={ restore.launch(arrayOf("application/zip","application/octet-stream")) },modifier=Modifier.fillMaxWidth()) { Text("Pulihkan Backup") }
                                Text("Pemulihan menggabungkan laporan berdasarkan ID. Laporan dengan ID yang sama diganti oleh isi backup.")
                                ConfirmDelete("Hapus file lampiran yang sudah tidak dipakai oleh laporan?",label="Bersihkan lampiran tidak terpakai",onConfirm=vm::cleanup)
                            }
                        }
                        if(state.saved.isNotBlank()) Text(state.saved,style=MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        if(state.error!=null) AlertDialog(onDismissRequest={ vm.error(null) },title={ Text("Informasi") },text={ Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) { Text(state.error!!) } },confirmButton={ TextButton(onClick={ vm.error(null) }) { Text("Mengerti") } })
        if(restoreUri!=null) AlertDialog(onDismissRequest={ restoreUri=null },title={ Text("Pulihkan backup?") },text={ Text("Laporan dengan ID yang sama akan diganti oleh isi backup. Laporan lain tetap tersedia.") },confirmButton={ TextButton(onClick={ restoreUri?.let(vm::restore); restoreUri=null }) { Text("Pulihkan") } },dismissButton={ TextButton(onClick={ restoreUri=null }) { Text("Batal") } })
    }
}

@Composable private fun MainCard(title:String,description:String,action:()->Unit) {
    ElevatedCard(onClick=action,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold); Text(description); Text("Buat laporan baru",color=MaterialTheme.colorScheme.primary) } }
}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); content() } }
}
@Composable private fun Notice(text:String) { Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) { Text(text,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium) } }
@Composable fun TextInput(label:String,value:String,multiline:Boolean=false,onChange:(String)->Unit) { OutlinedTextField(value=value,onValueChange=onChange,label={ Text(label) },modifier=Modifier.fillMaxWidth(),singleLine=!multiline,minLines=if(multiline) 2 else 1) }
@Composable fun Numeric(label:String,value:Long?,money:Boolean=false,onChange:(Long?)->Unit) {
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(value=value?.toString() ?: "",onValueChange={ raw ->
        val digits=raw.trim(); val n=digits.toLongOrNull()
        if(digits.isEmpty()) { invalid=false; onChange(null) } else if(digits.all(Char::isDigit) && n!=null && n<=Calculation.MAX) { invalid=false; onChange(n) } else invalid=true
    },label={ Text(label) },modifier=Modifier.fillMaxWidth(),singleLine=true,isError=invalid,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Next),prefix=if(money) ({ Text("Rp ") }) else null,visualTransformation=if(money) RupiahTransformation else VisualTransformation.None,supportingText={ if(invalid) Text("Masukkan angka bulat tanpa titik, koma, atau minus. Maksimal 15 digit.") else if(money && value==null && label.startsWith("Harga")) Text("Belum Diatur") else if(value==null) Text("Kosong berbeda dengan 0") })
}
private object RupiahTransformation:VisualTransformation {
    override fun filter(text:AnnotatedString):TransformedText {
        val source=text.text; val result=source.reversed().chunked(3).joinToString(".").reversed()
        val mapping=object:OffsetMapping {
            override fun originalToTransformed(offset:Int):Int { if(offset==0)return 0; return (offset+(source.length-1)/3-(source.length-offset)/3).coerceIn(0,result.length) }
            override fun transformedToOriginal(offset:Int)=result.take(offset).count { it!='.' }.coerceIn(0,source.length)
        }
        return TransformedText(AnnotatedString(result),mapping)
    }
}
@Composable fun IdentityEditor(i:Identity,profile:Boolean=false,onChange:(Identity)->Unit) {
    Text(if(profile) "Profil Sales" else "Identitas laporan",style=MaterialTheme.typography.titleLarge)
    if(!profile) {
        var picker by remember { mutableStateOf(false) }
        OutlinedButton(onClick={ picker=true },modifier=Modifier.fillMaxWidth()) { Text("${i.day()}, ${i.displayDate()} — Ganti Tanggal") }
        if(picker) {
            val date=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(i.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest={ picker=false },confirmButton={ TextButton(onClick={ date.selectedDateMillis?.let { onChange(i.copy(date=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }; picker=false }) { Text("Pilih") } },dismissButton={ TextButton(onClick={ picker=false }) { Text("Batal") } }) { DatePicker(date) }
        }
    }
    TextInput("Sales",i.sales) { onChange(i.copy(sales=it)) }
    TextInput("Join",i.join) { onChange(i.copy(join=it)) }
    TextInput("Area",i.area) { onChange(i.copy(area=it)) }
    TextInput("Rute",i.route) { onChange(i.copy(route=it)) }
    TextInput("Kendaraan",i.vehicle) { onChange(i.copy(vehicle=it)) }
    TextInput("Nopol",i.plate) { onChange(i.copy(plate=it)) }
}
@Composable private fun CallEditor(r:CallReport,settings:Settings,vm:LhsViewModel,pageScroll:ScrollState) {
    var identity by rememberSaveable(r.id) { mutableStateOf(r.identity.sales.isBlank()) }
    var selected by rememberSaveable(r.id) { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    BackHandler(selected!=null) { selected=null }
    LaunchedEffect(selected) { pageScroll.scrollTo(0) }
    Text("${r.identity.displayDate()} • ${r.activeOutlets().size} outlet",style=MaterialTheme.typography.titleMedium)
    TextButton(onClick={ identity=!identity }) { Text(if(identity) "Tutup Identitas" else "Ubah Identitas Sales") }
    if(identity) IdentityEditor(r.identity) { i -> vm.call { it.copy(identity=i) } }
    val index=r.outlets.indexOfFirst { it.id==selected }
    val outlet=r.outlets.getOrNull(index)
    if(outlet!=null) {
        Panel("Outlet ${index+1}") {
            TextInput("Nama Outlet",outlet.name) { name -> vm.call { it.copy(outlets=it.outlets.replace(index,outlet.copy(name=name))) } }
            val suggestions=settings.outlets.filter { it.name.contains(outlet.name,true) && it.name!=outlet.name }.take(4)
            if(suggestions.isNotEmpty()) {
                Text("Pilih outlet tersimpan",style=MaterialTheme.typography.labelMedium)
                suggestions.forEach { saved -> TextButton(onClick={ vm.call { it.copy(outlets=it.outlets.replace(index,outlet.copy(name=saved.name,address=saved.address))) } }) { Text(saved.name) } }
            }
            TextInput("Alamat",outlet.address,true) { address -> vm.call { it.copy(outlets=it.outlets.replace(index,outlet.copy(address=address))) } }
            Product.entries.forEachIndexed { p,product ->
                Text(product.label,fontWeight=FontWeight.Bold)
                val q=outlet.quantities[p]
                Numeric("Stok",q.stock) { n -> vm.call { it.copy(outlets=it.outlets.replace(index,outlet.copy(quantities=outlet.quantities.replace(p,q.copy(stock=n))))) } }
                Numeric("Jual",q.sold) { n -> vm.call { it.copy(outlets=it.outlets.replace(index,outlet.copy(quantities=outlet.quantities.replace(p,q.copy(sold=n))))) } }
                if(q.stock!=null && q.sold!=null && q.sold>q.stock) Text("Jual melebihi Stok. Periksa kembali.",color=MaterialTheme.colorScheme.error)
            }
            Button(onClick={
                if(outlet.name.isBlank()) vm.error("Isi nama outlet terlebih dahulu.") else {
                    vm.saveOutlet(outlet)
                    if(index<r.outlets.lastIndex) selected=r.outlets[index+1].id else { val next=Outlet(); vm.call { it.copy(outlets=it.outlets+next) }; selected=next.id }
                }
            },modifier=Modifier.fillMaxWidth()) { Text("Simpan dan Lanjut Outlet Berikutnya") }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={ selected=r.outlets.getOrNull(index-1)?.id },enabled=index>0) { Text("Sebelumnya") }
                OutlinedButton(onClick={ vm.saveOutlet(outlet); selected=null }) { Text("Daftar Outlet") }
            }
            ConfirmDelete("Hapus outlet ${index+1} dari laporan ini?") { vm.call { it.copy(outlets=it.outlets.filterNot { o->o.id==outlet.id }) }; selected=null }
        }
    } else {
        Button(onClick={ val o=Outlet(); vm.call { it.copy(outlets=it.outlets+o) }; selected=o.id },modifier=Modifier.fillMaxWidth()) { Text("Tambah Outlet") }
        TextInput("Cari Outlet",search) { search=it }
        r.outlets.forEachIndexed { k,o -> if(o.name.contains(search,true)||o.address.contains(search,true)) OutlinedCard(onClick={ selected=o.id },modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("${k+1}. ${o.name.ifBlank { "Nama belum diisi" }}",fontWeight=FontWeight.Bold); Text(o.address) } } }
        if(r.outlets.isEmpty()) Text("Belum ada outlet. Tekan Tambah Outlet untuk mulai.")
        SummaryCall(r)
    }
}
@Composable private fun ProductCard(p:Int,s:Stock,rules:Rules,vm:LhsViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val missing=s.delivery==null || s.sales.any { it.quantity==null || ((it.quantity?:0)>0 && it.price==null) } || s.sample==null || s.bonus==null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(Product.entries[p].label,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(if(missing) "Belum lengkap" else "Terisi",color=if(missing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            Text("Jual: ${runCatching { Calculation.number(Calculation.sold(s)) }.getOrDefault("Angka terlalu besar")} bungkus")
            Text("TG: ${runCatching { Calculation.number(Calculation.tg(s,rules)) }.getOrDefault("Angka terlalu besar")}",color=if(runCatching { (Calculation.tg(s,rules)?:0)<0 }.getOrDefault(true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            TotalLine("Total",Calculation.safeMoney { Calculation.productTotal(s) })
            TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Tutup Input" else "Isi / Edit Produk") }
            if(expanded) {
                fun update(v:Stock) { vm.day { it.copy(products=it.products.replace(p,v)) } }
                Numeric("DO",s.delivery) { update(s.copy(delivery=it)) }
                Tier.entries.forEachIndexed { t,tier ->
                    val sale=s.sales[t]
                    Text("${tier.label} — ${listOf("Grosir","Sub Grosir","Retail")[t]}",fontWeight=FontWeight.Bold)
                    Numeric("Jumlah ${tier.label} (bungkus)",sale.quantity) { update(s.copy(sales=s.sales.replace(t,sale.copy(quantity=it)))) }
                    Numeric("Harga ${tier.label}",sale.price,money=true) { update(s.copy(sales=s.sales.replace(t,sale.copy(price=it)))) }
                    Text("Subtotal ${tier.label}: ${Calculation.safeMoney { Calculation.subtotal(sale) }}")
                }
                Numeric("Sample",s.sample) { update(s.copy(sample=it)) }
                Numeric("Bonus / Bns",s.bonus) { update(s.copy(bonus=it)) }
                if(!rules.automaticTg) Numeric("TG manual",s.manualTg) { update(s.copy(manualTg=it)) }
                Text(rules.tgText(),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable private fun FinanceEditor(r:DailyReport,vm:LhsViewModel) {
    val f=r.finance
    Text("Keuangan",style=MaterialTheme.typography.titleLarge)
    Notice(r.rules.financeText())
    TotalLine("Total Penjualan",Calculation.safeMoney { Calculation.total(r) })
    Numeric("Saldo Sebelum",f.previous,money=true) { n -> vm.day { it.copy(finance=f.copy(previous=n)) } }
    Numeric("Piutang",f.debt,money=true) { n -> vm.day { it.copy(finance=f.copy(debt=n)) } }
    Numeric("Pelunasan",f.settlement,money=true) { n -> vm.day { it.copy(finance=f.copy(settlement=n)) } }
    Numeric("Transfer",f.transfer,money=true) { n -> vm.day { it.copy(finance=f.copy(transfer=n)) } }
    Numeric("Pengeluaran",f.expense,money=true) { n -> vm.day { it.copy(finance=f.copy(expense=n)) } }
    TotalLine("Saldo Akhir",Calculation.safeMoney { Calculation.balance(r) })
    TextInput("Keterangan",f.notes,true) { notes -> vm.day { it.copy(finance=f.copy(notes=notes)) } }
    TextInput("Dibuat Oleh",f.madeBy) { name -> vm.day { it.copy(finance=f.copy(madeBy=name)) } }
}
@Composable private fun RulesEditor(r:Rules,onChange:(Rules)->Unit) {
    Notice("TG sementara diasumsikan sebagai stok akhir. Verifikasi maknanya dengan perusahaan. Piutang = penjualan belum dibayar; Pelunasan = penerimaan piutang; Transfer = uang disetor; Pengeluaran = biaya operasional.")
    Toggle("Hitung TG otomatis",r.automaticTg) { onChange(r.copy(automaticTg=it,acknowledged=false)) }
    if(r.automaticTg) {
        Toggle("Kurangi Sample dari DO",r.subtractSample) { onChange(r.copy(subtractSample=it,acknowledged=false)) }
        Toggle("Kurangi Bonus dari DO",r.subtractBonus) { onChange(r.copy(subtractBonus=it,acknowledged=false)) }
    }
    Text(r.tgText())
    listOf("Piutang" to r.debtSign,"Pelunasan" to r.settlementSign,"Transfer" to r.transferSign,"Pengeluaran" to r.expenseSign).forEachIndexed { k,(label,value) ->
        Text(label,fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(-1 to "Kurangi",1 to "Tambahkan").forEach { (sign,text) -> FilterChip(selected=value==sign,onClick={ onChange(when(k) { 0->r.copy(debtSign=sign,acknowledged=false); 1->r.copy(settlementSign=sign,acknowledged=false); 2->r.copy(transferSign=sign,acknowledged=false); else->r.copy(expenseSign=sign,acknowledged=false) }) },label={ Text(text) }) } }
    }
    Text(r.financeText())
    Toggle("Saya sudah memverifikasi aturan ini",r.acknowledged) { onChange(r.copy(acknowledged=it)) }
}
@Composable private fun Toggle(label:String,value:Boolean,change:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp,end=12.dp)); Switch(checked=value,onCheckedChange=change) } }
@Composable private fun TotalLine(label:String,value:String) { Text("$label: $value",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
@Composable private fun SummaryCall(r:CallReport) {
    Text("Ringkasan Callsheet",style=MaterialTheme.typography.titleLarge)
    Text("${r.identity.day()}, ${r.identity.displayDate()} • ${r.identity.sales}")
    Text("${r.activeOutlets().size} outlet • ${maxOf(1,(r.activeOutlets().size+29)/30)} halaman A4 landscape")
    Product.entries.forEachIndexed { p,product -> Text("${product.label}: Stok ${runCatching { Calculation.number(Calculation.calls(r,p,true)) }.getOrDefault("terlalu besar")} • Jual ${runCatching { Calculation.number(Calculation.calls(r,p,false)) }.getOrDefault("terlalu besar")}") }
    Calculation.errors(r).forEach { Text(it,color=MaterialTheme.colorScheme.error) }
}
@Composable private fun SummaryDaily(r:DailyReport) {
    Text("Ringkasan Laporan Harian",style=MaterialTheme.typography.titleLarge)
    Text("${r.identity.day()}, ${r.identity.displayDate()} • ${r.identity.sales}")
    Text("A4 portrait • ${r.attachments.size} lampiran terpisah")
    Product.entries.forEachIndexed { p,product -> Text("${product.label}: ${Calculation.safeMoney { Calculation.productTotal(r.products[p]) }}") }
    TotalLine("Total Penjualan",Calculation.safeMoney { Calculation.total(r) }); TotalLine("Saldo Akhir",Calculation.safeMoney { Calculation.balance(r) })
    Calculation.errors(r).forEach { Text(it,color=MaterialTheme.colorScheme.error) }
}
@Composable private fun ExportActions(state:UiState,vm:LhsViewModel,save:(File)->Unit,shareFile:(File)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={ vm.export(false) },enabled=!state.busy,modifier=Modifier.weight(1f)) { Text("Buat PDF") }
        OutlinedButton(onClick={ vm.export(true) },enabled=!state.busy,modifier=Modifier.weight(1f)) { Text("Buat Excel") }
    }
    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.export?.let { file ->
        Text("Siap: ${file.name}")
        Button(onClick={ save(file) },modifier=Modifier.fillMaxWidth()) { Text("Simpan ${if(file.extension=="pdf") "PDF" else "Excel"}") }
        OutlinedButton(onClick={ runCatching { shareFile(file) }.onFailure { vm.error(it.message) } },modifier=Modifier.fillMaxWidth()) { Text("Bagikan ${if(file.extension=="pdf") "PDF" else "Excel"}") }
    }
}
@Composable private fun History(state:UiState,vm:LhsViewModel) {
    var daily by rememberSaveable { mutableStateOf(false) }; var search by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!daily,onClick={ daily=false },label={ Text("Callsheet") }); FilterChip(selected=daily,onClick={ daily=true },label={ Text("Laporan Harian") }) }
    TextInput("Cari tanggal / sales / rute",search) { search=it }
    if(daily) state.days.filter { "${it.identity.date} ${it.identity.sales} ${it.identity.route}".contains(search,true) }.forEach { r -> Panel("${r.identity.displayDate()} • ${r.identity.sales.ifBlank { "Sales belum diisi" }}") { Text("${r.identity.route} • ID ${r.id.take(8)}"); Button(onClick={ vm.open(r) }) { Text("Buka / Edit / Ekspor") }; ConfirmDelete("Hapus Laporan Harian ini?") { vm.removeDay(r) } } }
    else state.calls.filter { "${it.identity.date} ${it.identity.sales} ${it.identity.route}".contains(search,true) }.forEach { r -> Panel("${r.identity.displayDate()} • ${r.identity.sales.ifBlank { "Sales belum diisi" }}") { Text("${r.activeOutlets().size} outlet • ${r.identity.route} • ID ${r.id.take(8)}"); Button(onClick={ vm.open(r) }) { Text("Buka / Edit / Ekspor") }; ConfirmDelete("Hapus Callsheet ini?") { vm.removeCall(r) } } }
    if(if(daily) state.days.isEmpty() else state.calls.isEmpty()) Text("Belum ada laporan tersimpan.")
}
@Composable private fun SavedOutlets(s:Settings,vm:LhsViewModel) {
    var query by rememberSaveable { mutableStateOf("") }; var name by rememberSaveable { mutableStateOf("") }; var address by rememberSaveable { mutableStateOf("") }
    TextInput("Nama outlet baru",name) { name=it }; TextInput("Alamat outlet baru",address,true) { address=it }
    Button(onClick={ if(name.isBlank())vm.error("Isi nama outlet.") else { vm.saveOutlet(Outlet(name=name,address=address)); name=""; address="" } }) { Text("Simpan Outlet") }
    TextInput("Cari outlet",query) { query=it }
    s.outlets.filter { it.name.contains(query,true)||it.address.contains(query,true) }.forEach { o -> Panel(o.name) {
        TextInput("Nama",o.name) { n -> vm.settings { it.copy(outlets=it.outlets.map { item -> if(item==o)item.copy(name=n) else item }) } }
        TextInput("Alamat",o.address,true) { a -> vm.settings { it.copy(outlets=it.outlets.map { item -> if(item==o)item.copy(address=a) else item }) } }
        ConfirmDelete("Hapus ${o.name} dari daftar outlet tersimpan?") { vm.settings { it.copy(outlets=it.outlets-o) } }
    } }
}
@Composable private fun ConfirmDelete(message:String,label:String="Hapus",onConfirm:()->Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick={ show=true }) { Text(label,color=MaterialTheme.colorScheme.error) }
    if(show) AlertDialog(onDismissRequest={ show=false },title={ Text("Konfirmasi") },text={ Text(message) },confirmButton={ TextButton(onClick={ show=false; onConfirm() }) { Text("Hapus") } },dismissButton={ TextButton(onClick={ show=false }) { Text("Batal") } })
}
private fun share(context:Context,files:List<File>) {
    require(files.all { it.isFile }) { "Salah satu file tidak ditemukan. Buat ulang ekspor atau lampiran." }
    val uris=ArrayList(files.map { FileProvider.getUriForFile(context,"${context.packageName}.files",it) })
    val mime=if(files.size>1) "*/*" else when(files.first().extension) { "pdf"->"application/pdf"; "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "png"->"image/png"; "jpg"->"image/jpeg"; "webp"->"image/webp"; else->"application/octet-stream" }
    val intent=Intent(if(files.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type=mime; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
        clipData=ClipData.newRawUri("Laporan LHS Papua",uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
    }
    context.startActivity(Intent.createChooser(intent,"Bagikan laporan"))
}
