package id.lhspapua.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.lhspapua.data.Repository
import id.lhspapua.domain.*
import id.lhspapua.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.util.Locale

data class UiState(
    val ready:Boolean=false,val screen:String="home",val calls:List<CallReport> = emptyList(),val days:List<DailyReport> = emptyList(),
    val call:CallReport?=null,val day:DailyReport?=null,val settings:Settings=Settings(),val saved:String="Tersimpan",
    val pending:Int=0,val error:String?=null,val busy:Boolean=false,val pdf:File?=null,val xlsx:File?=null,val preview:File?=null,
    val notice:String?=null,val saveFailed:Boolean=false)

class LhsViewModel(app:Application):AndroidViewModel(app) {
    val repository=Repository(app)
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    private val jobs=Channel<suspend ()->Unit>(Channel.UNLIMITED)
    private var dayTicket=0L
    private var callTicket=0L
    private val prefs=app.getSharedPreferences("navigation",0)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            for(job in jobs) {
                try { job() } catch(e:CancellationException) { throw e }
                catch(e:Exception) { mutable.update { it.copy(error=e.message ?: "Pekerjaan gagal. Coba lagi.",saveFailed=true) } }
                finally { mutable.update { val n=(it.pending-1).coerceAtLeast(0); it.copy(pending=n,saved=if(it.saveFailed) "Gagal menyimpan" else if(n==0) "Tersimpan" else "Menyimpan…") } }
            }
        }
        viewModelScope.launch {
            runCatching {
                val settings=repository.settings()
                mutable.update { it.copy(settings=settings,ready=true) }
                val id=prefs.getString("id",null)
                when(prefs.getString("type",null)) {
                    "call" -> id?.let { repository.call(it) }?.let(::open)
                    "day" -> id?.let { repository.daily(it) }?.let(::open)
                }
            }.onFailure { error(it.message ?: "Penyimpanan tidak dapat dibuka."); mutable.update { it.copy(ready=true) } }
        }
        viewModelScope.launch { repository.calls.collect { rows -> mutable.update { it.copy(calls=rows) } } }
        viewModelScope.launch { repository.daily.collect { rows -> mutable.update { it.copy(days=rows) } } }
    }
    private fun enqueue(job:suspend ()->Unit) {
        mutable.update { it.copy(pending=it.pending+1,saved="Menyimpan…",saveFailed=false) }
        jobs.trySend(job)
    }
    fun rememberedStep(id:String)=prefs.getInt("step-$id",0)
    fun rememberStep(id:String,step:Int) { prefs.edit().putInt("step-$id",step).apply() }
    fun error(message:String?) { mutable.update { it.copy(error=message) } }
    fun dismissNotice() { mutable.update { it.copy(notice=null) } }
    fun navigate(screen:String) {
        mutable.update { it.copy(screen=screen,error=null) }
        if(screen=="home") prefs.edit().remove("id").remove("type").apply()
    }
    fun newCall() { val r=CallReport(identity=state.value.settings.profile.copy(date=LocalDate.now().toString()));open(r);saveCall(r) }
    fun newDay() {
        val s=state.value.settings
        val identity=s.profile.copy(date=LocalDate.now().toString())
        val r=newDaily(identity,s,StockLedger.sequence(identity,state.value.days))
        open(r);saveDay(r)
    }
    fun open(r:CallReport) {
        prefs.edit().putString("id",r.id).putString("type","call").apply()
        mutable.update { it.copy(call=r,screen="call",pdf=null,xlsx=null) }
    }
    fun open(r:DailyReport) {
        prefs.edit().putString("id",r.id).putString("type","day").apply()
        mutable.update { it.copy(day=r,screen="day",pdf=null,xlsx=null) }
    }
    fun call(change:(CallReport)->CallReport) {
        val r=change(state.value.call ?: return)
        mutable.update { it.copy(call=r,pdf=null,xlsx=null) };saveCall(r)
    }
    fun day(change:(DailyReport)->DailyReport) {
        val old=state.value.day ?: return
        if(old.status==ReportStatus.FINAL) { error("Buka kembali laporan sebelum mengubahnya.");return }
        val r=change(old)
        mutable.update { it.copy(day=r,pdf=null,xlsx=null) };saveDay(r)
    }
    private fun saveCall(r:CallReport) {
        val ticket=++callTicket
        enqueue { repository.save(r);if(ticket==callTicket)mutable.update { it.copy(saveFailed=false) } }
    }
    private fun saveDay(r:DailyReport) {
        val ticket=++dayTicket
        enqueue {
            val result=repository.saveDay(r)
            mutable.update { current ->
                current.copy(day=if(ticket==dayTicket && current.day?.id==r.id) result.reports.first { it.id==r.id } else current.day,
                    notice=if(result.affected.isNotEmpty()) "Perubahan memengaruhi ${result.affected.size} laporan berikutnya. Draf dihitung ulang; laporan final yang terdampak ditandai untuk diperiksa." else current.notice)
            }
        }
    }
    fun retrySave() { when(state.value.screen) { "call"->state.value.call?.let(::saveCall); "day"->state.value.day?.let(::saveDay) } }
    fun settings(change:(Settings)->Settings) {
        val s=change(state.value.settings);mutable.update { it.copy(settings=s) };enqueue { repository.save(s) }
    }
    fun updateIdentity(identity:Identity) {
        val r=state.value.day ?: return
        val ownerChanged=!identity.sales.trim().equals(r.identity.sales.trim(),true)
        val changed=ownerChanged || identity.date!=r.identity.date
        day { it.copy(identity=identity.copy(stockOwnerId=if(ownerChanged) "" else identity.stockOwnerId),
            openingMode=if(changed) OpeningMode.AUTO else it.openingMode,
            stockSourceId=if(changed)null else it.stockSourceId,sourceRevision=if(changed)null else it.sourceRevision,
            products=if(changed)it.products.map { s->s.copy(opening=null) } else it.products,
            testers=if(changed)it.testers.map { t->t.copy(opening=null) } else it.testers,
            stockSequence=if(changed)1 else it.stockSequence) }
    }
    fun prepareDailyIdentity(onReady:()->Unit) {
        val selected=state.value.day ?: return
        if(selected.identity.sales.isBlank()) { error("Isi nama Sales terlebih dahulu.");return }
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val r=repository.daily(selected.id) ?: selected
                val s=repository.settings()
                val name=r.identity.sales.trim()
                val all=repository.allDaily()
                val known=all.firstOrNull { it.identity.stockOwnerId.isNotBlank() && it.identity.sales.trim().equals(name,true) }
                val owner=s.owners.firstOrNull { it.name.trim().lowercase(Locale.ROOT)==name.lowercase(Locale.ROOT) } ?: known?.let { StockOwner(it.identity.stockOwnerId,name) } ?: StockOwner(name=name)
                val settings=s.copy(owners=if(s.owners.any { it.id==owner.id })s.owners else s.owners+owner,
                    profile=if(s.profile.sales.isBlank() || s.profile.sales.trim().equals(name,true))r.identity.copy(stockOwnerId=owner.id) else s.profile)
                repository.save(settings)
                val identity=r.identity.copy(sales=name,stockOwnerId=owner.id)
                val sequence=if(r.identity.stockOwnerId==owner.id) r.stockSequence else StockLedger.sequence(identity,all.filterNot { it.id==r.id })
                val result=repository.saveDay(r.copy(identity=identity,stockSequence=sequence))
                mutable.update { it.copy(settings=settings,day=result.reports.first { it.id==r.id }) }
                withContext(Dispatchers.Main) { onReady() }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun manualOpening() { day { it.copy(openingMode=OpeningMode.MANUAL,stockSourceId=null,sourceRevision=null,stockStale=false,stockIssue="",openingNote="Stok awal dicatat langsung oleh sales") } }
    fun automaticOpening() { day { it.copy(openingMode=OpeningMode.AUTO,stockStale=false) } }
    fun adoptLegacy() { day { StockLedger.adoptLegacy(it) } }
    fun finalizeDay(onReady:()->Unit) {
        val r=state.value.day ?: return
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val result=repository.finalizeDay(r.id)
                mutable.update { it.copy(day=result.reports.first { d->d.id==r.id }) }
                withContext(Dispatchers.Main) { onReady() }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun reopen(onReady:()->Unit) {
        val r=state.value.day ?: return
        enqueue {
            val result=repository.reopen(r.id)
            mutable.update { it.copy(day=result.reports.first { d->d.id==r.id },pdf=null,xlsx=null,
                notice=if(result.affected.isNotEmpty()) "Laporan terkait akan diperiksa ulang karena sumber stok dibuka kembali." else it.notice) }
            withContext(Dispatchers.Main) { onReady() }
        }
    }
    fun saveRules(rules:Rules) {
        val s=state.value.settings.copy(rules=rules)
        val r=state.value.day?.takeIf { it.status==ReportStatus.DRAFT }
        mutable.update { it.copy(settings=s,day=if(r!=null) r.copy(rules=rules) else it.day,pdf=null,xlsx=null) }
        enqueue { repository.save(s); if(r!=null) repository.saveDay(r.copy(rules=rules)) }
    }
    fun saveOutlet(o:Outlet) {
        if(o.name.isBlank())return
        settings { s -> s.copy(outlets=(s.outlets.filterNot { it.name.equals(o.name,true) }+SavedOutlet(o.name,o.address)).sortedBy { it.name }) }
    }
    fun removeCall(r:CallReport) { enqueue { repository.deleteCall(r.id) } }
    fun removeDay(r:DailyReport) { enqueue { repository.deleteDaily(r.id);mutable.update { it.copy(notice="Laporan dihapus. Laporan yang memakai stoknya telah diperiksa ulang.") } } }
    fun attach(uri:Uri) {
        val id=state.value.day?.id ?: return
        enqueue {
            val a=repository.importAttachment(uri)
            val r=repository.daily(id) ?: kotlin.error("Laporan tidak ditemukan.")
            require(r.status!=ReportStatus.FINAL) { "Buka kembali laporan untuk menambah lampiran." }
            val result=repository.saveDay(r.copy(attachments=r.attachments+a))
            mutable.update { if(it.day?.id==id)it.copy(day=result.reports.first { d->d.id==id }) else it }
        }
    }
    fun export(xlsx:Boolean=false,preview:Boolean=false) {
        val type=state.value.screen;val id=if(type=="call")state.value.call?.id else state.value.day?.id
        if(id==null)return
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val call=if(type=="call")repository.call(id) else null
                val day=if(type=="day")repository.daily(id) else null
                val errors=if(call!=null)Calculation.errors(call) else Calculation.errors(requireNotNull(day))
                require(errors.isEmpty()) { errors.joinToString("\n") }
                if(day?.formatVersion==2 && !preview) require(day.status==ReportStatus.FINAL) { "Selesaikan pemeriksaan laporan sebelum ekspor final." }
                val sheets=if(call!=null)Layout.calls(call) else if(xlsx)Layout.dailyWorkbook(day!!) else listOf(Layout.daily(day!!))
                val identity=call?.identity ?: day!!.identity
                val file=File(getApplication<Application>().cacheDir,"exports/${if(call!=null) "CALLSHEET" else "LHS"}_${identity.date}_${id.take(8)}.${if(xlsx) "xlsx" else "pdf"}")
                file.parentFile!!.mkdirs()
                val temp=File(file.parentFile,file.name+".tmp")
                try { temp.outputStream().use { if(xlsx)ExcelExport.write(sheets,it) else PdfExport.write(sheets,it) };check(temp.renameTo(file)) { "File gagal disimpan." } } finally { temp.delete() }
                mutable.update { if(xlsx)it.copy(xlsx=file) else it.copy(pdf=file,preview=if(preview)file else null) }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun preview(file:File?) { mutable.update { it.copy(preview=file) } }
    fun backup(onReady:(File)->Unit) { enqueue { val file=File(getApplication<Application>().cacheDir,"exports/LHS_BACKUP_${LocalDate.now()}.zip");file.parentFile!!.mkdirs();repository.backup(file);withContext(Dispatchers.Main) { onReady(file) } } }
    fun restore(uri:Uri) { enqueue { repository.restore(uri);val s=repository.settings();mutable.update { it.copy(settings=s,call=null,day=null,screen="history",notice="Backup dipulihkan. Laporan lama dan lampirannya tetap tersedia.") };prefs.edit().clear().apply() } }
    fun cleanup() { enqueue { val n=repository.cleanup();mutable.update { it.copy(notice="$n lampiran yang tidak terpakai dihapus") } } }
}
