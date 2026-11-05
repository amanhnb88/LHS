@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package id.lhspapua.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

fun <T> List<T>.replace(index:Int,value:T)=mapIndexed { i,v->if(i==index)value else v }
private val ink=Color(0xFF193A32)

@Composable fun LhsApp(vm:LhsViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val dispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var settingsSection by rememberSaveable { mutableStateOf("") }
    var returnToDay by rememberSaveable { mutableStateOf(false) }
    fun write(uri:android.net.Uri?) {
        val file=pendingFile ?: return
        if(uri!=null)scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out->file.inputStream().use { it.copyTo(out) } } ?: error("Lokasi penyimpanan tidak dapat dibuka.") } }
                .onSuccess { vm.error("File berhasil disimpan.") }.onFailure { vm.error(it.message) }
        }
    }
    val savePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { write(it) }
    val saveXlsx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { write(it) }
    val saveZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { write(it) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri=it }
    val attachment=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attach) }
    fun save(file:File) { pendingFile=file;when(file.extension) { "pdf"->savePdf.launch(file.name);"xlsx"->saveXlsx.launch(file.name);else->saveZip.launch(file.name) } }
    fun share(files:List<File>) { runCatching { ShareFiles.share(context,files) }.onFailure { vm.error(it.message) } }
    val scheme=lightColorScheme(primary=Color(0xFF006B5C),onPrimary=Color.White,primaryContainer=Color(0xFFDDEFE9),secondary=Color(0xFF47665A),secondaryContainer=Color(0xFFE5EEE9),
        background=Color(0xFFF5F8F6),surface=Color.White,onSurface=ink,onBackground=ink,surfaceVariant=Color(0xFFEDF3EF),outline=Color(0xFF9DAEA5),
        surfaceContainerHighest=Color(0xFFE9F0EB),surfaceContainerHigh=Color(0xFFEEF4F0),surfaceContainer=Color(0xFFF2F6F3),surfaceContainerLow=Color(0xFFF6F9F7),surfaceContainerLowest=Color.White)
    MaterialTheme(colorScheme=scheme) {
        val title=when(state.screen) { "call"->"Callsheet";"day"->"Laporan Harian";"history"->"Riwayat Laporan";"settings"->"Pengaturan";else->"LHS Papua" }
        BackHandler(state.screen!="home") {
            if(state.screen=="settings" && settingsSection.isNotEmpty())settingsSection=""
            else if(state.screen=="settings" && returnToDay) { returnToDay=false;vm.navigate("day") }
            else vm.navigate("home")
        }
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,topBar={ TopAppBar(title={ Text(title,fontWeight=FontWeight.Bold) },navigationIcon={ if(state.screen!="home")TextButton(onClick={ focus.clearFocus();dispatcher?.onBackPressed() }) { Text("Kembali") } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if(!state.ready)CircularProgressIndicator(Modifier.align(Alignment.Center)) else when(state.screen) {
                    "home" -> ScrollPage {
                        Text("Laporan sales, lebih mudah.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                        Text("Pilih formulir yang ingin diisi. Semua data tersimpan di perangkat.")
                        MainCard("CALLSHEET","Catat kunjungan outlet, stok, dan jumlah barang terjual.",vm::newCall)
                        MainCard("LAPORAN HARIAN","Catat stok, penjualan, tester, dan keuangan harian.",vm::newDay)
                        OutlinedButton(onClick={ vm.navigate("history") },modifier=Modifier.fillMaxWidth()) { Text("Riwayat Laporan") }
                        TextButton(onClick={ settingsSection="";returnToDay=false;vm.navigate("settings") },modifier=Modifier.fillMaxWidth()) { Text("Pengaturan") }
                    }
                    "call" -> state.call?.let { CallFlow(it,state,vm,::save,::share) }
                    "day" -> state.day?.let { r->DailyFlow(r,state,vm,::save,::share,{ attachment.launch(arrayOf("image/*","application/pdf")) }) { settingsSection="lanjutan";returnToDay=true;vm.navigate("settings") } }
                    "history" -> History(state,vm)
                    "settings" -> ScrollPage(pageKey=settingsSection) {
                        SettingsScreen(settingsSection,{ settingsSection=it },state,vm,
                            backup={ vm.backup { file->save(file) } },restore={ restore.launch(arrayOf("application/zip","application/octet-stream")) })
                        if(returnToDay)TextButton(onClick={ returnToDay=false;settingsSection="";vm.navigate("day") }) { Text("Kembali ke laporan") }
                        SaveStatus(state,vm)
                    }
                }
            }
        }
        state.error?.let { text->AlertDialog(onDismissRequest={ vm.error(null) },title={ Text("Informasi") },text={ Text(text,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick={ vm.error(null) }) { Text("Mengerti") } }) }
        state.notice?.let { text->AlertDialog(onDismissRequest=vm::dismissNotice,title={ Text("Perubahan tersimpan") },text={ Text(text) },confirmButton={ TextButton(onClick=vm::dismissNotice) { Text("Mengerti") } }) }
        if(restoreUri!=null)Confirmation("Pulihkan backup?","Laporan dengan ID yang sama akan diganti dari backup. Buat backup saat ini jika ingin mempertahankan salinannya.","Pulihkan",{ restoreUri?.let(vm::restore);restoreUri=null },{ restoreUri=null })
        state.preview?.let { DocumentPreview(it) { vm.preview(null) } }
    }
}

@Composable private fun ScrollPage(pageKey:Any=Unit,content:@Composable ColumnScope.()->Unit) {
    key(pageKey) { Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) { content();Spacer(Modifier.height(16.dp)) } }
}
@Composable private fun MainCard(title:String,description:String,action:()->Unit) {
    ElevatedCard(onClick=action,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.elevatedCardColors(containerColor=Color.White)) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary);Text(description) }
    }
}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),colors=CardDefaults.outlinedCardColors(containerColor=Color.White)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);content() }
    }
}
@Composable private fun Notice(text:String,error:Boolean=false) { Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(12.dp)) { Text(text,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium) } }
@Composable private fun SaveStatus(state:UiState,vm:LhsViewModel) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(state.saved,style=MaterialTheme.typography.labelSmall,color=if(state.saveFailed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        if(state.saveFailed)TextButton(onClick=vm::retrySave) { Text("Coba simpan lagi") }
    }
}
@Composable private fun FlowFrame(pageKey:Any,state:UiState,vm:LhsViewModel,primary:String,onPrimary:()->Unit,secondary:String?=null,onSecondary:()->Unit={},enabled:Boolean=true,content:@Composable ColumnScope.()->Unit) {
    val focus=LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        key(pageKey) { Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { content();Spacer(Modifier.height(12.dp)) } }
        Surface(shadowElevation=6.dp,color=Color.White) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp,vertical=8.dp)) {
                SaveStatus(state,vm)
                Button(onClick={ focus.clearFocus();onPrimary() },enabled=enabled && !state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(primary) }
                if(secondary!=null)TextButton(onClick={ focus.clearFocus();onSecondary() },modifier=Modifier.fillMaxWidth()) { Text(secondary) }
            }
        }
    }
}
@Composable fun TextInput(label:String,value:String,multiline:Boolean=false,onChange:(String)->Unit) { OutlinedTextField(value=value,onValueChange=onChange,label={ Text(label) },modifier=Modifier.fillMaxWidth(),singleLine=!multiline,minLines=if(multiline)2 else 1) }
@Composable fun Numeric(label:String,value:Long?,money:Boolean=false,signed:Boolean=false,modifier:Modifier=Modifier,tag:String=label,readonly:Boolean=false,compact:Boolean=false,onChange:(Long?)->Unit={}) {
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(value=value?.toString() ?: "",onValueChange={ raw->
        val v=raw.trim();val n=v.toLongOrNull()
        if(v.isEmpty()) { invalid=false;onChange(null) }
        else if(n!=null && v.removePrefix(if(signed)"-" else "").all(Char::isDigit) && (if(signed)Calculation.validSigned(n) else Calculation.validInput(n))) { invalid=false;onChange(n) }
        else invalid=true
    },modifier=modifier.fillMaxWidth().testTag(tag).semantics { contentDescription=label },label=if(compact)null else ({ Text(label,fontSize=13.sp) }),
        placeholder={ Text(if(money && label.startsWith("Harga"))"Belum diatur" else "—") },readOnly=readonly,singleLine=true,isError=invalid,
        keyboardOptions=KeyboardOptions(keyboardType=if(signed)KeyboardType.NumberPassword else KeyboardType.Number,imeAction=ImeAction.Next),
        prefix=if(money)({ Text("Rp ") })else null,trailingIcon=if(signed)({ TextButton(onClick={ onChange(-(value ?: 0)) }) { Text("±") } })else null,visualTransformation=if(money)RupiahTransformation else VisualTransformation.None,
        supportingText=if(invalid)({ Text("Masukkan angka bulat, maksimal 15 digit.") })else null)
}
private object RupiahTransformation:VisualTransformation {
    override fun filter(text:AnnotatedString):TransformedText {
        val s=text.text;val negative=s.startsWith("-");val digits=s.removePrefix("-")
        val grouped=(if(negative)"-" else "")+digits.reversed().chunked(3).joinToString(".").reversed()
        return TransformedText(AnnotatedString(grouped),object:OffsetMapping {
            override fun originalToTransformed(offset:Int):Int {
                if(offset==0)return 0
                val numeric=(offset-if(negative)1 else 0).coerceAtLeast(0)
                return ((if(negative)1 else 0)+numeric+(digits.length-1).coerceAtLeast(0)/3-(digits.length-numeric)/3).coerceIn(0,grouped.length)
            }
            override fun transformedToOriginal(offset:Int)=grouped.take(offset).count { it!='.' }.coerceIn(0,s.length)
        })
    }
}
@Composable fun IdentityEditor(i:Identity,profile:Boolean=false,salesSuggestions:List<String> = emptyList(),onChange:(Identity)->Unit) {
    var details by rememberSaveable { mutableStateOf(profile) }
    if(!profile)DateButton(i) { onChange(i.copy(date=it)) }
    TextInput("Nama Sales",i.sales) { onChange(i.copy(sales=it)) }
    val suggestions=salesSuggestions.filter { it.contains(i.sales,true) && !it.equals(i.sales,true) }.take(3)
    if(suggestions.isNotEmpty())FlowRow { suggestions.forEach { n->TextButton(onClick={ onChange(i.copy(sales=n)) }) { Text(n) } } }
    if(!profile)TextButton(onClick={ details=!details }) { Text(if(details)"Tutup informasi lainnya" else "Informasi lainnya") }
    if(details) {
        TextInput("Join",i.join) { onChange(i.copy(join=it)) }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextInput("Area",i.area) { onChange(i.copy(area=it)) } }
            Box(Modifier.weight(1f)) { TextInput("Rute",i.route) { onChange(i.copy(route=it)) } }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextInput("Kendaraan",i.vehicle) { onChange(i.copy(vehicle=it)) } }
            Box(Modifier.weight(1f)) { TextInput("Nopol",i.plate) { onChange(i.copy(plate=it)) } }
        }
    }
}
@Composable private fun DateButton(i:Identity,change:(String)->Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick={ show=true },modifier=Modifier.fillMaxWidth()) { Text("${i.day()}, ${i.displayDate()}  ·  Ganti tanggal") }
    if(show) {
        val date=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(i.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={ show=false },confirmButton={ TextButton(onClick={ date.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) };show=false }) { Text("Pilih") } },dismissButton={ TextButton(onClick={ show=false }) { Text("Batal") } }) { DatePicker(date) }
    }
}
@Composable private fun Confirmation(title:String,message:String,accept:String="Lanjutkan",onAccept:()->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ Text(message) },confirmButton={ TextButton(onClick=onAccept) { Text(accept) } },dismissButton={ TextButton(onClick=onDismiss) { Text("Batal") } })
}
@Composable private fun ConfirmButton(label:String,message:String,action:()->Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick={ show=true }) { Text(label) }
    if(show)Confirmation(label,message,label,{ show=false;action() },{ show=false })
}
@Composable private fun TotalLine(label:String,value:String) { Text("$label: $value",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium) }
@Composable private fun ProductTabs(selected:Int,onSelect:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { Product.entries.forEachIndexed { i,p -> FilterChip(selected=selected==i,onClick={ onSelect(i) },label={ Text(p.label,fontSize=12.sp) },modifier=Modifier.weight(1f)) } }
}

