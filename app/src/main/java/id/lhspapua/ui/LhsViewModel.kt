package id.lhspapua.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import id.lhspapua.data.Repository
import id.lhspapua.data.WriteBuffer
import kotlinx.serialization.encodeToString
import id.lhspapua.domain.*
import id.lhspapua.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

data class UiState(
    val ready:Boolean=false,val screen:String="home",val root:String="home",val calls:List<CallReport> = emptyList(),val days:List<DailyReport> = emptyList(),
    val call:CallReport?=null,val day:DailyReport?=null,val settings:Settings=Settings(),val saved:String="Tersimpan",
    val pending:Int=0,val error:String?=null,val busy:Boolean=false,val pdf:File?=null,val xlsx:File?=null,val preview:File?=null,
    val notice:String?=null,val saveFailed:Boolean=false,val previewTitle:String="Pratinjau PDF",
    val focusTag:String="",val focusSerial:Long=0,val lastId:String?=null,val lastType:String?=null)

internal data class DashboardUi(val period:SalesPeriod,val sales:String?,val summary:SalesDashboard)

class LhsViewModel(app:Application,private val navigation:SavedStateHandle=SavedStateHandle()):AndroidViewModel(app) {
    val repository=Repository(app)
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    private val salesDate=MutableStateFlow(ReportTime.date(Instant.now()))
    private val selectedPeriod=MutableStateFlow(SalesPeriod.WEEK)
    private val selectedSales=MutableStateFlow<String?>(null)
    internal val dashboard=combine(
        state.map { it.days }.distinctUntilChanged(),salesDate,selectedPeriod,selectedSales
    ) { reports,today,period,sales ->
        val summary=SalesDashboard.summarize(reports,today,period,sales)
        DashboardUi(period,sales,summary)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)

    internal fun dashboardPeriod(period:SalesPeriod) { selectedPeriod.value=period }
    internal fun dashboardSales(sales:String?) { selectedSales.value=sales }
    internal fun refreshSalesDate() { salesDate.value=ReportTime.date(Instant.now()) }

    private val jobs=Channel<suspend ()->Unit>(Channel.UNLIMITED)
    private var dayTicket=0L
    private val prefs=app.getSharedPreferences("navigation",0)
    private val journal=app.getSharedPreferences("draft-journal",0)
    private val journalLock=Any()
    private data class Write(val payload:String,val action:suspend ()->Unit)
    private val writes=WriteBuffer<Write>()
    var durableWrites:Int=0; private set
    private fun clearJournalEntry(key:String,payload:String) {
        val removed=synchronized(journalLock) {
            if(journal.getString(key,null)!=payload) false else {
                journal.edit().remove(key).apply()
                true
            }
        }
        // Preserve the durability barrier without holding the lock used by UI edits.
        if(removed)check(journal.edit().commit()) { "Catatan draf belum dapat diperbarui." }
    }

    private suspend fun deleteFromRepository(key:String,draftOnly:Boolean) {
        val id=key.substringAfter(':')
        when(key.substringBefore(':')) {
            "call" -> repository.deleteCall(id,draftOnly)
            "day" -> repository.deleteDaily(id,draftOnly)
            else -> kotlin.error("Jenis laporan tidak dikenal.")
        }
    }

    private suspend fun finishDelete(key:String,draftOnly:Boolean) {
        val marker="delete:$key"
        try {
            deleteFromRepository(key,draftOnly)
        } catch(e:CancellationException) {
            throw e
        } catch(e:Exception) {
            check(journal.edit().remove(marker).commit()) { "Catatan penghapusan belum dapat dipulihkan." }
            throw e
        }
        // A crash before this commit replays the delete, never the pending draft.
        check(journal.edit().remove(key).remove(marker).commit()) { "Catatan penghapusan belum dapat disimpan." }
    }

