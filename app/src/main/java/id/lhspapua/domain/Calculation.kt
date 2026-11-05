package id.lhspapua.domain

import java.text.NumberFormat
import java.util.Locale

/** Integers only. Limit below Excel's 15 significant digit boundary. Never silently overflow. */
object Calculation {
    const val MAX = 999_999_999_999_999L
    fun sum(values: Iterable<Long?>): Long? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        return present.fold(0L) { a, b -> bounded(Math.addExact(a, b)) }
    }
    fun bounded(n: Long): Long { require(n in -MAX..MAX) { "Angka hasil terlalu besar. Kurangi nilai sebelum menyimpan atau ekspor." }; return n }
    fun subtotal(s: Sale): Long? = when {
        s.quantity == null -> null
        s.quantity == 0L -> 0L
        s.price == null -> null
        else -> bounded(Math.multiplyExact(s.quantity, s.price))
    }
    fun sold(s: Stock) = sum(s.sales.map { it.quantity })
    fun productTotal(s: Stock): Long? = if (s.sales.any { (it.quantity ?: 0) > 0 && it.price == null }) null else sum(s.sales.map(::subtotal))
    fun total(r: DailyReport): Long? = if (r.products.any { it.sales.any { s -> (s.quantity ?: 0) > 0 && s.price == null } }) null else sum(r.products.map(::productTotal))
    fun tg(s: Stock, r: Rules): Long? = if (!r.automaticTg) s.manualTg else s.delivery?.let { bounded(it - (sold(s) ?: 0) - (if(r.subtractSample) s.sample ?: 0 else 0) - (if(r.subtractBonus) s.bonus ?: 0 else 0)) }
    fun tg(t: Tester, r: Rules): Long? = if (!r.automaticTg) t.manualTg else t.delivery?.let { bounded(it - (t.sample ?: 0)) }
    fun balance(r: DailyReport): Long? {
        if (r.products.any { it.sales.any { s -> (s.quantity ?: 0) > 0 && s.price == null } }) return null
        val f = r.finance
        val numbers = listOf(f.previous, total(r), f.debt?.times(r.rules.debtSign), f.settlement?.times(r.rules.settlementSign), f.transfer?.times(r.rules.transferSign), f.expense?.times(r.rules.expenseSign))
        return sum(numbers)
    }
    fun calls(r: CallReport, product: Int, stock: Boolean) = sum(r.outlets.map { if(stock) it.quantities[product].stock else it.quantities[product].sold })
    fun errors(r: CallReport): List<String> = buildList {
        r.activeOutlets().forEachIndexed { i, o ->
            if(o.name.isBlank()) add("Outlet ${i+1}: nama belum diisi.")
            o.quantities.forEachIndexed { p,q ->
                if(q.stock != null && q.sold != null && q.sold > q.stock) add("Outlet ${i+1}, ${Product.entries[p].label}: Jual melebihi Stok.")
                if(q.stock == null && q.sold != null) add("Outlet ${i+1}, ${Product.entries[p].label}: Stok belum diisi.")
            }
        }
        runCatching { Product.entries.indices.forEach { calls(r,it,true); calls(r,it,false) } }.exceptionOrNull()?.let { add(it.message ?: "Angka terlalu besar.") }
    }
    fun errors(r: DailyReport): List<String> = buildList {
        if(!r.rules.acknowledged) add("Verifikasi makna TG dan aturan keuangan pada Pengaturan Rumus laporan ini.")
        r.products.forEachIndexed { i,s ->
            val name = Product.entries[i].label
            s.sales.forEachIndexed { j,v -> if((v.quantity ?: 0)>0 && v.price == null) add("$name ${Tier.entries[j].label}: harga Belum Diatur.") }
            if(s.delivery == null && (s.sales.any { it.quantity != null } || s.sample != null || s.bonus != null)) add("$name: DO belum diisi.")
            runCatching {
                if((tg(s,r.rules) ?: 0)<0) add("$name: stok tidak mencukupi (TG negatif).")
                if(!r.rules.automaticTg && s.delivery != null && s.manualTg == null) add("$name: TG manual belum diisi.")
                productTotal(s)
            }.exceptionOrNull()?.let { add("$name: angka hasil terlalu besar.") }
        }
        r.testers.forEachIndexed { i,t ->
            if(t.delivery == null && t.sample != null) add("Tester ${Product.entries[i].label}: DO belum diisi.")
            if((tg(t,r.rules) ?: 0)<0) add("Tester ${Product.entries[i].label}: stok tidak mencukupi.")
            if(!r.rules.automaticTg && t.delivery != null && t.manualTg == null) add("Tester ${Product.entries[i].label}: TG manual belum diisi.")
        }
        runCatching { total(r); balance(r) }.exceptionOrNull()?.let { add("Total terlalu besar untuk diekspor secara akurat.") }
    }
    fun rupiah(n: Long?) = n?.let { "Rp " + NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).format(it) } ?: "Belum lengkap"
    fun number(n: Long?) = n?.toString() ?: "Belum diisi"
    fun safeMoney(block: () -> Long?) = runCatching { rupiah(block()) }.getOrDefault("Angka terlalu besar")
    fun validate(r: CallReport) {
        require(r.id.matches(Regex("[a-f0-9-]{36}"))) { "ID laporan tidak valid." }
        require(r.outlets.all { it.quantities.size == 3 && it.quantities.all { q -> listOf(q.stock,q.sold).all(::validInput) } }) { "Data outlet tidak valid." }
        java.time.LocalDate.parse(r.identity.date)
    }
    fun validate(r: DailyReport) {
        require(r.id.matches(Regex("[a-f0-9-]{36}"))) { "ID laporan tidak valid." }
        require(r.products.size == 3 && r.testers.size == 3)
        require(listOf(r.rules.debtSign,r.rules.settlementSign,r.rules.transferSign,r.rules.expenseSign).all { it == 1 || it == -1 })
        require(r.products.all { it.sales.size == 3 && listOf(it.delivery,it.sample,it.bonus,it.manualTg).all(::validInput) && it.sales.all { s -> validInput(s.quantity) && validInput(s.price) } })
        require(r.testers.all { listOf(it.delivery,it.sample,it.manualTg).all(::validInput) })
        require(listOf(r.finance.previous,r.finance.debt,r.finance.settlement,r.finance.transfer,r.finance.expense).all(::validInput))
        java.time.LocalDate.parse(r.identity.date)
    }
    fun validate(s: Settings) {
        require(s.prices.size==3 && s.prices.all { it.size==3 && it.all(::validInput) }) { "Pengaturan harga tidak valid." }
        require(listOf(s.rules.debtSign,s.rules.settlementSign,s.rules.transferSign,s.rules.expenseSign).all { it==1||it == -1 }) { "Aturan saldo tidak valid." }
        java.time.LocalDate.parse(s.profile.date)
    }
    fun validInput(v: Long?) = v == null || v in 0..MAX
}
