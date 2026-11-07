package id.lhspapua.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import id.lhspapua.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Repository(private val context: Context, private val db: LhsDatabase = LhsDatabase.get(context), private val clock: Clock = Clock.systemUTC()) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val dao = db.dao()
    val calls = dao.calls().map { rows -> rows.map { json.decodeFromString<CallReport>(it.payload) } }
    val daily = dao.daily().map { rows -> rows.map { json.decodeFromString<DailyReport>(it.payload) } }
    suspend fun settings() = dao.settings()?.let { json.decodeFromString<Settings>(it.payload) } ?: Settings()
    suspend fun save(s: Settings) { Calculation.validate(s); dao.save(SettingsEntity(payload=json.encodeToString(s))) }
    private fun now() = clock.instant().toString()
    private suspend fun writeCall(r: CallReport) {
        Calculation.validate(r)
        dao.save(CallEntity(r.id,r.identity.date,r.identity.sales,json.encodeToString(r)))
    }

    suspend fun save(r: CallReport) = db.withTransaction {
        val old=call(r.id)
        val saved=if(old==null) r.copy(updatedAt=r.updatedAt ?: r.createdAt?.let { now() }) else r.copy(
            createdAt=old.createdAt,
            updatedAt=if(r.identity!=old.identity || r.outlets!=old.outlets) now() else old.updatedAt,
            completedAt=when {
                !r.completed -> null
                !old.completed -> now()
                else -> old.completedAt
            }
        )
        if(saved!=old)writeCall(saved)
        saved
    }

    suspend fun completeCall(id:String) = db.withTransaction {
        val existing=call(id) ?: error("Laporan tidak ditemukan.")
        require(Calculation.errors(existing).isEmpty()) { "Periksa jumlah outlet sebelum menyelesaikan laporan." }
        save(existing.copy(completed=true))
    }

    suspend fun save(r: DailyReport) { saveDay(r) }
    private suspend fun writeDay(r:DailyReport) { Calculation.validate(r); dao.save(DailyEntity(r.id,r.identity.date,r.identity.sales,json.encodeToString(r))) }
    suspend fun allDaily() = dao.allDaily().map { json.decodeFromString<DailyReport>(it.payload) }
    suspend fun saveDay(report:DailyReport):StockLedger.Result = db.withTransaction {
        val old=daily(report.id)
        require(old?.status!=ReportStatus.FINAL) { "Buka kembali laporan sebelum menyimpan perubahan." }
        require(old==null || report.status==ReportStatus.DRAFT) { "Gunakan Selesaikan Laporan untuk finalisasi." }
        if(old!=null) Calculation.validateSalesEdit(old,report)
        else require(Calculation.modeConflicts(report).isEmpty()) { Calculation.modeConflicts(report).joinToString("\n") }
        val edit=report.copy(
            callsheetLink=old?.callsheetLink,
            automaticMadeBy=old?.automaticMadeBy ?: report.automaticMadeBy
        )
        persistDay(if(edit.automaticMadeBy)edit.copy(finance=edit.finance.copy(madeBy=edit.identity.uppercaseFields().sales)) else edit,old)
    }

    /** Explicit mode transition, serialized with report writes and committed with its default. */
    suspend fun changeIntegrationMode(mode:IntegrationMode, reportId:String?=null, expected:DailyReport?=null):DailyReport? = db.withTransaction {
        val updated=reportId?.let { id ->
            val report=daily(id) ?: error("Draft tidak ditemukan.")
            require(report.status==ReportStatus.DRAFT && report.automaticSold) { "Mode hanya dapat diubah pada draft Jual Otomatis." }
            require(expected?.id==id && expected.reportMode==report.reportMode && expected.products==report.products && expected.identity==report.identity) {
                "Draft berubah sejak ditampilkan. Periksa kembali sebelum mengubah mode."
            }
            val problems=Calculation.modeConflicts(report,mode)
            require(problems.isEmpty()) { problems.joinToString("\n") }
            persistDay(report.copy(reportMode=mode),report).reports.first { it.id==id }
        }
        save(settings().copy(integrationMode=mode))
        updated
    }

    private suspend fun persistDay(report:DailyReport,old:DailyReport?):StockLedger.Result {
        val r=if(old==null) report.copy(updatedAt=report.updatedAt ?: report.createdAt?.let { now() }) else {
            val sameContent=report.copy(
                createdAt=old.createdAt,updatedAt=old.updatedAt,finalizedAt=old.finalizedAt,
                wasFinalized=old.wasFinalized,status=old.status,revision=old.revision
            )==old
            report.copy(createdAt=old.createdAt,updatedAt=if(sameContent) old.updatedAt else now())
        }
        if(r.independent) {
            if(r!=old)writeDay(r)
            return StockLedger.Result(listOf(r),emptyList())
        }
        val before=allDaily()
        val result=StockLedger.reconcile(before,r)
        val existing=before.associateBy { it.id }
        result.reports.filter { existing[it.id]!=it }.forEach { writeDay(it) }; return result
    }
    suspend fun finalizeDay(id:String):StockLedger.Result = db.withTransaction {
        val existing=daily(id) ?: error("Laporan tidak ditemukan.")
        if(existing.status==ReportStatus.FINAL)return@withTransaction StockLedger.Result(listOf(existing),emptyList())
        val prepared=saveDay(existing).reports.first { it.id==id }
        val link=prepared.callsheetLink
        val matching=link?.let {
            SalesReconciliation.review(prepared,call(it.sourceId),SalesReconciliation.claimed(it.sourceId,id,allDaily()))
        }
        val errors=Calculation.errors(prepared)+matching?.problems.orEmpty()
        require(errors.isEmpty()) { errors.joinToString("\n") }
        persistDay(prepared.copy(status=ReportStatus.FINAL,wasFinalized=true,finalizedAt=now()),prepared)
    }
    suspend fun reopen(id:String):StockLedger.Result = db.withTransaction {
        val r=daily(id) ?: error("Laporan tidak ditemukan.")
        persistDay(r.copy(status=ReportStatus.DRAFT,finalizedAt=null),r)
    }
    suspend fun changeCallsheetLink(id:String,sourceId:String?,fingerprint:String?):DailyReport = db.withTransaction {
        val report=daily(id) ?: error("LHS tidak ditemukan.")
        require(report.status==ReportStatus.DRAFT) { "Buka kembali LHS sebelum mengubah tautan." }
        val link=sourceId?.let {
            require(report.callsheetLink?.sourceId==it || (report.reportMode?.integrated ?: settings().integrationMode.integrated)) {
                "Aktifkan Mode Terintegrasi di Pengaturan."
            }
            val source=call(it) ?: error("Callsheet sumber tidak tersedia.")
            require(source.completed) { "Callsheet sumber belum Final." }
            require(SalesReconciliation.matchesIdentity(report.identity,source.identity)) { "Identitas laporan tidak sesuai." }
            require(!SalesReconciliation.claimed(it,id,allDaily())) { "Callsheet sudah ditautkan ke LHS lain." }
            val current=SalesReconciliation.fingerprint(source)
            require(current==fingerprint) { "Callsheet berubah sejak ditampilkan. Periksa dan konfirmasi ulang." }
            CallsheetLink(it,current,now())
        }
        persistDay(report.copy(callsheetLink=link),report).reports.first { it.id==id }
    }

    suspend fun call(id: String) = dao.call(id)?.let { json.decodeFromString<CallReport>(it.payload) }
    suspend fun daily(id: String) = dao.day(id)?.let { json.decodeFromString<DailyReport>(it.payload) }
    suspend fun deleteCall(id:String,draftOnly:Boolean=false) = db.withTransaction {
        if(draftOnly)require(call(id)?.completed!=true) { "Callsheet final tidak dapat dihapus sebagai draft." }
        dao.deleteCall(id)
    }
    suspend fun deleteDaily(id:String,draftOnly:Boolean=false) = db.withTransaction {
        val report=daily(id) ?: return@withTransaction
        if(draftOnly)require(report.status==ReportStatus.DRAFT) { "Laporan final tidak dapat dihapus sebagai draft." }
        if(report.independent) {
            dao.deleteDaily(id)
            return@withTransaction
        }
        val all=allDaily(); dao.deleteDaily(id)
        val remaining=all.filterNot { it.id==id }
        if(remaining.isNotEmpty()) {
            // A deleted predecessor cannot silently become a different source for a final report.
            val orphaned=remaining.map { if(it.stockSourceId==id) it.copy(stockStale=true,stockIssue="Sumber stok telah dihapus. Periksa ulang Stok Awal laporan ini.") else it }
            orphaned.firstOrNull { it.usesLedger }?.let { changed ->
                StockLedger.reconcile(orphaned,changed).reports.filter { it.usesLedger }.forEach { writeDay(it) }
            }
        }
    } // orphan cleanup happens only on explicit storage cleanup
    fun attachment(a: Attachment): File {
        require(a.file.matches(Regex("[a-f0-9-]+\\.(jpg|png|webp|pdf)"))) { "Nama lampiran tidak valid." }
        return File(context.filesDir,"attachments/${a.file}")
    }
    suspend fun importAttachment(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val ext = when(mime) { "image/png"->"png"; "image/webp"->"webp"; "application/pdf"->"pdf"; else->"jpg" }
        val a = Attachment("${UUID.randomUUID()}.$ext", "Bukti ${java.time.LocalDate.now()}", mime)
        val target = attachment(a); target.parentFile!!.mkdirs()
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Foto tidak dapat dibuka." }
                target.outputStream().use { output ->
                    val buf=ByteArray(8192); var total=0L
                    while(true) { val n=input.read(buf); if(n<0) break; total+=n; require(total<=25L*1024*1024) { "Lampiran maksimal 25 MB." }; output.write(buf,0,n) }
                }
            }
            a
        } catch(e: Exception) { target.delete(); throw e }
    }
    suspend fun backup(target: File) = withContext(Dispatchers.IO) {
        val data = db.withTransaction { Backup(calls=dao.allCalls().map { json.decodeFromString(it.payload) },daily=dao.allDaily().map { json.decodeFromString(it.payload) },settings=settings()) }
        val metadata=json.encodeToString(data).toByteArray()
        val proofs=data.daily.flatMap { it.attachments }.distinctBy { it.file }
        require(proofs.all { attachment(it).isFile }) { "Salah satu lampiran tidak ditemukan." }
        require(metadata.size+proofs.sumOf { attachment(it).length() } <= 250L*1024*1024) { "Backup maksimal 250 MB. Kurangi lampiran sebelum membuat backup." }
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("reports.json")); zip.write(metadata); zip.closeEntry()
            proofs.forEach { a ->
                val source=attachment(a); require(source.isFile) { "Lampiran ${a.label} tidak ditemukan." }
                zip.putNextEntry(ZipEntry("attachments/${a.file}")); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    /** Merge by stable report IDs. Validate everything before committing; existing reports preserved unless same ID. */
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val stage=File(context.cacheDir,"restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total=0L; val names=mutableSetOf<String>()
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                ZipInputStream(input).use { zip ->
                    while(true) {
                        val entry=zip.nextEntry ?: break
                        require(names.add(entry.name)) { "Backup berisi file ganda." }
                        require(entry.name=="reports.json" || entry.name.matches(Regex("attachments/[a-f0-9-]+\\.(jpg|png|webp|pdf)"))) { "Isi backup tidak dikenal." }
                        val f=File(stage,entry.name); f.parentFile!!.mkdirs()
                        f.outputStream().use { out -> val buf=ByteArray(8192); while(true) { val n=zip.read(buf); if(n<0) break; total+=n; require(total<=250L*1024*1024) { "Backup terlalu besar (maksimal 250 MB)." }; out.write(buf,0,n) } }
                    }
                }
            }
            val data=json.decodeFromString<Backup>(File(stage,"reports.json").readText())
            require(data.version in 1..2) { "Versi backup belum didukung." }
            data.calls.forEach(Calculation::validate); data.daily.forEach(Calculation::validate)
            Calculation.validate(data.settings)
            // Attachments get fresh IDs to avoid overwriting files referenced by current reports.
            val mapped=mutableMapOf<String,Attachment>(); val newFiles=mutableListOf<File>()
            try {
                val restored=data.daily.map { r -> r.copy(attachments=r.attachments.map { a -> mapped.getOrPut(a.file) {
                    attachment(a) // validate the stored filename before resolving it in staging
                    val source=File(stage,"attachments/${a.file}"); require(source.isFile) { "Lampiran backup tidak lengkap." }
                    val replacement=a.copy(file="${UUID.randomUUID()}.${a.file.substringAfterLast('.')}")
                    val f=attachment(replacement); f.parentFile!!.mkdirs(); source.copyTo(f); newFiles.add(f); replacement
                } }) }
                db.withTransaction { data.calls.forEach { writeCall(it) }; restored.forEach { writeDay(it) }; save(data.settings)
                    val all=allDaily()
                    val linked=all.mapNotNull { it.callsheetLink?.sourceId }
                    require(linked.size==linked.toSet().size) { "Backup bertentangan dengan tautan Callsheet yang sudah tersimpan." }
                    all.firstOrNull { it.usesLedger }?.let { changed ->
                        StockLedger.reconcile(all,changed).reports.filter { it.usesLedger }.forEach { writeDay(it) }
                    }
                }
            } catch(e: Exception) { newFiles.forEach { it.delete() }; throw e }
        } finally { stage.deleteRecursively() }
    }
    suspend fun cleanup(): Int = withContext(Dispatchers.IO) {
        val active=dao.allDaily().flatMap { json.decodeFromString<DailyReport>(it.payload).attachments }.map { it.file }.toSet()
        File(context.filesDir,"attachments").listFiles()?.count { it.name !in active && it.delete() } ?: 0
    }
}
