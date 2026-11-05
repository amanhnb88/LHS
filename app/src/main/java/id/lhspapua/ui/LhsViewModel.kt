package id.lhspapua.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.lhspapua.data.Repository
import id.lhspapua.domain.*
import id.lhspapua.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class UiState(val ready: Boolean=false, val screen: String="home", val calls: List<CallReport> = emptyList(), val days: List<DailyReport> = emptyList(), val call: CallReport?=null, val day: DailyReport?=null, val settings: Settings=Settings(), val saved: String="", val error: String?=null, val busy:Boolean=false, val export: File?=null)
class LhsViewModel(app:Application): AndroidViewModel(app) {
    val repository=Repository(app)
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    private val jobs=Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val prefs=app.getSharedPreferences("navigation",0)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            for(job in jobs) { try { job() } catch(e:Exception) { mutable.update { it.copy(error=e.message ?: "Data gagal disimpan. Coba Simpan lagi.",saved="Gagal menyimpan") } } }
        }
        viewModelScope.launch {
            runCatching {
                val settings=repository.settings()
                mutable.update { it.copy(settings=settings,ready=true) }
                val id=prefs.getString("id",null); val type=prefs.getString("type",null)
                if(id!=null && type=="call") repository.call(id)?.let { r -> mutable.update { it.copy(call=r,screen="call") } }
                if(id!=null && type=="day") repository.daily(id)?.let { r -> mutable.update { it.copy(day=r,screen="day") } }
            }.onFailure { e -> mutable.update { it.copy(ready=true,error=e.message) } }
        }
        viewModelScope.launch { repository.calls.collect { rows -> mutable.update { it.copy(calls=rows) } } }
        viewModelScope.launch { repository.daily.collect { rows -> mutable.update { it.copy(days=rows) } } }
    }
    private fun enqueue(job:suspend ()->Unit) { jobs.trySend(job) }
    fun navigate(screen:String) { mutable.update { it.copy(screen=screen,error=null) }; if(screen=="home") prefs.edit().remove("id").remove("type").apply() }
    fun newCall() {
        val r=CallReport(identity=state.value.settings.profile.copy(date=java.time.LocalDate.now().toString()))
        open(r); save(r)
    }
    fun newDay() {
        val s=state.value.settings
        val r=DailyReport(identity=s.profile.copy(date=java.time.LocalDate.now().toString()),products=List(3) { p->Stock(sales=List(3) { t -> Sale(price=s.prices[p][t]) }) },rules=s.rules)
        open(r); save(r)
    }
    fun open(r:CallReport) { prefs.edit().putString("id",r.id).putString("type","call").apply(); mutable.update { it.copy(call=r,screen="call",export=null) } }
    fun open(r:DailyReport) { prefs.edit().putString("id",r.id).putString("type","day").apply(); mutable.update { it.copy(day=r,screen="day",export=null) } }
    fun call(change:(CallReport)->CallReport) { val r=change(state.value.call ?: return); mutable.update { it.copy(call=r,saved="Menyimpan…",export=null) }; save(r) }
    fun day(change:(DailyReport)->DailyReport) { val r=change(state.value.day ?: return); mutable.update { it.copy(day=r,saved="Menyimpan…",export=null) }; save(r) }
    private fun save(r:CallReport) = enqueue { repository.save(r); mutable.update { it.copy(saved="Tersimpan di perangkat") } }
    private fun save(r:DailyReport) = enqueue { repository.save(r); mutable.update { it.copy(saved="Tersimpan di perangkat") } }
    fun saveNow() { state.value.call?.takeIf { state.value.screen=="call" }?.let(::save); state.value.day?.takeIf { state.value.screen=="day" }?.let(::save) }
    fun settings(change:(Settings)->Settings) { val s=change(state.value.settings); mutable.update { it.copy(settings=s,saved="Menyimpan…") }; enqueue { repository.save(s); mutable.update { it.copy(saved="Pengaturan tersimpan") } } }
    fun saveOutlet(o:Outlet) {
        if(o.name.isBlank()) return
        settings { s -> s.copy(outlets=(s.outlets.filterNot { it.name.equals(o.name,true) }+SavedOutlet(o.name,o.address)).sortedBy { it.name }) }
    }
    fun removeCall(r:CallReport) = enqueue { repository.deleteCall(r.id); if(state.value.call?.id==r.id) mutable.update { it.copy(call=null) } }
    fun removeDay(r:DailyReport) = enqueue { repository.deleteDaily(r.id); if(state.value.day?.id==r.id) mutable.update { it.copy(day=null) } }
    fun error(message:String?) { mutable.update { it.copy(error=message) } }
    fun attach(uri:Uri) {
        val report=state.value.day ?: return
        enqueue {
            val a=repository.importAttachment(uri)
            // Preserve edits made while the file was being copied.
            val r=(state.value.day?.takeIf { it.id==report.id } ?: repository.daily(report.id) ?: report)
            val updated=r.copy(attachments=r.attachments+a); repository.save(updated)
            mutable.update { if(it.day?.id==report.id) it.copy(day=updated,saved="Lampiran tersimpan") else it }
        }
    }
    fun export(xlsx:Boolean) {
        val state=state.value
        val call=if(state.screen=="call") state.call else null
        val day=if(state.screen=="day") state.day else null
        if(call==null && day==null) return
        val errors=if(call!=null) Calculation.errors(call) else Calculation.errors(day!!)
        if(errors.isNotEmpty()) { error(errors.joinToString("\n")); return }
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val sheets=if(call!=null) Layout.calls(call) else listOf(Layout.daily(day!!))
                val id=call?.id ?: day!!.id; val identity=call?.identity ?: day!!.identity
                val file=File(getApplication<Application>().cacheDir,"exports/${if(call!=null) "CALLSHEET" else "LHS"}_${identity.date}_${id.take(8)}.${if(xlsx) "xlsx" else "pdf"}")
                file.parentFile!!.mkdirs()
                val tmp=File(file.parentFile,file.name+".tmp")
                try { tmp.outputStream().use { if(xlsx) ExcelExport.write(sheets,it) else PdfExport.write(sheets,it) }; check(tmp.renameTo(file)) { "File tidak bisa disimpan." } }
                finally { tmp.delete() }
                mutable.update { it.copy(export=file) }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun backup(onReady:(File)->Unit) { enqueue { val file=File(getApplication<Application>().cacheDir,"exports/LHS_BACKUP_${java.time.LocalDate.now()}.zip"); file.parentFile!!.mkdirs(); repository.backup(file); withContext(Dispatchers.Main) { onReady(file) } } }
    fun restore(uri:Uri) { enqueue { repository.restore(uri); val s=repository.settings(); mutable.update { it.copy(settings=s,call=null,day=null,saved="Backup dipulihkan. Buka laporan dari Riwayat.") }; prefs.edit().clear().apply() } }
    fun cleanup() { enqueue { val count=repository.cleanup(); mutable.update { it.copy(saved="$count lampiran yang tidak dipakai dihapus") } } }
}
