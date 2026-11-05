@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package id.lhspapua.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.lhspapua.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun LhsApp(vm:LhsViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    val dispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var settingsSection by rememberSaveable { mutableStateOf("") }
    var returnToDay by rememberSaveable { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() }
    DisposableEffect(vm,lifecycle) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)vm.flushPending() }
        lifecycle.lifecycle.addObserver(observer);onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.error) { state.error?.let { message->snackbar.showSnackbar(message,withDismissAction=true);vm.error(null) } }
    fun write(uri:android.net.Uri?) {
        val file=pendingFile ?: return
        if(uri!=null)scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out->file.inputStream().use { it.copyTo(out) } } ?: error("Lokasi penyimpanan tidak dapat dibuka.") } }
                .onSuccess { vm.error("File berhasil disimpan.") }.onFailure { vm.error(it.message ?: "File gagal disimpan.") }
        }
    }
    val savePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { write(it) }
    val saveXlsx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { write(it) }
    val saveZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { write(it) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri=it }
    val attachment=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attach) }
    fun save(file:File) { pendingFile=file;when(file.extension) { "pdf"->savePdf.launch(file.name);"xlsx"->saveXlsx.launch(file.name);else->saveZip.launch(file.name) } }
    fun share(files:List<File>) { runCatching { ShareFiles.share(context,files) }.onFailure { vm.error(it.message) } }
    SalesTheme {
        CompositionLocalProvider(LocalFieldFocus provides FieldFocus(state.focusTag,state.focusSerial),LocalInputCommit provides vm::flushPending,LocalInputLocked provides state.busy) {
            BackHandler(state.screen!="home") {
                if(state.screen=="settings"&&settingsSection.isNotEmpty())settingsSection=""
                else if(state.screen=="settings"&&returnToDay) { returnToDay=false;vm.navigate("day") }
                else vm.navigate("home")
            }
            val title=when(state.screen) { "call","calls"->"Callsheet";"day","days"->"Laporan Harian";"history"->"Riwayat";"settings"->"Pengaturan";else->"LHS Papua" }
            Scaffold(contentWindowInsets=WindowInsets.safeDrawing,snackbarHost={ SnackbarHost(snackbar) },topBar={ TopAppBar(title={ Text(title,fontWeight=FontWeight.Bold) },navigationIcon={ if(state.screen!="home")TextButton(onClick={ dispatcher?.onBackPressed() }) { Text("Kembali") } }) }) { padding->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    if(!state.ready)CircularProgressIndicator(Modifier.align(Alignment.Center)) else when(state.screen) {
                        "home"->HomeScreen(state,vm) { settingsSection="";returnToDay=false;vm.navigate("settings") }
                        "calls","days"->ReportHub(state.screen=="days",state,vm)
                        "call"->state.call?.let { CallsheetScreen(it,state,vm,::save,::share) }
                        "day"->state.day?.let { DailyScreen(it,state,vm,::save,::share,{ attachment.launch(arrayOf("image/*","application/pdf")) }) { settingsSection="lanjutan";returnToDay=true;vm.navigate("settings") } }
                        "history"->HistoryScreen(state,vm)
                        "settings"->ScrollPage(pageKey=settingsSection) {
                            SettingsScreen(settingsSection,{ settingsSection=it },state,vm,backup={ vm.backup(::save) },restore={ restore.launch(arrayOf("application/zip","application/octet-stream")) })
                            if(returnToDay)TextButton(onClick={ returnToDay=false;settingsSection="";vm.navigate("day") }) { Text("Kembali ke laporan") }
                            SaveStatus(state,vm)
                        }
                    }
                }
            }
            state.notice?.let { Confirmation("Periksa laporan terkait",it,"Mengerti",vm::dismissNotice,vm::dismissNotice) }
            restoreUri?.let { uri->Confirmation("Pulihkan backup?","Laporan dengan ID yang sama diganti dari backup. Simpan backup saat ini jika ingin mempertahankan salinannya.","Pulihkan",{ vm.restore(uri);restoreUri=null },{ restoreUri=null }) }
            state.preview?.let { DocumentPreview(it) { vm.preview(null) } }
        }
    }
}
