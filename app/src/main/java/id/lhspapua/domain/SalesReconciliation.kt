package id.lhspapua.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

object SalesReconciliation {
    enum class SourceStatus(val label: String, val problem: String?) {
        READY("Sumber terkonfirmasi", null),
        MISSING("SUMBER TIDAK TERSEDIA", "Callsheet sumber tidak tersedia."),
        DRAFT("SUMBER BELUM FINAL", "Selesaikan Callsheet sumber dan konfirmasi ulang."),
        IDENTITY("IDENTITAS BERBEDA", "Nama Sales, tanggal, Area atau Rute tidak sesuai."),
        CONFLICT("TAUTAN GANDA", "Callsheet sudah ditautkan ke LHS lain."),
        CHANGED("PERLU KONFIRMASI ULANG", "Callsheet sumber berubah. Periksa dan konfirmasi ulang.")
    }

    enum class Match(val label: String) {
        MATCH("COCOK"), DIFFERENT("BELUM COCOK"), INCOMPLETE("BELUM LENGKAP"), INVALID("ANGKA TIDAK VALID")
    }

    data class ProductMatch(
        val product: Product, val callsheet: Long?, val gross: Long?, val retail: Long?,
        val total: Long?, val difference: Long?, val status: Match
    )

    data class Review(val source: SourceStatus, val products: List<ProductMatch>) {
        val problems: List<String> get() = source.problem?.let { listOf(it) }
            ?: products.filter { it.status!=Match.MATCH }.map { "${it.product.label}: ${it.status.label}." }
    }

    private fun key(value: String) = value.trim().uppercase(Locale.ROOT)
    private fun compatible(a: String, b: String) = a.isBlank() || b.isBlank() || key(a)==key(b)

    fun matchesIdentity(a: Identity, b: Identity): Boolean =
        a.sales.isNotBlank() && key(a.sales)==key(b.sales) && a.date==b.date &&
            compatible(a.area,b.area) && compatible(a.route,b.route)

    fun claimed(sourceId: String, reportId: String, days: List<DailyReport>): Boolean =
        days.any { it.id!=reportId && it.callsheetLink?.sourceId==sourceId }

    fun candidates(report: DailyReport, calls: List<CallReport>, days: List<DailyReport>): List<CallReport> =
        calls.filter { it.completed && matchesIdentity(report.identity,it.identity) && !claimed(it.id,report.id,days) }
            .sortedWith(compareByDescending<CallReport> {
                listOf(it.identity.area to report.identity.area,it.identity.route to report.identity.route)
                    .count { (left,right) -> left.isNotBlank() && right.isNotBlank() }
            }.thenByDescending { ReportTime.parse(it.completedAt) }.thenBy { it.id })

    fun fingerprint(source: CallReport): String {
        val payload=Json.encodeToString(source.copy(createdAt=null,updatedAt=null))
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT,it.toInt() and 255) }
    }

    fun review(report: DailyReport, source: CallReport?, conflict: Boolean): Review? {
        val link=report.callsheetLink ?: return null
        val status=when {
            source==null || source.id!=link.sourceId -> SourceStatus.MISSING
            !source.completed -> SourceStatus.DRAFT
            !matchesIdentity(report.identity,source.identity) -> SourceStatus.IDENTITY
            conflict -> SourceStatus.CONFLICT
            fingerprint(source)!=link.fingerprint -> SourceStatus.CHANGED
            else -> SourceStatus.READY
        }
        return Review(status,if(source!=null && source.id==link.sourceId) Product.entries.map { compare(source,report,it) } else emptyList())
    }

    private fun compare(source: CallReport, report: DailyReport, product: Product): ProductMatch {
        val sales=report.products[product.ordinal].sales
        val gross=sales[Tier.G.ordinal].quantity
        val retail=sales[Tier.RTL.ordinal].quantity
        val callsheet=runCatching { Calculation.calls(source,product.ordinal,false) }
        val total=runCatching {
            require(Tier.entries.all { report.salesMode.allows(it) || (sales[it.ordinal].quantity ?: 0L)==0L }) { "Kategori terkunci masih berisi angka." }
            val active=listOf(Tier.G,Tier.RTL).filter { report.salesMode.allows(it) }.map { sales[it.ordinal].quantity }
            if(active.any { it==null }) null else {
                require(active.all(Calculation::validInput))
                Calculation.sum(active)
            }
        }
        val left=total.getOrNull()
        val right=callsheet.getOrNull()
        val delta=if(left==null || right==null) null else Math.subtractExact(left,right)
        val status=when {
            callsheet.isFailure || total.isFailure -> Match.INVALID
            delta==null -> Match.INCOMPLETE
            delta==0L -> Match.MATCH
            else -> Match.DIFFERENT
        }
        return ProductMatch(product,right,gross,retail,left,delta,status)
    }
}
