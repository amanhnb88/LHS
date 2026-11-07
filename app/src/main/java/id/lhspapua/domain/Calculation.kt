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
        !s.applicable || s.quantity == null -> null
        s.quantity == 0L -> 0L
        s.price == null -> null
        else -> bounded(Math.multiplyExact(s.quantity, s.price))
    }
    fun sold(s: Stock) = sum(s.sales.filter { it.applicable }.map { it.quantity })
    private fun missingPrice(s: Sale) = s.applicable && (s.quantity ?: 0) > 0 && s.price == null
    fun productTotal(s: Stock): Long? = if (s.sales.any(::missingPrice)) null else sum(s.sales.map(::subtotal))
    fun reportStock(r: DailyReport, product: Int): Stock {
        val stock=r.products[product]
        require(Tier.entries.all { r.salesMode.allows(it) || (stock.sales[it.ordinal].quantity ?: 0L)==0L }) {
            "${Product.entries[product].label}: kategori terkunci masih berisi angka. Periksa mode laporan di Pengaturan."
        }
        return if(r.salesMode==IntegrationMode.SALES || r.salesMode==IntegrationMode.LEADER)
            stock.copy(sales=stock.sales.mapIndexed { t,sale -> if(r.salesMode.allows(Tier.entries[t]))sale else sale.copy(applicable=false) })
        else stock
    }
    fun productTotal(r: DailyReport, product: Int): Long? = productTotal(reportStock(r,product))
    fun total(r: DailyReport): Long? {
        val stocks=r.products.indices.map { reportStock(r,it) }
        return if(stocks.any { it.sales.any(::missingPrice) }) null else sum(stocks.map(::productTotal))
    }
    fun available(s: Stock): Long? = if(s.opening==null || s.delivery==null) null else bounded(Math.addExact(s.opening,s.delivery))
    fun tg(s: Stock, r: Rules): Long? {
        if(s.openingTracked) {
            if(s.opening==null || s.delivery==null || s.sample==null || s.bonus==null || s.sales.any { it.applicable && it.quantity==null }) return null
            return bounded(available(s)!! - (sold(s) ?: 0) - s.sample - s.bonus)
        }
        return if (!r.automaticTg) s.manualTg else s.delivery?.let { bounded(it - (sold(s) ?: 0) - (if(r.subtractSample) s.sample ?: 0 else 0) - (if(r.subtractBonus) s.bonus ?: 0 else 0)) }
    }
    fun tg(t: Tester, r: Rules): Long? {
        if(t.openingTracked) {
            if(t.opening==null || t.delivery==null || t.sample==null) return null
            return bounded(Math.addExact(t.opening,t.delivery) - t.sample)
        }
        return if (!r.automaticTg) t.manualTg else t.delivery?.let { bounded(it - (t.sample ?: 0)) }
    }
    fun stockSold(r: DailyReport, product: Int): Long? =
        if(r.independent && !r.automaticSold) r.products[product].manualSold else sold(reportStock(r,product))

    fun stockTg(r: DailyReport, product: Int): Long? =
        if(r.independent) r.products[product].manualTg else tg(r.products[product], r.rules)

    fun testerTg(r: DailyReport, product: Int): Long? =
        if(r.independent) r.testers[product].manualTg else tg(r.testers[product], r.rules)

    fun balance(r: DailyReport): Long? {
        if(r.independent) return r.finance.manualBalance
        if (r.products.any { it.sales.any(::missingPrice) }) return null
        val f = r.finance
        if(!r.rules.automaticFinance) return f.manualBalance
        if(r.formatVersion>=2 && listOf(f.previous,f.debt,f.settlement,f.transfer,f.expense).any { it==null }) return null
        val numbers = listOf(f.previous, total(r), f.debt?.times(r.rules.debtSign), f.settlement?.times(r.rules.settlementSign), f.transfer?.times(r.rules.transferSign), f.expense?.times(r.rules.expenseSign))
        return sum(numbers)
    }
    fun calls(r: CallReport, product: Int, stock: Boolean) = sum(r.outlets.map { if(stock) it.quantities[product].stock else it.quantities[product].sold })
    fun modeConflicts(r: DailyReport, mode: IntegrationMode = r.salesMode): List<String> =
        r.products.flatMapIndexed { p, stock ->
            Tier.entries.filter { !mode.allows(it) && (stock.sales[it.ordinal].quantity ?: 0L) != 0L }.map {
                "${Product.entries[p].label}: ${if(it==Tier.G) "Grosir" else if(it==Tier.RTL) "Retail" else "SG"} masih berisi angka. Perbaiki dalam mode sebelumnya sebelum memakai ${mode.label}."
            }
        }

    /** Also guards callbacks/storage writes; disabled UI is not the authority. */
    fun validateSalesEdit(old: DailyReport, edit: DailyReport) {
        require(edit.reportMode == old.reportMode) { "Ubah mode laporan melalui Pengaturan Integrasi Laporan." }
        require(old.products.indices.all { p -> Tier.entries.all { tier ->
            old.salesMode.allows(tier) || old.products[p].sales[tier.ordinal] == edit.products[p].sales[tier.ordinal]
        } }) { "Kategori penjualan dikunci oleh mode laporan. Ubah mode melalui Pengaturan." }
    }
    fun errors(r: CallReport): List<String> = buildList {
        r.activeOutlets().forEachIndexed { i, outlet ->
            outlet.quantities.forEachIndexed { p, quantity ->
                val label = "Outlet ${i+1}, ${Product.entries[p].label}"
                if(!validInput(quantity.stock)) add("$label: angka Stok tidak valid.")
                if(!validInput(quantity.sold)) add("$label: angka Jual tidak valid.")
            }
        }
        runCatching { Product.entries.indices.forEach { calls(r,it,true); calls(r,it,false) } }.exceptionOrNull()?.let { add(it.message ?: "Angka terlalu besar.") }
    }
    fun errors(r: DailyReport): List<String> = buildList {
        addAll(modeConflicts(r))
        if(r.independent) {
            runCatching { validate(r) }.onFailure { add(it.message ?: "Periksa angka pada laporan.") }
            if(r.automaticSold)r.products.forEachIndexed { index,stock ->
                runCatching { sold(stock) }.onFailure { add("${Product.entries[index].label}: jumlah Jual terlalu besar.") }
            }
            runCatching { total(r) }.onFailure { if(modeConflicts(r).isEmpty())add("Total terlalu besar untuk diekspor secara akurat.") }
            return@buildList
        }
        if(r.rules.automaticFinance && !r.rules.acknowledged) add("Aturan saldo belum disetujui. Verifikasi sekali di Pengaturan Lanjutan, atau gunakan Saldo Akhir manual.")
        if(!r.rules.automaticFinance && r.finance.manualBalance==null) add("Saldo Akhir manual belum diisi.")
        if(r.formatVersion>=2) {
            addAll(stockErrors(r))
            if(r.identity.sales.isBlank() || r.identity.stockOwnerId.isBlank()) add("Pilih atau isi Sales pada Informasi Harian.")
            if(r.stockStale) add(r.stockIssue.ifBlank { "Stok berubah dari laporan sebelumnya. Periksa ulang laporan ini." })
            if(listOf(r.finance.previous,r.finance.debt,r.finance.settlement,r.finance.transfer,r.finance.expense).any { it==null }) add("Lengkapi Keuangan; isi 0 untuk nilai yang tidak ada.")
        }
        r.products.forEachIndexed { i,s ->
            val name = Product.entries[i].label
            s.sales.forEachIndexed { j,v -> if(missingPrice(v)) add("$name ${Tier.entries[j].label}: harga Belum Diatur.") }
            if(!s.openingTracked && s.delivery == null && (s.sales.any { it.applicable && it.quantity != null } || s.sample != null || s.bonus != null)) add("$name: DO belum diisi.")
            runCatching {
                if(!s.openingTracked && (tg(s,r.rules) ?: 0)<0) add("$name: stok tidak mencukupi (TG negatif).")
                if(!s.openingTracked && !r.rules.automaticTg && s.delivery != null && s.manualTg == null) add("$name: TG manual belum diisi.")
                productTotal(s)
            }.exceptionOrNull()?.let { add("$name: angka hasil terlalu besar.") }
        }
        r.testers.forEachIndexed { i,t ->
            if(!t.openingTracked) runCatching {
                if(t.delivery == null && t.sample != null) add("Tester ${Product.entries[i].label}: DO belum diisi.")
                if((tg(t,r.rules) ?: 0)<0) add("Tester ${Product.entries[i].label}: stok tidak mencukupi.")
                if(!r.rules.automaticTg && t.delivery != null && t.manualTg == null) add("Tester ${Product.entries[i].label}: TG manual belum diisi.")
            }.onFailure { add("Angka tester terlalu besar.") }
        }
        runCatching { total(r); balance(r) }.exceptionOrNull()?.let { add("Total terlalu besar untuk diekspor secara akurat.") }
    }
    fun grouped(n:Long) = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).format(n)
    fun rupiah(n: Long?) = n?.let { "Rp " + grouped(it) } ?: "Belum lengkap"
    fun number(n: Long?) = n?.toString() ?: "Belum diisi"
    fun safeMoney(empty: String = "Belum lengkap", block: () -> Long?) = runCatching { block()?.let(::rupiah) ?: empty }.getOrDefault("Angka terlalu besar")
    fun validate(r: CallReport) {
        require(r.id.matches(Regex("[a-f0-9-]{36}"))) { "ID laporan tidak valid." }
        require(r.outlets.all { it.quantities.size == 3 && it.quantities.all { q -> listOf(q.stock,q.sold).all(::validInput) } }) { "Data outlet tidak valid." }
        java.time.LocalDate.parse(r.identity.date)
    }
    fun validate(r: DailyReport) {
        require(r.id.matches(Regex("[a-f0-9-]{36}"))) { "ID laporan tidak valid." }
        require(r.products.size == 3 && r.testers.size == 3)
        require(listOf(r.rules.debtSign,r.rules.settlementSign,r.rules.transferSign,r.rules.expenseSign).all { it == 1 || it == -1 })
        require(r.products.all { it.sales.size == 3 && listOf(it.delivery,it.sample,it.bonus,it.manualTg,it.opening,it.manualSold).all(::validInput) && it.sales.all { s -> validInput(s.quantity) && validInput(s.price) } })
        r.products.forEach { stock ->
            val sg = stock.sales[Tier.SG.ordinal]
            require(stock.sales[Tier.G.ordinal].applicable && stock.sales[Tier.RTL.ordinal].applicable)
            if(r.independent) require(!sg.applicable) { "SG tidak berlaku untuk laporan baru." }
            if(!sg.applicable) {
                require(sg.price == null && (sg.quantity ?: 0) == 0L) { "SG tidak berlaku untuk laporan ini." }
                require(stock.sales.filter { it.applicable }.all { (it.price ?: 0) > 0 }) { "Harga resmi belum lengkap." }
            }
        }
        require(r.testers.all { listOf(it.delivery,it.sample,it.manualTg,it.opening).all(::validInput) })
        require(validSigned(r.finance.previous) && validSigned(r.finance.manualBalance))
        require(listOf(r.finance.debt,r.finance.settlement,r.finance.transfer,r.finance.expense).all(::validInput))
        require(r.formatVersion in 1..4 && r.stockSequence>0 && r.revision>=0)
        require(r.reportMode !in listOf(IntegrationMode.SALES,IntegrationMode.LEADER) || r.automaticSold) { "Mode Sales/Leader memerlukan laporan Jual Otomatis." }
        if(r.usesLedger) require(r.products.all { it.openingTracked } && r.testers.all { it.openingTracked })
        java.time.LocalDate.parse(r.identity.date)
    }
    fun validate(s: Settings) {
        require(s.prices.size==3 && s.prices.all { it.size==3 && it.all(::validInput) }) { "Pengaturan harga tidak valid." }
        require(listOf(s.rules.debtSign,s.rules.settlementSign,s.rules.transferSign,s.rules.expenseSign).all { it==1||it == -1 }) { "Aturan saldo tidak valid." }
        java.time.LocalDate.parse(s.profile.date)
    }
    fun stockErrors(r: DailyReport): List<String> = buildList {
        if(r.independent) return@buildList
        if(!r.usesLedger) { add("Stok awal laporan lama belum diverifikasi."); return@buildList }
        r.products.forEachIndexed { p,s ->
            if(s.opening==null) add("${Product.entries[p].label}: isi Stok Awal atau ambil dari laporan sebelumnya.")
            if(s.delivery==null || s.sample==null || s.bonus==null || s.sales.any { it.applicable && it.quantity==null }) add("${Product.entries[p].label}: jumlah belum lengkap. Isi 0 jika tidak ada.")
            runCatching { if((tg(s,r.rules) ?: 0)<0) add("${Product.entries[p].label}: stok tidak mencukupi.") }.onFailure { add("${Product.entries[p].label}: angka stok terlalu besar.") }
        }
        r.testers.forEachIndexed { p,t ->
            if(t.opening==null) add("Tester ${Product.entries[p].label}: Stok Awal Tester belum diisi.")
            if(t.delivery==null || t.sample==null) add("Tester ${Product.entries[p].label}: jumlah belum lengkap.")
            runCatching { if((tg(t,r.rules) ?: 0)<0) add("Tester ${Product.entries[p].label}: stok tidak mencukupi.") }.onFailure { add("Angka tester terlalu besar.") }
        }
    }
    fun validSigned(v: Long?) = v==null || v in -MAX..MAX
    fun validInput(v: Long?) = v == null || v in 0..MAX
}
