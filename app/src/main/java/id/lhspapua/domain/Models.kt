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
@Serializable data class CallReport(val id: String = UUID.randomUUID().toString(), val identity: Identity = Identity(), val outlets: List<Outlet> = emptyList(), val completed: Boolean = false,
    val createdAt: String? = null, val updatedAt: String? = null, val completedAt: String? = null)
@Serializable data class Sale(val quantity: Long? = null, val price: Long? = null, val applicable: Boolean = true)
@Serializable data class Stock(val delivery: Long? = null, val sample: Long? = null, val bonus: Long? = null, val manualTg: Long? = null, val sales: List<Sale> = List(3) { Sale() }, val opening: Long? = null, val openingTracked: Boolean = false, val manualSold: Long? = null)
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
    val openingNote: String = "", val finalizedAt: String? = null, val wasFinalized: Boolean = false,
    val createdAt: String? = null, val updatedAt: String? = null,
    val automaticMadeBy: Boolean = false, val callsheetLink: CallsheetLink? = null) {
    val usesLedger: Boolean get() = formatVersion == 2
    val independent: Boolean get() = formatVersion in 3..4
    val automaticSold: Boolean get() = formatVersion == 4
    val canEnableAutomaticSold: Boolean get() = formatVersion == 3 && status == ReportStatus.DRAFT
}
@Serializable enum class ReportStatus { DRAFT, FINAL }
@Serializable enum class OpeningMode { AUTO, MANUAL }
@Serializable data class StockOwner(val id: String = UUID.randomUUID().toString(), val name: String)
@Serializable data class SavedOutlet(val name: String, val address: String)
@Serializable data class LhsFeatureSettings(
    val stockEnabled: Boolean = false,
    val testerEnabled: Boolean = false,
    val financeEnabled: Boolean = false
)
@Serializable enum class IntegrationMode { MANDIRI, TERINTEGRASI }
@Serializable data class CallsheetLink(val sourceId: String, val fingerprint: String, val confirmedAt: String)
@Serializable data class Settings(val profile: Identity = Identity(), val prices: List<List<Long?>> = OfficialPrices.values, val rules: Rules = Rules(), val outlets: List<SavedOutlet> = emptyList(), val owners: List<StockOwner> = emptyList(),
    val lhsFeatures: LhsFeatureSettings = LhsFeatureSettings(), val integrationMode: IntegrationMode = IntegrationMode.MANDIRI)
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
        else Sale(price=values[product.ordinal][tier.ordinal])
    }
}

fun Identity.uppercaseFields() = copy(
    sales=sales.uppercase(Locale.ROOT),join=join.uppercase(Locale.ROOT),area=area.uppercase(Locale.ROOT),
    route=route.uppercase(Locale.ROOT),vehicle=vehicle.uppercase(Locale.ROOT),plate=plate.uppercase(Locale.ROOT)
)

/** New reports derive stock sold from the applicable sales quantities. */
fun newDaily(identity: Identity) = DailyReport(
    identity = identity.uppercaseFields().copy(stockOwnerId = ""),
    formatVersion = 4,
    products = Product.entries.map { product -> Stock(sales = OfficialPrices.sales(product)) },
    finance = Finance(madeBy = identity.sales.uppercase(Locale.ROOT)),
    rules = Rules(automaticTg = false, automaticFinance = false)
)