    private suspend fun recoverDrafts() {
        journal.all.filterKeys { it.startsWith("delete:") }.forEach { (marker,draftOnly) ->
            finishDelete(marker.removePrefix("delete:"),draftOnly==true)
        }
        if(journal.contains("flush-time"))check(journal.edit().remove("flush-time").commit())
        val saved=journal.all.mapNotNull { (key,value)->(value as? String)?.let { key to it } }.sortedBy { if(it.first=="settings")0 else 1 }
        for((key,payload) in saved) {
            when {
                key=="settings"->repository.save(repository.json.decodeFromString<Settings>(payload))
                key.startsWith("call:")->repository.save(repository.json.decodeFromString<CallReport>(payload))
                key.startsWith("day:")->repository.save(repository.json.decodeFromString<DailyReport>(payload))
                else->kotlin.error("Catatan draf tidak dikenal.")
            }
            clearJournalEntry(key,payload)
        }
    }
    private fun queueWrite(key:String,payload:String,action:suspend ()->Unit) {
        synchronized(journalLock) { journal.edit().putString(key,payload).apply() }
        mutable.update { it.copy(saved="Menyimpan…") }
        if(writes.replace(key,Write(payload,action))) enqueue(dataWrite=true) {
            delay(180)
            val latest=writes.take(key) ?: return@enqueue
            latest.action();durableWrites++
            // Do not erase a newer edit which arrived while this transaction was in progress.
            clearJournalEntry(key,latest.payload)
        }
    }
    fun focus(tag:String) { mutable.update { it.copy(focusTag=tag,focusSerial=it.focusSerial+1) } }
    fun flushPending() {
        enqueue(dataWrite=true) {
            // A synchronous commit waits for the journal's asynchronous applies.
            check(journal.edit().commit()) { "Catatan draf belum dapat disimpan." }
        }
    }

    suspend fun readyToExit():Boolean {
        flushPending()
        val drained=state.first { it.pending==0 }
        return !drained.saveFailed && !drained.busy && journal.all.isEmpty()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for(job in jobs) {
                try { job() } catch(e:CancellationException) { throw e }
                catch(e:Exception) { mutable.update { it.copy(error=e.message ?: "Pekerjaan gagal. Coba lagi.") } }
                finally {
                    mutable.update {
                        val pending=(it.pending-1).coerceAtLeast(0)
                        val status=when {
                            it.saveFailed -> "Gagal menyimpan"
                            pending==0 -> "Tersimpan"
                            else -> "Menyimpan…"
                        }
                        it.copy(pending=pending,saved=status)
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { recoverDrafts() }
                val settings=repository.settings()
                val call=navigation.get<String>("callId")?.let { repository.call(it) }
                val day=navigation.get<String>("dayId")?.let { repository.daily(it) }
                val root=navigation.get<String>("root")?.takeIf { it in RootTab.routes } ?: "home"
                val screen=when(val saved=navigation.get<String>("screen")) {
                    "call" -> if(call!=null) saved else root
                    "day" -> if(day!=null) saved else root
                    "settings" -> saved
                    else -> root
                }
                mutable.update { it.copy(settings=settings,ready=true,screen=screen,root=root,call=call,day=day,
                    lastId=prefs.getString("id",null),lastType=prefs.getString("type",null)) }
            }.onFailure { error(it.message ?: "Penyimpanan tidak dapat dibuka."); mutable.update { it.copy(ready=true,saveFailed=true,saved="Gagal menyimpan") } }
        }
        viewModelScope.launch { repository.calls.flowOn(Dispatchers.Default).collect { rows -> mutable.update { it.copy(calls=rows) } } }
        viewModelScope.launch { repository.daily.flowOn(Dispatchers.Default).collect { rows -> mutable.update { it.copy(days=rows) } } }
    }
    private fun enqueue(dataWrite:Boolean=false,job:suspend ()->Unit) {
        mutable.update { it.copy(pending=it.pending+1) }
        jobs.trySend {
            try { job() } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(dataWrite)mutable.update { it.copy(saveFailed=true) };throw e }
        }
    }
    fun rememberedSection(id:String)=prefs.getInt("section-v3-$id",0)
    fun rememberSection(id:String,section:Int) { prefs.edit().putInt("section-v3-$id",section).apply() }
    fun rememberedOutlet(id:String)=prefs.getString("outlet-v3-$id",null)
    fun rememberOutlet(id:String,outlet:String?) { prefs.edit().putString("outlet-v3-$id",outlet).apply() }
    fun rememberedStep(id:String)=prefs.getInt("step-$id",0)
    fun rememberStep(id:String,step:Int) { prefs.edit().putInt("step-$id",step).apply() }
    fun error(message:String?) { mutable.update { it.copy(error=message) } }
    fun dismissNotice() { mutable.update { it.copy(notice=null) } }
    fun navigate(screen:String) {
        if(screen=="home")refreshSalesDate()
        val root=screen.takeIf { it in RootTab.routes } ?: state.value.root
        navigation["screen"]=screen
        navigation["root"]=root
        mutable.update { it.copy(screen=screen,root=root,error=null,preview=null) }
        flushPending()
    }
    fun backToRoot() {
        val current=state.value
        navigation["resume-${current.screen}"]=false
        navigate(current.root)
    }

