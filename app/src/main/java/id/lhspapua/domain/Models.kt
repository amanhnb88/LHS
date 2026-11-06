package id.lhspapua.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Serializable enum class Product(val label: String) { MARTIL_12("MARTIL 12"), MARTIL("MARTIL"), HD_LIGHT("HD LIGHT") }
@Serializable enum class Tier(val label: String) { G("G"), SG("SG"), RTL("RTL") }
@Serializable data class Identity(
    val date: String = LocalDate.now().toString(), val sales: String = "", val join: String = "",
    val area: String = "", val route: String = "", val vehicle: String = "", val plate: String = "", val stockOwnerId: String = ""
) {
    fun day(): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("id-ID")))
    fun displayDate(): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}
@Serializable data class Quantity(val stock: Long? = null, val sold: Long? = null)
@Serializable data class Outlet(val id: String = UUID.randomUUID().toString(), val name: String = "", val address: String = "", val quantities: List<Quantity> = List(3) { Quantity() })
fun Outlet.untouched() = name.isBlank() && address.isBlank() && quantities.all { it.stock==null && it.sold==null }
fun CallReport.activeOutlets() = outlets.filterNot { it.untouched() }
@Serializable data class CallReport(val id: String = UUID.randomUUID().toString(), val identity: Identity = Identity(), val outlets: List<Outlet> = emptyList(), val completed: Boolean = false)
@Serializable data class Sale(val quantity: Long? = null, val price: Long? = null, val applicable: Boolean = true)
@Serializable data class Stock(val delivery: Long? = null, val sample: Long? = null, val bonus: Long? = null, val manualTg: Long? = null, val sales: List<Sale> = List(3) { Sale() }, val opening: Long? = null, val openingTracked: Boolean = false)
@Serializable data class Tester(val delivery: Long? = null, val sample: Long? = null, val manualTg: Long? = null, val opening: Long? = null, val openingTracked: Boolean = false)
@Serializable data class Finance(val previous: Long? = null, val debt: Long? = null, val settlement: Long? = null, val transfer: Long? = null, val expense: Long? = null, val notes: String = "", val madeBy: String = "", val manualBalance: Long? = null)
@Serializable data class Rules(
    val automaticTg: Boolean = true, val subtractSample: Boolean = true, val subtractBonus: Boolean = true,
    val debtSign: Int = -1, val settlementSign: Int = 1, val transferSign: Int = -1, val expenseSign: Int = -1,
    val acknowledged: Boolean = false, val automaticFinance: Boolean = true
) {
    fun tgText() = if (automaticTg) "TG = DO - Jual${if (subtractSample) " - Sample" else ""}${if (subtractBonus) " - Bns" else ""}. TG Tester = DO - Sampel." else "TG stock dan tester diisi manual."
    fun financeText() = "Saldo Akhir = Saldo Sebelum + Total Penjualan ${sign(debtSign)} Piutang ${sign(settlementSign)} Pelunasan ${sign(transferSign)} Transfer ${sign(expenseSign)} Pengeluaran"
    private fun sign(v: Int) = if (v == 1) "+" else "-"
}
@Serializable data class Attachment(val file: String, val label: String, val mime: String)
@Serializable data class DailyReport(val id: String = UUID.randomUUID().toString(), val identity: Identity = Identity(), val products: List<Stock> = List(3) { Stock() }, val testers: List<Tester> = List(3) { Tester() }, val finance: Finance = Finance(), val rules: Rules = Rules(), val attachments: List<Attachment> = emptyList(),
    val formatVersion: Int = 1, val status: ReportStatus = ReportStatus.DRAFT,
    val stockSequence: Int = 1, val openingMode: OpeningMode = OpeningMode.AUTO,
    val stockSourceId: String? = null, val sourceRevision: Long? = null,
    val revision: Long = 0, val stockStale: Boolean = false, val stockIssue: String = "",
    val openingNote: String = "", val finalizedAt: String? = null, val wasFinalized: Boolean = false)
@Serializable enum class ReportStatus { DRAFT, FINAL }
@Serializable enum class OpeningMode { AUTO, MANUAL }
@Serializable data class StockOwner(val id: String = UUID.randomUUID().toString(), val name: String)
@Serializable data class SavedOutlet(val name: String, val address: String)
@Serializable data class Settings(val profile: Identity = Identity(), val prices: List<List<Long?>> = OfficialPrices.values, val rules: Rules = Rules(), val outlets: List<SavedOutlet> = emptyList(), val owners: List<StockOwner> = emptyList())
@Serializable data class Backup(val version: Int = 2, val calls: List<CallReport>, val daily: List<DailyReport>, val settings: Settings)


/** Admin tariff for new reports. Existing reports retain their own sales snapshots. */
object OfficialPrices {
    val values: List<List<Long?>> = Product.entries.map { product ->
        when(product) {
            Product.MARTIL_12 -> listOf(14000L, null, 14500L)
            Product.MARTIL -> listOf(15000L, null, 15500L)
            Product.HD_LIGHT -> listOf(17500L, null, 18000L)
        }
    }

    fun sales(product: Product): List<Sale> = Tier.entries.map { tier ->
        if(tier == Tier.SG) Sale(applicable=false)
        else Sale(quantity=0, price=values[product.ordinal][tier.ordinal])
    }
}

/** Unknown opening stock stays empty until the ledger or the sales user supplies it. */
fun newDaily(identity: Identity, settings: Settings, sequence: Int = 1) = DailyReport(
    identity=identity, formatVersion=2, stockSequence=sequence,
    products=Product.entries.map { product -> Stock(delivery=0,sample=0,bonus=0,openingTracked=true,sales=OfficialPrices.sales(product)) },
    testers=List(3) { Tester(delivery=0,sample=0,openingTracked=true) },finance=Finance(debt=0,settlement=0,transfer=0,expense=0,madeBy=identity.sales),rules=settings.rules.copy(automaticTg=true,subtractSample=true,subtractBonus=true))