@Composable private fun CallFlow(r:CallReport,state:UiState,vm:LhsViewModel,save:(File)->Unit,share:(List<File>)->Unit) {
    var phase by rememberSaveable(r.id) { mutableIntStateOf(vm.rememberedStep(r.id).coerceIn(0,2)) }
    var selected by rememberSaveable(r.id) { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var choose by remember { mutableStateOf(false) }
    LaunchedEffect(phase) { vm.rememberStep(r.id,phase) }
    val outlet=r.outlets.firstOrNull { it.id==selected }
    fun leaveOutlet() { selected=null }
    BackHandler { if(selected!=null)leaveOutlet() else if(phase>0)phase-- else vm.navigate("home") }
    fun addOutlet() {
        val current=vm.state.value.call ?: return
        val blank=current.outlets.firstOrNull { it.untouched() }
        val o=blank ?: Outlet()
        if(blank==null)vm.call { it.copy(outlets=it.outlets+o) }
        selected=o.id;phase=1
    }
    fun outletReady(o:Outlet):Boolean {
        if(o.name.isBlank()) { vm.error("Isi nama outlet.");return false }
        if(o.quantities.any { it.stock==null || it.sold==null }) { vm.error("Lengkapi Stok dan Jual ketiga produk. Isi 0 jika tidak ada.");return false }
        val errors=Calculation.errors(CallReport(outlets=listOf(o)))
        if(errors.isNotEmpty()) { vm.error(errors.joinToString("\n"));return false }
        return true
    }
    if(phase==2) {
        ExportReview(state,vm,save,share,call=r,onEdit={ phase=1 })
    } else {
        val primary=when { phase==0->"Lanjut";outlet!=null->"Simpan & Outlet Berikutnya";else->"Tambah Outlet" }
        val secondary=when { outlet!=null->"Lihat Daftar Outlet";phase==1 && r.activeOutlets().isNotEmpty()->"Lihat Ringkasan";else->null }
        FlowFrame(pageKey="${r.id}-$phase-$selected",state=state,vm=vm,primary=primary,onPrimary={
            when {
                phase==0 -> if(r.identity.sales.isBlank())vm.error("Isi nama Sales terlebih dahulu.") else phase=1
                outlet!=null -> if(outletReady(outlet)) { vm.saveOutlet(outlet);addOutlet() }
                else -> addOutlet()
            }
        },secondary=secondary,onSecondary={ if(outlet!=null)leaveOutlet() else phase=2 }) {
            if(phase==0) {
                Text("Informasi Callsheet",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("Periksa tanggal dan identitas sales sebelum mencatat kunjungan.")
                IdentityEditor(r.identity) { identity -> vm.call { it.copy(identity=identity) } }
            } else if(outlet!=null) {
                Text("Outlet ${r.outlets.indexOfFirst { it.id==outlet.id }+1}",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                TextInput("Nama Outlet",outlet.name) { name -> vm.call { report->report.copy(outlets=report.outlets.map { if(it.id==outlet.id)it.copy(name=name) else it }) } }
                if(state.settings.outlets.isNotEmpty())TextButton(onClick={ choose=true }) { Text("Pilih outlet tersimpan") }
                TextInput("Alamat",outlet.address,true) { address -> vm.call { report->report.copy(outlets=report.outlets.map { if(it.id==outlet.id)it.copy(address=address) else it }) } }
                Panel("") {
                    Row(Modifier.fillMaxWidth()) { Text("PRODUK",Modifier.weight(1.5f),style=MaterialTheme.typography.labelSmall);Text("STOK",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall);Text("JUAL",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall) }
                    Product.entries.forEachIndexed { p,product ->
                        val q=outlet.quantities[p]
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(product.label,Modifier.weight(1.5f),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                            Numeric("Stok ${product.label}",q.stock,modifier=Modifier.weight(1f),tag="call-$p-stock",compact=true) { n -> vm.call { report->report.copy(outlets=report.outlets.map { if(it.id==outlet.id)it.copy(quantities=it.quantities.replace(p,it.quantities[p].copy(stock=n))) else it }) } }
                            Numeric("Jual ${product.label}",q.sold,modifier=Modifier.weight(1f),tag="call-$p-sold",compact=true) { n -> vm.call { report->report.copy(outlets=report.outlets.map { if(it.id==outlet.id)it.copy(quantities=it.quantities.replace(p,it.quantities[p].copy(sold=n))) else it }) } }
                        }
                        if(q.stock!=null && q.sold!=null && q.sold>q.stock)Text("${product.label}: Jual melebihi Stok.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Jumlah dalam bungkus. Isi 0 jika tidak ada.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ vm.call { report->report.copy(outlets=report.outlets.map { if(it.id==outlet.id)it.copy(quantities=it.quantities.map { q->Quantity(q.stock ?: 0,q.sold ?: 0) }) else it }) } }) { Text("Isi kolom kosong dengan 0") }
            } else {
                Text("${r.identity.displayDate()} · ${r.identity.sales}",style=MaterialTheme.typography.titleMedium)
                Text("${r.activeOutlets().size} outlet dicatat. Tambahkan kunjungan berikutnya atau periksa laporan saat selesai.")
                TextButton(onClick={ phase=0 }) { Text("Ubah informasi laporan") }
                TextInput("Cari outlet",search) { search=it }
                val visible=r.outlets.withIndex().filter { (i,o)->!o.untouched() && (o.name.contains(search,true)||o.address.contains(search,true)) }
                visible.forEach { (index,o)->Panel("${index+1}. ${o.name.ifBlank { "Nama belum diisi" }}") {
                    Text(o.address)
                    Text(if(o.quantities.all { it.stock!=null && it.sold!=null } && Calculation.errors(CallReport(outlets=listOf(o))).isEmpty())"Terisi" else "Perlu dilengkapi",style=MaterialTheme.typography.labelMedium)
                    Row {
                        TextButton(onClick={ selected=o.id }) { Text("Edit Outlet") }
                        ConfirmButton("Hapus","Hapus outlet ini dari Callsheet?") { vm.call { it.copy(outlets=it.outlets.filterNot { row->row.id==o.id }) } }
                    }
                } }
                if(visible.isEmpty())Text(if(search.isBlank())"Belum ada outlet. Tekan Tambah Outlet untuk mulai." else "Outlet tidak ditemukan.")
            }
        }
    }
    if(choose && outlet!=null)OutletPicker(state.settings.outlets,{ saved->
        vm.call { report->report.copy(outlets=report.outlets.map { if(it.id==outlet.id)it.copy(name=saved.name,address=saved.address) else it }) };choose=false
    },{ choose=false })
}
@Composable private fun OutletPicker(outlets:List<SavedOutlet>,onSelect:(SavedOutlet)->Unit,onClose:()->Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onClose,title={ Text("Pilih Outlet") },text={ Column(Modifier.heightIn(max=400.dp)) {
        TextInput("Cari outlet tersimpan",search) { search=it }
        Column(Modifier.verticalScroll(rememberScrollState())) { outlets.filter { it.name.contains(search,true)||it.address.contains(search,true) }.forEach { o->TextButton(onClick={ onSelect(o) },modifier=Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(o.name,fontWeight=FontWeight.Bold);Text(o.address,style=MaterialTheme.typography.bodySmall) } } } }
    } },confirmButton={ TextButton(onClick=onClose) { Text("Tutup") } })
}

@Composable private fun DailyFlow(r:DailyReport,state:UiState,vm:LhsViewModel,save:(File)->Unit,share:(List<File>)->Unit,attach:()->Unit,advanced:()->Unit) {
    var step by rememberSaveable(r.id) { mutableIntStateOf(if(r.formatVersion<2 || r.status==ReportStatus.FINAL)5 else vm.rememberedStep(r.id).coerceIn(0,5)) }
    var product by rememberSaveable(r.id,step) { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    LaunchedEffect(step) { vm.rememberStep(r.id,step) }
    BackHandler { if(step>0 && r.status==ReportStatus.DRAFT && r.formatVersion>=2)step-- else vm.navigate("home") }
    if(step==5 || r.formatVersion<2 || r.status==ReportStatus.FINAL) {
        ExportReview(state,vm,save,share,day=r,onEdit={ step=0 },onAdvanced=advanced,onLegacy={ legacy=true })
    } else {
        fun next() {
            val active=vm.state.value.day ?: return
            val errors=when(step) {
                1 -> Calculation.stockErrors(active).filterNot { it.startsWith("Tester") || it.contains("Angka tester") }
                2 -> active.products.flatMapIndexed { p,s->s.sales.mapIndexedNotNull { t,v->if(v.quantity==null || (v.quantity>0 && v.price==null))"${Product.entries[p].label} ${Tier.entries[t].label}: jumlah atau harga belum lengkap." else null } }
                3 -> Calculation.stockErrors(active).filter { it.startsWith("Tester") || it.contains("tester") }
                else -> emptyList()
            }
            if(errors.isNotEmpty()) { vm.error(errors.joinToString("\n"));return }
            if(step==0)vm.prepareDailyIdentity { step=1 } else step++
        }
        FlowFrame(pageKey="${r.id}-$step",state=state,vm=vm,primary=if(step==4)"Periksa Laporan" else "Lanjut",onPrimary={ next() },secondary=if(step>0)"Kembali ke langkah sebelumnya" else null,onSecondary={ step-- }) {
            Text("Langkah ${step+1} dari 5",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress={ (step+1)/5f },modifier=Modifier.fillMaxWidth())
            Text(listOf("Informasi Harian","Stok & Barang Masuk","Penjualan","Tester","Keuangan")[step],style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            when(step) {
                0 -> {
                    IdentityEditor(r.identity,salesSuggestions=(state.settings.owners.map { it.name }+state.days.map { it.identity.sales }).distinct()) { vm.updateIdentity(it) }
                    if(r.identity.stockOwnerId.isNotBlank()) {
                        Text("Urutan stok tanggal ini: ${r.stockSequence}",style=MaterialTheme.typography.bodySmall)
                        var changeSequence by remember { mutableStateOf(false) }
                        TextButton(onClick={ changeSequence=true }) { Text("Atur urutan laporan pada tanggal yang sama") }
                        if(changeSequence) {
                            var sequence by remember { mutableStateOf<Long?>(r.stockSequence.toLong()) }
                            AlertDialog(onDismissRequest={ changeSequence=false },title={ Text("Urutan stok") },text={ Column { Text("Laporan kedua memakai stok akhir laporan pertama yang sudah final. Setiap urutan harus berbeda.");Numeric("Urutan",sequence) { sequence=it } } },confirmButton={ TextButton(onClick={ if(sequence!=null && sequence!! in 1..Int.MAX_VALUE.toLong()) { vm.day { it.copy(stockSequence=sequence!!.toInt()) };changeSequence=false } }) { Text("Gunakan urutan") } },dismissButton={ TextButton(onClick={ changeSequence=false }) { Text("Batal") } })
                        }
                    }
                }
                1 -> {
                    StockOrigin(r,state,onManual={ manual=true },onAuto=vm::automaticOpening)
                    ProductTabs(product) { product=it }
                    StockInputs(r,product,vm)
                    Text("DO hanya barang baru dari gudang. Angka 0 berarti tidak ada barang masuk, sampel, atau bonus.",style=MaterialTheme.typography.bodySmall)
                }
                2 -> {
                    Text("Isi jumlah bungkus. Harga memakai pengaturan yang tersimpan pada laporan ini.")
                    ProductTabs(product) { product=it }
                    SalesInputs(r,product,vm)
                    TotalLine("Total Penjualan",Calculation.safeMoney { Calculation.total(r) })
                }
                3 -> {
                    Text("Tester memiliki stok sendiri. Tidak mengurangi stok barang jual.")
                    ProductTabs(product) { product=it }
                    val t=r.testers[product]
                    Panel(Product.entries[product].label) {
                        Numeric("Stok Awal Tester",t.opening,readonly=r.openingMode==OpeningMode.AUTO) { n->vm.day { it.copy(testers=it.testers.replace(product,it.testers[product].copy(opening=n))) } }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Numeric("Barang Masuk (DO)",t.delivery,modifier=Modifier.weight(1f),tag="tester-$product-do") { n->vm.day { it.copy(testers=it.testers.replace(product,it.testers[product].copy(delivery=n))) } }
                            Numeric("Sampel Tester",t.sample,modifier=Modifier.weight(1f),tag="tester-$product-sample") { n->vm.day { it.copy(testers=it.testers.replace(product,it.testers[product].copy(sample=n))) } }
                        }
                        TotalLine("Sisa Tester (TG)",runCatching { Calculation.number(Calculation.tg(t,r.rules)) }.getOrDefault("Angka terlalu besar"))
                    }
                    if(r.openingMode==OpeningMode.AUTO && t.opening==null)TextButton(onClick={ manual=true }) { Text("Isi Stok Awal secara langsung") }
                }
                4 -> FinanceInputs(r,vm,attach)
            }
        }
    }
    if(manual)Confirmation("Isi Stok Awal langsung?","Nilai awal akan menjadi catatan penyesuaian laporan ini. Pastikan jumlahnya sesuai stok fisik milik ${r.identity.sales}.","Isi langsung",{ vm.manualOpening();manual=false },{ manual=false })
    if(legacy)Confirmation("Lengkapi laporan lama?","Seluruh identitas, transaksi, harga, dan lampiran dipertahankan. Masukkan Stok Awal yang benar sebelum memakai laporan ini untuk stok hari berikutnya.","Lengkapi",{ vm.adoptLegacy();step=0;legacy=false },{ legacy=false })
}
@Composable private fun StockOrigin(r:DailyReport,state:UiState,onManual:()->Unit,onAuto:()->Unit) {
    val source=state.days.firstOrNull { it.id==r.stockSourceId }
    if(r.openingMode==OpeningMode.MANUAL) {
        Notice("Stok Awal diisi langsung. Angka ini tersimpan pada laporan ini.")
        TextButton(onClick=onAuto) { Text("Ambil dari laporan sebelumnya") }
    } else if(source!=null) {
        Notice("Stok Awal dari ${source.identity.displayDate()}, urutan ${source.stockSequence}, milik ${source.identity.sales}.")
        ConfirmButton("Sesuaikan Stok Awal","Ganti pengambilan otomatis dengan input Stok Awal langsung?") { onManual() }
    } else {
        Notice(r.stockIssue.ifBlank { "Belum ada laporan final sebelumnya. Isi Stok Awal barang jual dan tester. Masukkan 0 jika memang tidak ada stok." })
        OutlinedButton(onClick=onManual,modifier=Modifier.fillMaxWidth()) { Text("Isi Stok Awal") }
    }
}
@Composable private fun StockInputs(r:DailyReport,p:Int,vm:LhsViewModel) {
    val s=r.products[p]
    Panel(Product.entries[p].label) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Numeric("Stok Awal",s.opening,modifier=Modifier.weight(1f),tag="stock-$p-opening",readonly=r.openingMode==OpeningMode.AUTO) { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(opening=n))) } }
            Numeric("Barang Masuk (DO)",s.delivery,modifier=Modifier.weight(1f),tag="stock-$p-do") { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(delivery=n))) } }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Numeric("Sampel",s.sample,modifier=Modifier.weight(1f),tag="stock-$p-sample") { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(sample=n))) } }
            Numeric("Bonus",s.bonus,modifier=Modifier.weight(1f),tag="stock-$p-bonus") { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(bonus=n))) } }
        }
        TotalLine("Stok Tersedia",runCatching { Calculation.number(Calculation.available(s)) }.getOrDefault("Angka terlalu besar"))
        Text("Stok Awal + DO",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun SalesInputs(r:DailyReport,p:Int,vm:LhsViewModel) {
    val s=r.products[p]
    var prices by remember { mutableStateOf(false) }
    Panel(Product.entries[p].label) {
        Tier.entries.forEachIndexed { t,tier->
            val sale=s.sales[t]
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1.5f)) { Text(listOf("Grosir (G)","Sub Grosir (SG)","Retail (RTL)")[t],fontWeight=FontWeight.Bold);Text(if(sale.price==null)"Harga belum diatur" else Calculation.rupiah(sale.price),style=MaterialTheme.typography.bodySmall) }
                Numeric("Jumlah ${tier.label}",sale.quantity,modifier=Modifier.weight(1f),tag="sale-$p-${tier.label}",compact=true) { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(sales=it.products[p].sales.replace(t,it.products[p].sales[t].copy(quantity=n))))) } }
            }
            Text("Subtotal ${tier.label}: ${Calculation.safeMoney { Calculation.subtotal(sale) }}",style=MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        TotalLine("Jumlah Terjual",runCatching { Calculation.number(Calculation.sold(s)) }.getOrDefault("Angka terlalu besar"))
        TotalLine("Sisa Stok (TG)",runCatching { Calculation.number(Calculation.tg(s,r.rules)) }.getOrDefault("Angka terlalu besar"))
        TotalLine("Total Produk",Calculation.safeMoney { Calculation.productTotal(s) })
        TextButton(onClick={ prices=true }) { Text("Ubah harga laporan ini") }
    }
    if(prices) {
        var values by remember { mutableStateOf(s.sales.map { it.price }) }
        AlertDialog(onDismissRequest={ prices=false },title={ Text("Harga ${Product.entries[p].label}") },text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Perubahan hanya berlaku pada laporan ini. Harga laporan lama dan Harga Produk tetap tersimpan.")
            Tier.entries.forEachIndexed { i,t->Numeric("Harga ${t.label}",values[i],money=true) { values=values.replace(i,it) } }
        } },confirmButton={ TextButton(onClick={ vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(sales=it.products[p].sales.mapIndexed { i,v->v.copy(price=values[i]) }))) };prices=false }) { Text("Gunakan harga") } },dismissButton={ TextButton(onClick={ prices=false }) { Text("Batal") } })
    }
}
@Composable private fun FinanceInputs(r:DailyReport,vm:LhsViewModel,attach:()->Unit) {
    val f=r.finance
    TotalLine("Total Penjualan",Calculation.safeMoney { Calculation.total(r) })
    Numeric("Saldo Sebelum",f.previous,money=true,signed=true) { n->vm.day { it.copy(finance=it.finance.copy(previous=n)) } }
    Numeric("Piutang",f.debt,money=true) { n->vm.day { it.copy(finance=it.finance.copy(debt=n)) } }
    Numeric("Pelunasan",f.settlement,money=true) { n->vm.day { it.copy(finance=it.finance.copy(settlement=n)) } }
    Numeric("Transfer",f.transfer,money=true) { n->vm.day { it.copy(finance=it.finance.copy(transfer=n)) } }
    Numeric("Pengeluaran",f.expense,money=true) { n->vm.day { it.copy(finance=it.finance.copy(expense=n)) } }
    if(!r.rules.automaticFinance)Numeric("Saldo Akhir Manual",f.manualBalance,money=true,signed=true) { n->vm.day { it.copy(finance=it.finance.copy(manualBalance=n)) } }
    else {
        TotalLine("Saldo Akhir",Calculation.safeMoney { Calculation.balance(r) })
        Text(if(r.rules.acknowledged)"Dihitung otomatis sesuai aturan yang telah disetujui." else "Perhitungan sementara. Aturan perlu disetujui di Pengaturan Lanjutan sebelum laporan diselesaikan.",style=MaterialTheme.typography.bodySmall)
    }
    TextInput("Keterangan",f.notes,true) { notes->vm.day { it.copy(finance=it.finance.copy(notes=notes)) } }
    TextInput("Dibuat Oleh",f.madeBy) { name->vm.day { it.copy(finance=it.finance.copy(madeBy=name)) } }
    OutlinedButton(onClick=attach,modifier=Modifier.fillMaxWidth()) { Text("Tambah Bukti Transfer / Biaya") }
    Text("Bukti disimpan sebagai lampiran terpisah dari formulir resmi.",style=MaterialTheme.typography.bodySmall)
    r.attachments.forEach { a->Row(verticalAlignment=Alignment.CenterVertically) {
        Text(a.label,Modifier.weight(1f))
        ConfirmButton("Hapus","Hapus lampiran ini dari laporan?") { vm.day { it.copy(attachments=it.attachments-a) } }
    } }
}