    fun selectTab(route:String) {
        if(route !in RootTab.routes)return
        val current=state.value
        val editor=when(route) { "calls"->"call";"days"->"day";else->null }
        preview(null)
        if(current.screen==editor) {
            navigation["resume-$editor"]=false
            navigate(route)
            return
        }
        if(current.screen==route)return
        if(current.screen=="call" || current.screen=="day")navigation["resume-${current.screen}"]=true
        navigate(route)
        if(editor!=null && navigation.get<Boolean>("resume-$editor")==true) {
            if(editor=="call" && current.call!=null || editor=="day" && current.day!=null)navigate(editor)
        }
    }

    fun newCall() {
        if(state.value.busy)return
        val now=Instant.now()
        val identity=state.value.settings.profile.uppercaseFields().copy(date=ReportTime.date(now).toString())
        val r=CallReport(identity=identity,createdAt=now.toString())
        open(r);saveCall(r)
    }
    fun newDay() {
        if(state.value.busy)return
        val now=Instant.now()
        val identity=state.value.settings.profile.copy(date=ReportTime.date(now).toString())
        val r=newDaily(identity).copy(createdAt=now.toString(),automaticMadeBy=true)
        open(r);saveDay(r)
    }
    fun open(r:CallReport) {
        if(state.value.busy)return
        prefs.edit().putString("id",r.id).putString("type","call").apply()
        navigation["screen"]="call"
        navigation["callId"]=r.id
        mutable.update { it.copy(call=it.call?.takeIf { current->current.id==r.id && journal.contains("call:${r.id}") } ?: r,screen="call",pdf=null,xlsx=null,lastId=r.id,lastType="call",focusTag="") }
    }
    fun open(r:DailyReport) {
        if(state.value.busy)return
        prefs.edit().putString("id",r.id).putString("type","day").apply()
        navigation["screen"]="day"
        navigation["dayId"]=r.id
        mutable.update { it.copy(day=it.day?.takeIf { current->current.id==r.id && journal.contains("day:${r.id}") } ?: r,screen="day",pdf=null,xlsx=null,lastId=r.id,lastType="day",focusTag="") }
    }
    fun call(change:(CallReport)->CallReport) {
        if(state.value.busy)return
        val old=state.value.call ?: return
        val r=change(old).copy(completed=false,completedAt=null)
        if(r==old)return
        mutable.update { it.copy(call=r,pdf=null,xlsx=null) };saveCall(r)
    }
    fun day(change:(DailyReport)->DailyReport) {
        if(state.value.busy)return
        val old=state.value.day ?: return
        if(old.status==ReportStatus.FINAL) { error("Buka kembali laporan sebelum mengubahnya.");return }
        val edit=change(old)
        val r=if(old.automaticMadeBy)edit.copy(finance=edit.finance.copy(madeBy=edit.identity.uppercaseFields().sales)) else edit
        if(r==old)return
        mutable.update { it.copy(day=r,pdf=null,xlsx=null) };saveDay(r)
    }
    fun changeCallsheetLink(reportId:String,sourceId:String?,fingerprint:String?) {
        val current=state.value
        val report=current.day ?: return
        if(current.busy || report.id!=reportId || report.status!=ReportStatus.DRAFT)return
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val updated=repository.changeCallsheetLink(reportId,sourceId,fingerprint)
                mutable.update { if(it.day?.id==reportId)it.copy(day=updated) else it }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }

