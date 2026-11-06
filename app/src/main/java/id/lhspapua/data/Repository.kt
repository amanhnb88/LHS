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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Repository(private val context: Context, private val db: LhsDatabase = LhsDatabase.get(context)) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val dao = db.dao()
    val calls = dao.calls().map { rows -> rows.map { json.decodeFromString<CallReport>(it.payload) } }
    val daily = dao.daily().map { rows -> rows.map { json.decodeFromString<DailyReport>(it.payload) } }
    suspend fun settings() = dao.settings()?.let { json.decodeFromString<Settings>(it.payload) } ?: Settings()
    suspend fun save(s: Settings) { Calculation.validate(s); dao.save(SettingsEntity(payload=json.encodeToString(s))) }
    suspend fun save(r: CallReport) { Calculation.validate(r); dao.save(CallEntity(r.id,r.identity.date,r.identity.sales,json.encodeToString(r))) }
    suspend fun save(r: DailyReport) { saveDay(r) }
    private suspend fun writeDay(r:DailyReport) { Calculation.validate(r); dao.save(DailyEntity(r.id,r.identity.date,r.identity.sales,json.encodeToString(r))) }
    suspend fun allDaily() = dao.allDaily().map { json.decodeFromString<DailyReport>(it.payload) }
    suspend fun saveDay(r:DailyReport):StockLedger.Result = db.withTransaction {
        if(r.independent) {
            writeDay(r)
            return@withTransaction StockLedger.Result(listOf(r),emptyList())
        }
        val before=allDaily()
        val result=StockLedger.reconcile(before,r)
        val existing=before.associateBy { it.id }
        result.reports.filter { existing[it.id]!=it }.forEach { writeDay(it) }; result
    }
    suspend fun finalizeDay(id:String):StockLedger.Result = db.withTransaction {
        val existing=daily(id) ?: error("Laporan tidak ditemukan.")
        val prepared=saveDay(existing).reports.first { it.id==id }
        val errors=Calculation.errors(prepared)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        saveDay(prepared.copy(status=ReportStatus.FINAL,wasFinalized=true,finalizedAt=java.time.Instant.now().toString()))
    }
    suspend fun reopen(id:String):StockLedger.Result = db.withTransaction {
        val r=daily(id) ?: error("Laporan tidak ditemukan.")
        saveDay(r.copy(status=ReportStatus.DRAFT,finalizedAt=null))
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
                db.withTransaction { data.calls.forEach { save(it) }; restored.forEach { writeDay(it) }; save(data.settings)
                    val all=allDaily()
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
