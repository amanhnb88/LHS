package id.lhspapua.domain

/** Deterministic stock ordering and propagation. FINAL payload quantities are never changed implicitly. */
object StockLedger {
    private fun before(a:DailyReport,b:DailyReport) = a.identity.date < b.identity.date || (a.identity.date==b.identity.date && a.stockSequence<b.stockSequence)
    private fun owner(a:DailyReport,b:DailyReport) = a.identity.stockOwnerId.isNotBlank() && a.identity.stockOwnerId==b.identity.stockOwnerId
    private val order = compareBy<DailyReport>({it.identity.date},{it.stockSequence},{it.id})
    fun sourceFor(report:DailyReport,all:List<DailyReport>):DailyReport? {
        if(!report.usesLedger) return null
        val previous=all.filter { !it.independent && it.id!=report.id && owner(it,report) && before(it,report) }
        val valid=previous.filter { eligible(it) }.maxWithOrNull(order)
        // Never jump past an unresolved finalized transaction or a reopened source.
        if(previous.any { ((it.status==ReportStatus.FINAL && it.stockStale) || (it.wasFinalized && it.status!=ReportStatus.FINAL)) && (valid==null || before(valid,it)) }) return null
        val linked=report.stockSourceId?.let { id -> all.find { it.id==id } }
        if(linked!=null && (linked.status!=ReportStatus.FINAL || linked.stockStale) && (valid==null || before(valid,linked))) return null
        return valid
    }
    fun eligible(r:DailyReport) = r.usesLedger && r.status==ReportStatus.FINAL && !r.stockStale && r.identity.stockOwnerId.isNotBlank() && Calculation.errors(r).isEmpty()
    fun sequence(identity:Identity,all:List<DailyReport>) = (all.filter { !it.independent && it.identity.stockOwnerId==identity.stockOwnerId && it.identity.date==identity.date }.maxOfOrNull { it.stockSequence } ?: 0)+1
    private fun withSource(r:DailyReport,source:DailyReport?) = r.copy(
        products=r.products.mapIndexed { i,s -> s.copy(opening=source?.let { Calculation.tg(it.products[i],it.rules) }) },
        testers=r.testers.mapIndexed { i,t -> t.copy(opening=source?.let { Calculation.tg(it.testers[i],it.rules) }) },
        stockSourceId=source?.id,sourceRevision=source?.revision,stockStale=false,
        stockIssue=if(source==null) "Belum ada sumber stok final yang valid. Masukkan Stok Awal, atau periksa laporan sebelumnya." else "")
    fun stockChanged(a:DailyReport,b:DailyReport):Boolean = a.identity.stockOwnerId!=b.identity.stockOwnerId || a.identity.date!=b.identity.date || a.stockSequence!=b.stockSequence || a.openingMode!=b.openingMode || a.status!=b.status ||
        a.products.map { listOf(it.opening,it.delivery,it.sample,it.bonus)+it.sales.map { v->v.quantity } } != b.products.map { listOf(it.opening,it.delivery,it.sample,it.bonus)+it.sales.map { v->v.quantity } } || a.testers!=b.testers
    data class Result(val reports:List<DailyReport>,val affected:List<String>)
    fun reconcile(all:List<DailyReport>,changed:DailyReport):Result {
        Calculation.validate(changed)
        if(changed.independent) return Result(all.filterNot { it.id==changed.id }+changed,emptyList())
        val prior=all.find { it.id==changed.id }
        val edit=changed.copy(revision=if(prior!=null && stockChanged(prior,changed)) prior.revision+1 else prior?.revision ?: changed.revision)
        val map=(all.filterNot { it.id==changed.id }+edit).associateBy { it.id }.toMutableMap()
        val affected=mutableListOf<String>()
        val duplicate=map.values.filter { it.usesLedger && it.identity.stockOwnerId.isNotBlank() }.groupBy { Triple(it.identity.stockOwnerId,it.identity.date,it.stockSequence) }.values.filter { it.size>1 }.flatten().map { it.id }.toSet()
        for(original in map.values.sortedWith(order)) {
            var r=map.getValue(original.id)
            if(!r.usesLedger) continue
            if(r.id in duplicate) {
                r=r.copy(stockStale=true,stockIssue="Urutan stok pada tanggal ini dipakai laporan lain. Ubah urutan di Informasi Harian.")
            } else if(r.openingMode==OpeningMode.AUTO && r.identity.stockOwnerId.isNotBlank()) {
                val expected=withSource(r,sourceFor(r,map.values.toList()))
                if(r.status==ReportStatus.FINAL) {
                    if(r.stockSourceId!=expected.stockSourceId || r.sourceRevision!=expected.sourceRevision || r.products.map { it.opening }!=expected.products.map { it.opening } || r.testers.map { it.opening }!=expected.testers.map { it.opening })
                        r=r.copy(stockStale=true,stockIssue="Laporan sebelumnya berubah. Nilai laporan final ini dipertahankan; buka kembali dan periksa sebelum memfinalisasi ulang.")
                } else r=expected.copy(revision=if(r.products.map { it.opening }!=expected.products.map { it.opening } || r.testers.map { it.opening }!=expected.testers.map { it.opening }) r.revision+1 else r.revision)
            } else if(r.openingMode==OpeningMode.MANUAL && r.status==ReportStatus.DRAFT) {
                r=r.copy(stockSourceId=null,sourceRevision=null,stockStale=false,stockIssue="")
            }
            if(r!=map[original.id] && r.id!=changed.id) affected.add(r.id)
            map[r.id]=r
        }
        return Result(map.values.toList(),affected)
    }
    fun adoptLegacy(r:DailyReport) = r.copy(formatVersion=2,status=ReportStatus.DRAFT,openingMode=OpeningMode.MANUAL,openingNote="Stok awal laporan lama dilengkapi oleh pengguna",stockSourceId=null,sourceRevision=null,stockStale=false,
        products=r.products.map { it.copy(openingTracked=true,opening=null,delivery=it.delivery ?: 0,sample=it.sample ?: 0,bonus=it.bonus ?: 0,sales=it.sales.map { v->v.copy(quantity=v.quantity ?: 0) }) },
        testers=r.testers.map { it.copy(openingTracked=true,opening=null,delivery=it.delivery ?: 0,sample=it.sample ?: 0) })
}