    fun enableAutomaticSold() {
        if(state.value.day?.canEnableAutomaticSold!=true)return
        day { it.copy(formatVersion=4) }
    }

    private fun saveCall(r:CallReport) {
        queueWrite("call:${r.id}",repository.json.encodeToString(r)) { repository.save(r) }
    }
    private fun saveDay(r:DailyReport) {
        val ticket=++dayTicket
        queueWrite("day:${r.id}",repository.json.encodeToString(r)) {
            val result=repository.saveDay(r)
            mutable.update { current ->
                current.copy(day=if(ticket==dayTicket && current.day?.id==r.id) result.reports.first { it.id==r.id } else current.day,
                    notice=if(result.affected.isNotEmpty()) "Perubahan memengaruhi ${result.affected.size} laporan berikutnya. Draf dihitung ulang; laporan final yang terdampak ditandai untuk diperiksa." else current.notice)
            }
        }
    }
    fun retrySave() {
        if(state.value.busy)return
        mutable.update { it.copy(busy=true) }
        enqueue(dataWrite=true) {
            try {
                recoverDrafts()
                mutable.update { it.copy(saveFailed=false) }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun settings(change:(Settings)->Settings) {
        val current=state.value.settings
        val s=change(current)
        if(s==current)return
        mutable.update { it.copy(settings=s) };queueWrite("settings",repository.json.encodeToString(s)) { repository.save(s) }
    }
    fun updateIdentity(identity:Identity) {
        val r=state.value.day ?: return
        if(r.independent) {
            day { it.copy(identity=identity.copy(stockOwnerId="")) }
            return
        }
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
        if(!selected.usesLedger) { onReady();return }
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
                mutable.update { it.copy(settings=settings,day=if(it.day?.id==r.id)result.reports.first { d->d.id==r.id } else it.day) }
                withContext(Dispatchers.Main) { onReady() }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun manualOpening() { day { it.copy(openingMode=OpeningMode.MANUAL,stockSourceId=null,sourceRevision=null,stockStale=false,stockIssue="",openingNote="Stok awal dicatat langsung oleh sales") } }
    fun automaticOpening() {
        val r=state.value.day ?: return
        if(StockLedger.sourceFor(r,state.value.days)==null) { error("Belum ada stok sebelumnya. Stok Awal yang sudah diisi tetap disimpan.");return }
        day { it.copy(openingMode=OpeningMode.AUTO,stockStale=false) }
    }
    fun setOpening(product:Int,value:Long?,tester:Boolean=false) {
        day { r->r.copy(openingMode=OpeningMode.MANUAL,stockSourceId=null,sourceRevision=null,stockStale=false,stockIssue="",openingNote="Stok awal dicatat langsung oleh sales",
            products=if(tester)r.products else r.products.replace(product,r.products[product].copy(opening=value)),
            testers=if(tester)r.testers.replace(product,r.testers[product].copy(opening=value)) else r.testers) }
    }
    fun rememberProfile(identity:Identity) { settings { it.copy(profile=identity) } }
    fun completeCall(onReady:()->Unit) {
        if(state.value.busy)return
        val r=state.value.call ?: return
        mutable.update { it.copy(busy=true) }
        enqueue(dataWrite=true) {
            try {
                val final=repository.completeCall(r.id)
                mutable.update { if(it.call?.id==r.id) it.copy(call=final) else it }
                withContext(Dispatchers.Main) { onReady() }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }

    fun adoptLegacy() { day { StockLedger.adoptLegacy(it) } }
    fun finalizeDay(onReady:()->Unit) {
        if(state.value.busy)return
        val r=state.value.day ?: return
        mutable.update { it.copy(busy=true) }
        enqueue(dataWrite=true) {
            try {
                val result=repository.finalizeDay(r.id)
                mutable.update { it.copy(day=result.reports.first { d->d.id==r.id }) }
                withContext(Dispatchers.Main) { onReady() }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun reopen(onReady:()->Unit) {
        if(state.value.busy)return
        val r=state.value.day ?: return
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val result=repository.reopen(r.id)
                mutable.update { it.copy(day=result.reports.first { d->d.id==r.id },pdf=null,xlsx=null,
                    notice=if(result.affected.isNotEmpty()) "Laporan terkait akan diperiksa ulang karena sumber stok dibuka kembali." else it.notice) }
                withContext(Dispatchers.Main) { onReady() }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }

    fun saveRules(rules:Rules) {
        settings { it.copy(rules=rules) }
        if(state.value.day?.let { !it.independent && it.status==ReportStatus.DRAFT }==true)day { it.copy(rules=rules) }
    }
    fun saveOutlet(o:Outlet) {
        if(o.name.isBlank())return
        settings { s -> s.copy(outlets=(s.outlets.filterNot { it.name.equals(o.name,true) }+SavedOutlet(o.name.uppercase(Locale.ROOT),o.address.uppercase(Locale.ROOT))).sortedBy { it.name }) }
    }
    fun removeCall(r:CallReport,draftOnly:Boolean=false) = removeReport("call",r.id,draftOnly)
    fun removeDay(r:DailyReport,draftOnly:Boolean=false) = removeReport("day",r.id,draftOnly,r.usesLedger)

    private fun removeReport(type:String,id:String,draftOnly:Boolean,usesLedger:Boolean=false) {
        if(state.value.busy)return
        val key="$type:$id"
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                // The same queue drains all previously scheduled writes before recording deletion.
                check(journal.edit().putBoolean("delete:$key",draftOnly).commit()) { "Penghapusan belum dapat dicatat." }
                finishDelete(key,draftOnly)
                withContext(Dispatchers.Main) {
                    val current=state.value
                    val clearCall=type=="call" && current.call?.id==id
                    val clearDay=type=="day" && current.day?.id==id
                    if(clearCall || clearDay) {
                        navigation.remove<String>(if(clearCall)"callId" else "dayId")
                        navigation["resume-$type"]=false
                        if(current.screen==type)backToRoot()
                    }
                    if(current.lastId==id)prefs.edit().remove("id").remove("type").apply()
                    mutable.update {
                        it.copy(call=if(clearCall)null else it.call,day=if(clearDay)null else it.day,
                            calls=if(type=="call")it.calls.filterNot { row->row.id==id } else it.calls,
                            days=if(type=="day")it.days.filterNot { row->row.id==id } else it.days,
                            lastId=it.lastId.takeUnless { value->value==id },
                            lastType=it.lastType.takeUnless { _->it.lastId==id },
                            error=if(draftOnly)"Draft berhasil dihapus" else "Laporan berhasil dihapus",
                            notice=if(usesLedger)"Laporan yang memakai stoknya telah diperiksa ulang." else it.notice)
                    }
                }
            } finally {
                mutable.update { it.copy(busy=false) }
            }
        }
    }
    fun attach(uri:Uri) {
        if(state.value.busy)return
        val id=state.value.day?.id ?: return
        enqueue {
            val a=repository.importAttachment(uri)
            val r=repository.daily(id) ?: kotlin.error("Laporan tidak ditemukan.")
            require(r.status!=ReportStatus.FINAL) { "Buka kembali laporan untuk menambah lampiran." }
            val result=repository.saveDay(r.copy(attachments=r.attachments+a))
            mutable.update { if(it.day?.id==id)it.copy(day=result.reports.first { d->d.id==id }) else it }
        }
    }
    fun export(format:ExportFormat=ExportFormat.PDF,onReady:(List<File>)->Unit={}) {
        exportReport(format,false,onReady)
    }

    fun previewReport(asJpeg:Boolean=false) {
        exportReport(if(asJpeg)ExportFormat.JPG else ExportFormat.PDF,true) {}
    }

    private fun exportReport(format:ExportFormat,preview:Boolean,onReady:(List<File>)->Unit) {
        val current=state.value
        if(current.busy)return
        val type=current.screen
        val id=when(type) { "call"->current.call?.id;"day"->current.day?.id;else->null } ?: return
        mutable.update { it.copy(busy=true) }
        enqueue {
            try {
                val call=if(type=="call")repository.call(id) else null
                val day=if(type=="day")repository.daily(id) else null
                val errors=if(call!=null)Calculation.errors(call) else Calculation.errors(requireNotNull(day))
                require(errors.isEmpty()) { errors.joinToString("\n") }
                if(day?.usesLedger==true && !preview) require(day.status==ReportStatus.FINAL) { "Selesaikan pemeriksaan laporan sebelum ekspor final." }
                val excel=format==ExportFormat.EXCEL
                val sheets=if(call!=null)Layout.calls(call) else if(excel)Layout.dailyWorkbook(day!!) else listOf(Layout.daily(day!!))
                val identity=call?.identity ?: day!!.identity
                val base="${if(call!=null) "CALLSHEET" else "LHS"}_${identity.date}_${id.take(8)}"
                val exports=File(getApplication<Application>().cacheDir,"exports").apply { mkdirs() }
                val extension=if(excel)"xlsx" else "pdf"
                val file=File(exports,"$base.$extension")
                val temp=File.createTempFile("report-",".tmp",exports)
                val files=try {
                    temp.outputStream().use { if(excel)ExcelExport.write(sheets,it) else PdfExport.write(sheets,it) }
                    if(format==ExportFormat.JPG && !preview) {
                        val folder=java.nio.file.Files.createTempDirectory(exports.toPath(),"jpg-").toFile()
                        JpegExport.write(temp,folder,base)
                    } else {
                        check(temp.renameTo(file)) { "File gagal disimpan." }
                        listOf(file)
                    }
                } finally {
                    temp.delete()
                }
                withContext(Dispatchers.Main) {
                    val latest=state.value
                    val latestId=if(type=="call")latest.call?.id else latest.day?.id
                    if(latest.screen==type && latestId==id) {
                        mutable.update {
                            when {
                                preview -> it.copy(preview=file,previewTitle="Pratinjau ${format.label}")
                                excel -> it.copy(xlsx=file)
                                format==ExportFormat.PDF -> it.copy(pdf=file)
                                else -> it
                            }
                        }
                        onReady(files)
                    }
                }
            } finally {
                mutable.update { it.copy(busy=false) }
            }
        }
    }
    fun preview(file:File?) { mutable.update { it.copy(preview=file) } }
    fun backup(onReady:(File)->Unit) { enqueue { val file=File(getApplication<Application>().cacheDir,"exports/LHS_BACKUP_${LocalDate.now()}.zip");file.parentFile!!.mkdirs();repository.backup(file);withContext(Dispatchers.Main) { onReady(file) } } }
    fun restore(uri:Uri) { enqueue { repository.restore(uri);val s=repository.settings();mutable.update { it.copy(settings=s,call=null,day=null,notice="Backup dipulihkan. Laporan lama dan lampirannya tetap tersedia.") };prefs.edit().clear().apply();withContext(Dispatchers.Main) { navigate("history") } } }
    fun cleanup() { enqueue { val n=repository.cleanup();mutable.update { it.copy(notice="$n lampiran yang tidak terpakai dihapus") } } }
}