@Composable private fun ExportReview(state:UiState,vm:LhsViewModel,save:(File)->Unit,share:(List<File>)->Unit,call:CallReport?=null,day:DailyReport?=null,onEdit:()->Unit={},onAdvanced:()->Unit={},onLegacy:()->Unit={}) {
    var excel by rememberSaveable(call?.id,day?.id) { mutableStateOf(false) }
    var finalize by remember { mutableStateOf(false) }
    var reopen by remember { mutableStateOf(false) }
    val errors=if(call!=null)Calculation.errors(call) else Calculation.errors(day!!)
    val draft=day?.formatVersion==2 && day.status!=ReportStatus.FINAL
    val file=if(excel)state.xlsx else state.pdf
    val name=if(excel)"Excel" else "PDF"
    val primary=when { draft->"Selesaikan Laporan";file!=null->"Bagikan $name";else->"Buat $name" }
    FlowFrame(pageKey="review-${call?.id ?: day?.id}",state=state,vm=vm,primary=primary,onPrimary={
        if(draft) { if(errors.isNotEmpty())vm.error(errors.joinToString("\n")) else finalize=true }
        else if(file!=null)share(listOf(file)) else vm.export(excel)
    },secondary=if(!draft && file!=null)"Simpan $name" else null,onSecondary={ if(file!=null)save(file) }) {
        Text("Periksa Laporan",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        val identity=call?.identity ?: day!!.identity
        Text("${identity.day()}, ${identity.displayDate()} · ${identity.sales}")
        if(call!=null) {
            Text("${call.activeOutlets().size} outlet")
            Product.entries.forEachIndexed { p,v->Panel(v.label) {
                Text("Total Stok: ${runCatching { Calculation.number(Calculation.calls(call,p,true)) }.getOrDefault("Angka terlalu besar")}")
                Text("Total Jual: ${runCatching { Calculation.number(Calculation.calls(call,p,false)) }.getOrDefault("Angka terlalu besar")}")
            } }
            TextButton(onClick=onEdit) { Text("Edit Callsheet") }
        } else if(day!=null) {
            Text("${if(day.formatVersion<2) "Laporan versi lama" else if(day.stockStale) "Perlu diperiksa ulang" else if(draft) "Draf" else "Final"} · Urutan ${day.stockSequence}")
            if(day.formatVersion<2) {
                Notice("Data versi lama tetap tersimpan. Stok Awal dahulu belum dicatat, sehingga laporan ini belum menjadi sumber stok antarhari.")
                OutlinedButton(onClick=onLegacy,modifier=Modifier.fillMaxWidth()) { Text("Lengkapi Stok Awal Laporan Lama") }
            }
            if(day.stockStale)Notice(day.stockIssue,error=true)
            Product.entries.forEachIndexed { p,v->Panel(v.label) {
                Text("Terjual: ${runCatching { Calculation.number(Calculation.sold(day.products[p])) }.getOrDefault("Angka terlalu besar")} bungkus")
                Text("Sisa Stok: ${runCatching { Calculation.number(Calculation.tg(day.products[p],day.rules)) }.getOrDefault("Angka terlalu besar")} bungkus")
                Text("Sisa Tester: ${runCatching { Calculation.number(Calculation.tg(day.testers[p],day.rules)) }.getOrDefault("Angka terlalu besar")}")
                TotalLine("Penjualan",Calculation.safeMoney { Calculation.productTotal(day.products[p]) })
            } }
            TotalLine("Total Penjualan",Calculation.safeMoney { Calculation.total(day) })
            TotalLine("Saldo Akhir",Calculation.safeMoney { Calculation.balance(day) })
            if(day.rules.automaticFinance && !day.rules.acknowledged) {
                Notice("Aturan keuangan perlu disetujui sekali. Harga dan transaksi laporan ini tetap tersimpan.")
                OutlinedButton(onClick=onAdvanced,modifier=Modifier.fillMaxWidth()) { Text("Buka Pengaturan Lanjutan") }
            }
            if(day.formatVersion>=2)TextButton(onClick={ if(day.status==ReportStatus.FINAL)reopen=true else onEdit() }) { Text(if(draft)"Kembali Mengedit" else "Buka Kembali untuk Edit") }
        }
        if(errors.isNotEmpty())Panel("Perlu dilengkapi") { errors.distinct().forEach { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium) } }
        if(!draft) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!excel,onClick={ excel=false },label={ Text("PDF") });FilterChip(selected=excel,onClick={ excel=true },label={ Text("Excel") }) }
            Text(if(call!=null)"A4 landscape · 30 baris per halaman" else "A4 portrait",style=MaterialTheme.typography.bodySmall)
        }
        if(errors.isEmpty())OutlinedButton(onClick={ if(state.pdf!=null)vm.preview(state.pdf) else vm.export(preview=true) },enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Pratinjau PDF · Halaman Utuh") }
        if(file!=null && !draft)Text("File siap disimpan atau dibagikan.",color=MaterialTheme.colorScheme.primary)
        if(day!=null && day.attachments.isNotEmpty() && file!=null && !draft)OutlinedButton(onClick={ runCatching { share(listOf(file)+day.attachments.map { vm.repository.attachment(it) }) }.onFailure { vm.error(it.message) } },modifier=Modifier.fillMaxWidth()) { Text("Bagikan Laporan + ${day.attachments.size} Lampiran") }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if(finalize)Confirmation("Selesaikan laporan?","Periksa angka stok dan keuangan. Laporan final yang valid dapat menjadi sumber Stok Awal berikutnya. Untuk mengeditnya nanti, pilih Buka Kembali.","Selesaikan",{ finalize=false;vm.finalizeDay {} },{ finalize=false })
    if(reopen)Confirmation("Buka kembali laporan?","Perubahan stok dapat memengaruhi laporan sesudahnya. Laporan final terkait akan ditandai untuk diperiksa; nilainya tidak diganti diam-diam.","Buka kembali",{ reopen=false;vm.reopen(onEdit) },{ reopen=false })
}

