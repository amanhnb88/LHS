@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package id.lhspapua.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.provider.DocumentsContract
import androidx.compose.ui.res.painterResource
import id.lhspapua.R
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

@Composable fun LhsApp(vm:LhsViewModel=viewModel(),onExit:()->Unit={}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    val dispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var pendingJpegs by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var settingsSection by rememberSaveable { mutableStateOf("") }
    var settingsOrigin by rememberSaveable { mutableStateOf("home") }
    var exitDialog by rememberSaveable { mutableStateOf(false) }
    var exitFailed by rememberSaveable { mutableStateOf(false) }
    var exitWaiting by remember { mutableStateOf(false) }
    val pages=rememberSaveableStateHolder()
    val tab=RootTab.entries.firstOrNull { it.route==state.screen }
    val imeOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val activeTab=when(state.screen) { "call"->"calls";"day"->"days";else->state.root }
    val showTabs=tab!=null || state.screen=="call" || state.screen=="day"
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun selectTab(route:String) {
        focus.clearFocus()
        keyboard?.hide()
        vm.selectTab(route)
    }
    fun openSettings(section:String="") {
        focus.clearFocus()
        keyboard?.hide()
        settingsOrigin=state.screen
        settingsSection=section
        vm.navigate("settings")
    }
    fun requestExit(retry:Boolean=false) {
        if(exitWaiting)return
        exitWaiting=true
        scope.launch {
            if(retry)vm.retrySave()
            val canExit=vm.readyToExit()
            exitWaiting=false
            if(canExit && exitDialog) {
                exitDialog=false
                onExit()
            } else exitFailed=!canExit
        }
    }
    val snackbar=remember { SnackbarHostState() }
    DisposableEffect(vm,lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP)vm.flushPending()
            if(event==Lifecycle.Event.ON_RESUME)vm.refreshSalesDate()
        }
        lifecycle.lifecycle.addObserver(observer);onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.error) { state.error?.let { message->snackbar.showSnackbar(message,withDismissAction=true);vm.error(null) } }
    fun write(uri:android.net.Uri?) {
        val file=pendingPath?.let(::File) ?: return
        pendingPath=null
        if(uri!=null)scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out->file.inputStream().use { it.copyTo(out) } } ?: error("Lokasi penyimpanan tidak dapat dibuka.") } }
                .onSuccess { vm.error("File berhasil disimpan.") }.onFailure { vm.error(it.message ?: "File gagal disimpan.") }
        }
    }
    val savePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { write(it) }
    val saveXlsx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { write(it) }
    val saveJpeg=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { write(it) }
    val saveJpegFolder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val files=pendingJpegs.map(::File)
        pendingJpegs=arrayListOf()
        if(tree!=null && files.isNotEmpty())scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver=context.contentResolver
                    val folder=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                    val created=mutableListOf<android.net.Uri>()
                    try {
                        for(file in files) {
                            val target=DocumentsContract.createDocument(resolver,folder,"image/jpeg",file.name)
                                ?: error("Folder tujuan tidak dapat ditulis.")
                            created.add(target)
                            val output=resolver.openOutputStream(target) ?: error("File tujuan tidak dapat dibuka.")
                            output.use { out->file.inputStream().use { it.copyTo(out) } }
                        }
                    } catch(e:Exception) {
                        for(target in created)runCatching { DocumentsContract.deleteDocument(resolver,target) }
                        throw e
                    }
                }
            }.onSuccess { vm.error("${files.size} halaman JPG berhasil disimpan.") }
                .onFailure { vm.error(it.message ?: "JPG gagal disimpan.") }
        }
    }
    val saveZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { write(it) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri=it }
    val attachment=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attach) }
    fun save(file:File) { pendingPath=file.absolutePath;when(file.extension) { "pdf"->savePdf.launch(file.name);"xlsx"->saveXlsx.launch(file.name);"jpg"->saveJpeg.launch(file.name);else->saveZip.launch(file.name) } }
    fun saveFiles(files:List<File>) {
        if(files.size==1)save(files.single()) else {
            pendingJpegs=ArrayList(files.map { it.absolutePath })
            saveJpegFolder.launch(null)
        }
    }
    fun share(files:List<File>) { runCatching { ShareFiles.share(context,files) }.onFailure { vm.error(it.message) } }
    SalesTheme {
        CompositionLocalProvider(LocalFieldFocus provides FieldFocus(state.focusTag,state.focusSerial),LocalInputCommit provides vm::flushPending,LocalInputLocked provides state.busy) {
            KeyboardAwareBack(enabled=state.ready && state.screen!="call" && state.screen!="day") {
                when {
                    state.screen=="settings" && settingsSection.isNotEmpty() -> settingsSection=""
                    state.screen=="settings" -> vm.navigate(settingsOrigin)
                    state.screen=="home" -> { exitFailed=false;exitDialog=true }
                    else -> vm.navigate("home")
                }
            }
            val title=tab?.title ?: when(state.screen) {
                "call" -> "Callsheet"
                "day" -> "Laporan Harian"
                "settings" -> "Pengaturan"
                else -> "LHS Papua"
            }
            Scaffold(
                modifier=Modifier.imePadding(),
                contentWindowInsets=WindowInsets.safeDrawing,
                snackbarHost={ SnackbarHost(snackbar) },
                topBar={
                    TopAppBar(
                        title={ Text(title,fontWeight=FontWeight.Bold) },
                        navigationIcon={
                            if(tab==null || tab!=RootTab.HOME)IconButton(onClick={ dispatcher?.onBackPressed() }) {
                                Icon(painterResource(R.drawable.nav_back),"Kembali")
                            }
                        },
                        actions={
                            if(tab!=null)IconButton(onClick={ openSettings() }) {
                                Icon(painterResource(R.drawable.nav_settings),"Pengaturan")
                            }
                        }
                    )
                },
                bottomBar={ if(showTabs)RootNavigation(activeTab,imeOpen,::selectTab) }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    if(!state.ready)CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else {
                        val page=when(state.screen) {
                            "call" -> "call-${state.call?.id}"
                            "day" -> "day-${state.day?.id}"
                            else -> state.screen
                        }
                        pages.SaveableStateProvider(page) {
                            when(state.screen) {
                                "home" -> HomeScreen(vm)
                                "calls","days" -> ReportHub(state.screen=="days",vm)
                                "call" -> state.call?.let { CallsheetScreen(it,state,vm,::saveFiles,::share) }
                                "day" -> state.day?.let {
                                    DailyScreen(it,state,vm,::saveFiles,::share,{ attachment.launch(arrayOf("image/*","application/pdf")) }) {
                                        openSettings("lanjutan")
                                    }
                                }
                                "drafts" -> ReportsScreen(state,vm,drafts=true)
                                "history" -> ReportsScreen(state,vm,drafts=false)
                                "settings" -> ScrollPage(pageKey=settingsSection) {
                                    SettingsScreen(settingsSection,{ settingsSection=it },state.settings,state.day,state.busy,vm,backup={ vm.backup(::save) },restore={ restore.launch(arrayOf("application/zip","application/octet-stream")) })
                                    SaveStatus(state,vm)
                                }
                            }
                        }
                    }
                }
            }
            if(exitDialog)AlertDialog(
                onDismissRequest={ if(!exitWaiting)exitDialog=false },
                title={ Text("Keluar dari LHS Papua?") },
                text={
                    Text(when {
                        exitWaiting -> "Menunggu penyimpanan selesai…"
                        exitFailed -> "Penyimpanan gagal. Coba lagi atau tetap di aplikasi untuk memeriksa data."
                        else -> "Apakah Anda yakin ingin keluar dari aplikasi? Data yang sudah disimpan akan tetap tersedia saat aplikasi dibuka kembali."
                    })
                },
                dismissButton={ TextButton(onClick={ exitDialog=false },enabled=!exitWaiting) { Text("Batal") } },
                confirmButton={ TextButton(onClick={ requestExit(exitFailed) },enabled=!exitWaiting) { Text(if(exitFailed)"Coba Lagi" else "Keluar") } }
            )
            state.notice?.let { Confirmation("Periksa laporan terkait",it,"Mengerti",vm::dismissNotice,vm::dismissNotice) }
            restoreUri?.let { uri->Confirmation("Pulihkan backup?","Laporan dengan ID yang sama diganti dari backup. Simpan backup saat ini jika ingin mempertahankan salinannya.","Pulihkan",{ vm.restore(uri);restoreUri=null },{ restoreUri=null }) }
            state.preview?.let { file ->
                DocumentPreview(file,state.previewTitle,bottomBar={ RootNavigation(activeTab,select=::selectTab) }) { vm.preview(null) }
            }
        }
    }
}