@Composable private fun History(state:UiState,vm:LhsViewModel) {
    var daily by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    ScrollPage {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!daily,onClick={ daily=false },label={ Text("Callsheet") });FilterChip(selected=daily,onClick={ daily=true },label={ Text("Laporan Harian") }) }
        TextInput("Cari tanggal, sales, atau rute",query) { query=it }
        if(daily) {
            val rows=state.days.filter { "${it.identity.date} ${it.identity.sales} ${it.identity.route}".contains(query,true) }.sortedWith(compareByDescending<DailyReport> { it.identity.date }.thenByDescending { it.stockSequence })
            rows.forEach { r->Panel("${r.identity.displayDate()} · ${r.identity.sales.ifBlank { "Sales belum diisi" }}") {
                Text("${r.identity.route} · Urutan ${r.stockSequence}")
                Text(if(r.formatVersion<2)"Versi lama · data dipertahankan" else if(r.stockStale)"Perlu diperiksa" else if(r.status==ReportStatus.FINAL)"Final" else "Draf")
                Row { TextButton(onClick={ vm.open(r) }) { Text("Buka Laporan") };ConfirmButton("Hapus","Hapus laporan ini? Stok laporan terkait akan diperiksa ulang. Laporan final terkait akan ditandai.") { vm.removeDay(r) } }
            } }
            if(rows.isEmpty())Text("Belum ada laporan yang sesuai.")
        } else {
            val rows=state.calls.filter { "${it.identity.date} ${it.identity.sales} ${it.identity.route}".contains(query,true) }
            rows.forEach { r->Panel("${r.identity.displayDate()} · ${r.identity.sales.ifBlank { "Sales belum diisi" }}") {
                Text("${r.activeOutlets().size} outlet · ${r.identity.route}")
                Row { TextButton(onClick={ vm.open(r) }) { Text("Buka Callsheet") };ConfirmButton("Hapus","Hapus Callsheet ini?") { vm.removeCall(r) } }
            } }
            if(rows.isEmpty())Text("Belum ada Callsheet yang sesuai.")
        }
    }
}
@Composable private fun SettingsScreen(section:String,select:(String)->Unit,state:UiState,vm:LhsViewModel,backup:()->Unit,restore:()->Unit) {
    when(section) {
        "" -> {
            listOf(Triple("profil","Profil Sales","Identitas yang sering dipakai."),Triple("harga","Harga Produk","Harga Grosir, Sub Grosir, dan Retail."),Triple("outlet","Data Outlet","Nama dan alamat outlet tersimpan."),Triple("backup","Backup dan Pemulihan","Simpan salinan laporan dan lampiran."),Triple("lanjutan","Pengaturan Lanjutan","Aturan keuangan dan penjelasan stok.")).forEach { (key,title,description)->MainCard(title,description) { select(key) } }
        }
        "profil" -> {
            Text("Profil Sales",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            IdentityEditor(state.settings.profile,profile=true) { i->vm.settings { it.copy(profile=i.copy(stockOwnerId=if(i.sales.trim().equals(it.profile.sales.trim(),true))i.stockOwnerId else "")) } }
            Text("Profil digunakan sebagai isian awal. Laporan yang sudah tersimpan tetap memakai identitasnya sendiri.",style=MaterialTheme.typography.bodySmall)
        }
        "harga" -> {
            var product by rememberSaveable { mutableIntStateOf(0) }
            Text("Harga Produk",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Notice("Berlaku untuk laporan baru. Harga pada laporan lama tidak berubah.")
            ProductTabs(product) { product=it }
            Panel(Product.entries[product].label) {
                Tier.entries.forEachIndexed { i,_ ->
                    val title=listOf("Grosir (G)","Sub Grosir (SG)","Retail (RTL)")[i]
                    Numeric("Harga $title",state.settings.prices[product][i],money=true) { n ->
                        vm.settings { settings -> settings.copy(prices=settings.prices.replace(product,settings.prices[product].replace(i,n))) }
                    }
                }
            }
        }
        "outlet" -> SavedOutlets(state.settings,vm)
        "backup" -> {
            Text("Backup dan Pemulihan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Backup mencakup laporan, harga, profil, daftar outlet, stok antarhari, dan seluruh lampiran. Simpan secara berkala sebelum mengganti HP atau menghapus data aplikasi.")
            Button(onClick=backup,modifier=Modifier.fillMaxWidth()) { Text("Simpan Backup ZIP") }
            OutlinedButton(onClick=restore,modifier=Modifier.fillMaxWidth()) { Text("Pulihkan Backup ZIP") }
            ConfirmButton("Bersihkan lampiran tidak terpakai","Hapus hanya file lampiran yang sudah tidak dipakai oleh laporan?") { vm.cleanup() }
        }
        "lanjutan" -> AdvancedSettings(state.settings.rules,vm)
    }
}
@Composable private fun AdvancedSettings(current:Rules,vm:LhsViewModel) {
    var rules by remember(current) { mutableStateOf(current) }
    var confirm by remember { mutableStateOf(false) }
    Text("Pengaturan Lanjutan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Notice("DO = barang baru dari gudang. TG = Tutup Ganti / Sisa Stok Akhir. Stok tersedia = Stok Awal + DO. TG = Stok Awal + DO − Jual − Sampel − Bonus. Stok tester dihitung sendiri.")
    Text("Aturan Keuangan",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Text("Piutang: penjualan baru yang belum dibayar. Pelunasan: uang dari pembayaran piutang. Transfer: uang yang disetor. Pengeluaran: biaya operasional. Verifikasi arti dan arah perhitungannya dengan perusahaan.")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=rules.automaticFinance,onClick={ rules=rules.copy(automaticFinance=true,acknowledged=false) },label={ Text("Saldo otomatis") })
        FilterChip(selected=!rules.automaticFinance,onClick={ rules=rules.copy(automaticFinance=false,acknowledged=false) },label={ Text("Saldo manual") })
    }
    if(rules.automaticFinance) {
        listOf("Piutang" to rules.debtSign,"Pelunasan" to rules.settlementSign,"Transfer" to rules.transferSign,"Pengeluaran" to rules.expenseSign).forEachIndexed { i,(name,value)->
            Text(name,fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(-1 to "Kurangi",1 to "Tambahkan").forEach { (sign,text)->FilterChip(selected=value==sign,onClick={ rules=when(i) { 0->rules.copy(debtSign=sign,acknowledged=false);1->rules.copy(settlementSign=sign,acknowledged=false);2->rules.copy(transferSign=sign,acknowledged=false);else->rules.copy(expenseSign=sign,acknowledged=false) } },label={ Text(text) }) } }
        }
        Text(rules.financeText())
        Text(if(rules.acknowledged)"Aturan ini sudah disetujui." else "Aturan belum disetujui.",color=if(rules.acknowledged)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    } else Text("Sales mengisi Saldo Akhir secara langsung. Tidak ada rumus saldo yang diasumsikan sebagai aturan perusahaan.")
    Button(onClick={ confirm=true },modifier=Modifier.fillMaxWidth()) { Text(if(rules.automaticFinance)"Verifikasi & Simpan Aturan" else "Gunakan Saldo Manual") }
    if(confirm)Confirmation("Simpan aturan keuangan?",if(rules.automaticFinance)"Saya sudah memverifikasi aturan berikut: ${rules.financeText()}. Berlaku untuk laporan baru dan draf yang sedang dibuka. Laporan final lain tetap memakai aturannya sendiri." else "Saldo Akhir akan diisi manual pada laporan baru dan draf yang sedang dibuka. Nilai laporan final lain dipertahankan.","Setujui & Simpan",{ vm.saveRules(rules.copy(acknowledged=rules.automaticFinance));confirm=false },{ confirm=false })
}
@Composable private fun SavedOutlets(s:Settings,vm:LhsViewModel) {
    var search by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<SavedOutlet?>(null) }
    Text("Data Outlet",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Panel("Tambah Outlet") {
        TextInput("Nama Outlet",name) { name=it };TextInput("Alamat",address,true) { address=it }
        Button(onClick={ if(name.isBlank())vm.error("Isi nama outlet.") else { vm.saveOutlet(Outlet(name=name,address=address));name="";address="" } }) { Text("Tambahkan") }
    }
    TextInput("Cari outlet tersimpan",search) { search=it }
    s.outlets.filter { it.name.contains(search,true)||it.address.contains(search,true) }.forEach { o->Panel(o.name) {
        Text(o.address)
        Row { TextButton(onClick={ edit=o }) { Text("Edit") };ConfirmButton("Hapus","Hapus ${o.name} dari daftar outlet tersimpan? Transaksi laporan tidak ikut dihapus.") { vm.settings { it.copy(outlets=it.outlets-o) } } }
    } }
    edit?.let { original->
        var editedName by remember(original) { mutableStateOf(original.name) };var editedAddress by remember(original) { mutableStateOf(original.address) }
        AlertDialog(onDismissRequest={ edit=null },title={ Text("Edit Outlet") },text={ Column { TextInput("Nama Outlet",editedName) { editedName=it };TextInput("Alamat",editedAddress,true) { editedAddress=it } } },confirmButton={ TextButton(onClick={ if(editedName.isNotBlank()) { vm.settings { it.copy(outlets=it.outlets.map { row->if(row==original)SavedOutlet(editedName,editedAddress) else row }) };edit=null } }) { Text("Simpan Perubahan") } },dismissButton={ TextButton(onClick={ edit=null }) { Text("Batal") } })
    }
}
