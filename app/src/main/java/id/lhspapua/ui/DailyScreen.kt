package id.lhspapua.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*
import java.io.File

@Composable internal fun DailyScreen(r:DailyReport,state:UiState,vm:LhsViewModel,save:(List<File>)->Unit,share:(List<File>)->Unit,attach:()->Unit,advanced:()->Unit) {
    var section by rememberSaveable(r.id) { mutableIntStateOf(if(r.status==ReportStatus.FINAL||r.formatVersion<2)3 else vm.rememberedSection(r.id).coerceIn(0,3)) }
    var trail by rememberSaveable(r.id) { mutableStateOf(if(section in 1..2)intArrayOf(0) else intArrayOf()) }
    var reviewFrom by rememberSaveable(r.id) { mutableIntStateOf(0) }
    val stockScroll=rememberScrollState()
    val testerScroll=rememberScrollState()
    val financeScroll=rememberScrollState()
    var product by rememberSaveable(r.id) { mutableIntStateOf(if(r.independent) 0 else -1) }
    var identity by rememberSaveable(r.id) { mutableStateOf(!r.independent && r.identity.sales.isBlank()) }
    var correction by remember { mutableStateOf(false) }
    var upgradeSold by rememberSaveable(r.id) { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    var sequenceDialog by remember { mutableStateOf(false) }
    LaunchedEffect(section) { vm.rememberSection(r.id,section) }
    val issues=ReportIssues.daily(r)
    fun route(issue:ReportIssue) {
        section=issue.section;product=issue.product
        if(issue.field=="Nama Sales"||issue.field=="identity")identity=true
        if(issue.field=="rules")advanced() else vm.focus(issue.field)
    }
    KeyboardAwareBack {
        if(state.busy)return@KeyboardAwareBack
        when {
            section==3 && r.status==ReportStatus.DRAFT && r.formatVersion>=2 -> section=reviewFrom
            trail.isNotEmpty() && r.status==ReportStatus.DRAFT && r.formatVersion>=2 -> {
                section=trail.last()
                trail=trail.copyOf(trail.size-1)
            }
            else -> vm.backToRoot()
        }
        vm.flushPending()
    }
    if(section==3||r.status==ReportStatus.FINAL||r.formatVersion<2) {
        ReportReview(state,vm,save,share,day=r,onEdit={ section=reviewFrom },onIssue=::route,onLegacy={ legacy=true })
    } else FlowFrame(
        pageKey="day-${r.id}-$section",state=state,vm=vm,primary="Periksa Laporan",
        onPrimary={ reviewFrom=section;section=3;vm.flushPending() },
        scroll=when(section) { 0 -> stockScroll;1 -> testerScroll;else -> financeScroll },
        header={
            Column(
                Modifier.fillMaxWidth().padding(horizontal=16.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp)
            ) {
                EqualTabs(
                    labels=listOf(if(r.independent) "Stok & Penjualan" else "Barang","Tester","Keuangan"),
                    selected=section,
                    tagPrefix="daily-section"
                ) { next ->
                    if(section!=next) {
                        val prior=trail.indexOf(next)
                        trail=if(prior>=0)trail.copyOf(prior) else trail+section
                        section=next
                        vm.flushPending()
                    }
                }
                if(r.independent && section==0) {
                    EqualTabs(Product.entries.map { it.label },product,"daily-product",secondary=true) {
                        product=it
                        vm.flushPending()
                    }
                }
            }
        }
    ) {
        ReportIdentity(r.identity,identity,{ identity=it },vm::updateIdentity,compact=true) {
            if(r.independent) identity=false
            else if(r.identity.sales.isBlank()) vm.focus("Nama Sales")
            else vm.prepareDailyIdentity { identity=false }
        }
        if(identity && r.usesLedger) {
            val sameDate=state.days.filter { it.id!=r.id&&it.identity.date==r.identity.date&&it.identity.stockOwnerId==r.identity.stockOwnerId&&r.identity.stockOwnerId.isNotBlank() }
            if(sameDate.isNotEmpty())TextButton(onClick={ sequenceDialog=true }) { Text("Laporan lain pada tanggal ini") }
        }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        when(section) {
            0->{
                if(r.usesLedger) {
                    val source=state.days.find { it.id==r.stockSourceId }
                    if(r.products.any { it.opening==null })Notice("Belum ada stok sebelumnya. Masukkan stok awal untuk mulai.")
                    else if(source!=null)Text("Stok awal dari ${source.identity.displayDate()}",style=MaterialTheme.typography.bodySmall)
                    if(source!=null&&r.openingMode==OpeningMode.AUTO)TextButton(onClick={ correction=true }) { Text("Sesuaikan Stok Awal") }
                }
                if(r.independent) {
                    val p=product.coerceIn(Product.entries.indices)
                    if(r.canEnableAutomaticSold)TextButton(onClick={ upgradeSold=true },enabled=!state.busy) {
                        Text("Aktifkan Jual Otomatis")
                    }
                    IndependentProductEditor(r,p,vm)
                } else Product.entries.forEachIndexed { p,name ->
                    val stock=r.products[p]
                    val ownIssues=issues.filter { it.section==0 && it.product==p }
                    Surface(onClick={ product=if(product==p)-1 else p },modifier=Modifier.fillMaxWidth(),
                        color=if(product==p)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape=MaterialTheme.shapes.small) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(name.label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            Text("${if(ownIssues.isEmpty())"Lengkap" else "Perlu diisi"} · Terjual ${runCatching { Calculation.number(Calculation.sold(stock)) }.getOrDefault("—")} · Sisa ${runCatching { Calculation.number(Calculation.tg(stock,r.rules)) }.getOrDefault("—")}",
                                style=MaterialTheme.typography.bodySmall)
                            Text(if(product==p)"Tutup" else "Isi Stok & Penjualan",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                        }
                    }
                    if(product==p)ProductEditor(r,p,state,vm)
                }
                TotalLine("Total Penjualan",Calculation.safeMoney(empty="—") { Calculation.total(r) })
            }
            1->if(r.independent) IndependentTesterEditor(r,vm) else {
                Text("Tester",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("Stok tester dicatat terpisah dari barang jual.",style=MaterialTheme.typography.bodySmall)
                Product.entries.forEachIndexed { p,name->
                    val t=r.testers[p]
                    Text(name.label,fontWeight=FontWeight.Bold)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Numeric("Stok Awal",t.opening,modifier=Modifier.weight(1f),tag="tester-$p-opening",readonly=r.openingMode==OpeningMode.AUTO&&r.stockSourceId!=null,error=if(t.opening==null)"Isi stok awal" else null) { vm.setOpening(p,it,true) }
                        Numeric("DO Tester",t.delivery,modifier=Modifier.weight(1f),tag="tester-$p-do") { n->vm.day { it.copy(testers=it.testers.replace(p,it.testers[p].copy(delivery=n))) } }
                        Numeric("Sampel",t.sample,modifier=Modifier.weight(1f),tag="tester-$p-sample",done=p==2) { n->vm.day { it.copy(testers=it.testers.replace(p,it.testers[p].copy(sample=n))) } }
                    }
                    val tg=runCatching { Calculation.tg(t,r.rules) }.getOrNull()
                    Text("Sisa Tester: ${Calculation.number(tg)}",color=if(tg!=null&&tg<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick={ vm.day { it.copy(openingMode=if(t.opening==null)OpeningMode.MANUAL else it.openingMode,testers=it.testers.replace(p,it.testers[p].copy(opening=t.opening ?: 0,delivery=t.delivery ?: 0,sample=t.sample ?: 0))) } }) { Text("Tidak Ada — Isi Kolom Kosong 0") }
                    HorizontalDivider()
                }
            }
            2->FinanceEditor(r,vm,attach,advanced)
        }
    }
    if(upgradeSold && r.canEnableAutomaticSold) {
        val changes=r.products.mapIndexed { index,stock ->
            val old=stock.manualSold?.let(Calculation::grouped) ?: "—"
            val updated=runCatching { Calculation.sold(stock)?.let(Calculation::grouped) ?: "—" }.getOrDefault("terlalu besar")
            "${Product.entries[index].label}: $old → $updated"
        }.joinToString("\n")
        Confirmation("Aktifkan Jual Otomatis?",
            "Jual pada draft ini akan mengikuti Grosir + Retail. Nilai manual lama tetap tercatat dalam data draft.\n\n$changes",
            "Aktifkan",{ upgradeSold=false;vm.enableAutomaticSold() },{ upgradeSold=false })
    }
    if(correction)Confirmation("Sesuaikan stok awal?","Isi jumlah sesuai stok fisik. Perubahan ini dicatat pada laporan dan tidak mengubah laporan sumber.","Sesuaikan",{ vm.manualOpening();correction=false },{ correction=false })
    if(legacy)Confirmation("Lengkapi laporan lama?","Identitas, transaksi, harga, dan lampiran tetap disimpan. Isi Stok Awal sebelum laporan dipakai untuk stok berikutnya.","Lengkapi",{ vm.adoptLegacy();section=0;identity=true;legacy=false },{ legacy=false })
    if(sequenceDialog) {
        var number by remember { mutableStateOf<Long?>(r.stockSequence.toLong()) }
        AlertDialog(onDismissRequest={ sequenceDialog=false },title={ Text("Laporan pada tanggal yang sama") },text={ Column { Text("Setiap laporan mempunyai urutan berbeda. Laporan berikutnya memakai sisa stok laporan sebelumnya yang sudah final.");Numeric("Laporan ke",number) { number=it } } },confirmButton={ TextButton(onClick={ if(number!=null&&number!! in 1..Int.MAX_VALUE.toLong()) { vm.day { it.copy(stockSequence=number!!.toInt()) };sequenceDialog=false } }) { Text("Gunakan") } },dismissButton={ TextButton(onClick={ sequenceDialog=false }) { Text("Batal") } })
    }
}
@Composable private fun IndependentProductEditor(report:DailyReport,product:Int,vm:LhsViewModel) {
    val stock=report.products[product]
    fun update(change: (Stock) -> Stock) {
        vm.day { report ->
            report.copy(products=report.products.replace(product,change(report.products[product])))
        }
    }
    Panel("Stok") {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Numeric("Barang Masuk (DO)",stock.delivery,modifier=Modifier.weight(1f),tag="stock-$product-do") { n ->
                update { it.copy(delivery=n) }
            }
            if(report.automaticSold) {
                val sold=runCatching { Calculation.stockSold(report,product)?.let { "${Calculation.grouped(it)} BUNGKUS" } ?: "—" }
                    .getOrDefault("Angka terlalu besar")
                Surface(modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(8.dp)) {
                        Text("JUAL (OTOMATIS)",style=MaterialTheme.typography.labelSmall)
                        Text(sold,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("stock-$product-sold-auto"))
                    }
                }
            } else Numeric("Jual",stock.manualSold,modifier=Modifier.weight(1f),tag="stock-$product-sold") { n ->
                update { it.copy(manualSold=n) }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Numeric("Sampel",stock.sample,modifier=Modifier.weight(1f),tag="stock-$product-sample") { n ->
                update { it.copy(sample=n) }
            }
            Numeric("Bonus",stock.bonus,modifier=Modifier.weight(1f),tag="stock-$product-bonus") { n ->
                update { it.copy(bonus=n) }
            }
            Numeric("Sisa (TG)",stock.manualTg,modifier=Modifier.weight(1f),tag="stock-$product-tg") { n ->
                update { it.copy(manualTg=n) }
            }
        }
        HorizontalDivider()
        Text("Penjualan",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        listOf(Tier.G,Tier.RTL).forEach { tier ->
            val index=tier.ordinal
            val sale=stock.sales[index]
            val label=if(tier==Tier.G) "Grosir (G)" else "Retail (RTL)"
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Numeric(label,sale.quantity,modifier=Modifier.weight(1f),tag="sale-$product-${tier.label}",done=tier==Tier.RTL) { n ->
                    update { it.copy(sales=it.sales.replace(index,it.sales[index].copy(quantity=n))) }
                }
                Column(Modifier.weight(1.3f)) {
                    Text("× ${Calculation.rupiah(sale.price)}",style=MaterialTheme.typography.bodySmall)
                    Text(Calculation.safeMoney(empty="—") { Calculation.subtotal(sale) },fontWeight=FontWeight.SemiBold)
                }
            }
        }
        TotalLine("Total Produk",Calculation.safeMoney(empty="—") { Calculation.productTotal(stock) })
    }
}

@Composable private fun IndependentTesterEditor(report: DailyReport, vm: LhsViewModel) {
    Product.entries.forEachIndexed { index, product ->
        val tester=report.testers[index]
        fun update(change: (Tester) -> Tester) {
            vm.day { current ->
                current.copy(testers=current.testers.replace(index,change(current.testers[index])))
            }
        }
        Panel(product.label) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Numeric("DO",tester.delivery,modifier=Modifier.weight(1f),tag="tester-$index-do") { n ->
                    update { it.copy(delivery=n) }
                }
                Numeric("Sampel",tester.sample,modifier=Modifier.weight(1f),tag="tester-$index-sample") { n ->
                    update { it.copy(sample=n) }
                }
                Numeric("TG",tester.manualTg,modifier=Modifier.weight(1f),tag="tester-$index-tg",done=index==2) { n ->
                    update { it.copy(manualTg=n) }
                }
            }
        }
    }
}

@Composable private fun ProductEditor(r:DailyReport,p:Int,state:UiState,vm:LhsViewModel) {
    val stock=r.products[p]
    val historicalPrices = stock.sales[Tier.SG.ordinal].applicable
    var prices by rememberSaveable(r.id,p) { mutableStateOf(false) }
    var promotions by rememberSaveable(r.id,p) { mutableStateOf((stock.sample ?: 0)>0||(stock.bonus ?: 0)>0) }
    LaunchedEffect(state.focusSerial) {
        if(state.focusTag.startsWith("price-$p"))prices=true
        if(state.focusTag=="stock-$p-sample"||state.focusTag=="stock-$p-bonus")promotions=true
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Numeric("Stok Awal",stock.opening,modifier=Modifier.weight(1f),tag="stock-$p-opening",readonly=r.openingMode==OpeningMode.AUTO&&r.stockSourceId!=null,error=if(stock.opening==null)"Isi stok awal" else null) { vm.setOpening(p,it) }
        Numeric("Barang Masuk (DO)",stock.delivery,modifier=Modifier.weight(1f),tag="stock-$p-do") { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(delivery=n))) } }
    }
    Text("Stok tersedia: ${runCatching { Calculation.number(Calculation.available(stock)) }.getOrDefault("Angka terlalu besar")}",style=MaterialTheme.typography.bodySmall)
    Tier.entries.forEachIndexed { t,tier->
        val sale=stock.sales[t]
        if(!sale.applicable) return@forEachIndexed
        val label=listOf("Grosir","Sub Grosir","Retail")[t]
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1.5f)) {
                Text("$label (${tier.label})",fontWeight=FontWeight.SemiBold)
                if(sale.price==null)TextButton(onClick={ prices=true;vm.focus("price-$p-$t") }) { Text("Atur Harga $label") }
                else Text(Calculation.rupiah(sale.price),style=MaterialTheme.typography.bodySmall)
            }
            Numeric("Jumlah $label",sale.quantity,modifier=Modifier.weight(1f),tag="sale-$p-${tier.label}",compact=true,done=t==2,error=if((sale.quantity ?: 0)>0&&sale.price==null)"Atur harga" else null) { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(sales=it.products[p].sales.replace(t,it.products[p].sales[t].copy(quantity=n))))) } }
        }
        Text("Subtotal: ${Calculation.safeMoney { Calculation.subtotal(sale) }}",style=MaterialTheme.typography.bodySmall)
    }
    if(historicalPrices) {
        Text("Harga tersimpan pada laporan ini.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={ prices=!prices }) { Text(if(prices)"Tutup Harga" else "Ubah Harga Laporan Ini") }
    }
    if(historicalPrices && prices) {
        Text("Harga hanya berubah pada laporan ini.",style=MaterialTheme.typography.bodySmall)
        Tier.entries.forEachIndexed { t,tier->Numeric("Harga ${tier.label}",stock.sales[t].price,money=true,tag="price-$p-$t",done=t==2) { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(sales=it.products[p].sales.replace(t,it.products[p].sales[t].copy(price=n))))) } } }
    }
    TextButton(onClick={ promotions=!promotions }) { Text(if(promotions)"Tutup Sampel & Bonus" else "Sampel & Bonus · ${stock.sample ?: "—"} / ${stock.bonus ?: "—"}") }
    if(promotions)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Numeric("Sampel",stock.sample,modifier=Modifier.weight(1f),tag="stock-$p-sample") { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(sample=n))) } }
        Numeric("Bonus",stock.bonus,modifier=Modifier.weight(1f),tag="stock-$p-bonus",done=true) { n->vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(bonus=n))) } }
    }
    TextButton(onClick={ vm.day { it.copy(products=it.products.replace(p,it.products[p].copy(delivery=stock.delivery ?: 0,sample=stock.sample ?: 0,bonus=stock.bonus ?: 0,sales=stock.sales.map { sale->if(sale.applicable) sale.copy(quantity=sale.quantity ?: 0) else sale }))) } }) { Text("Tidak Ada — Isi Jumlah Kosong 0") }
    val tg=runCatching { Calculation.tg(stock,r.rules) }.getOrNull()
    Text("Terjual: ${runCatching { Calculation.number(Calculation.sold(stock)) }.getOrDefault("—")} · Sisa Stok: ${Calculation.number(tg)}",fontWeight=FontWeight.Bold,color=if(tg!=null&&tg<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if(tg!=null&&tg<0)Notice("Stok tidak mencukupi. Periksa Stok Awal, DO, penjualan, sampel, dan bonus.",true)
    TotalLine("Total Produk",Calculation.safeMoney { Calculation.productTotal(stock) })
    HorizontalDivider()
}
@Composable private fun FinanceEditor(r:DailyReport,vm:LhsViewModel,attach:()->Unit,advanced:()->Unit) {
    val f=r.finance
    Text("Keuangan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    TotalLine("Total Penjualan",Calculation.safeMoney(empty="—") { Calculation.total(r) })
    Numeric("Saldo Sebelum",f.previous,money=true,signed=true,error=if(!r.independent&&f.previous==null)"Isi saldo sebelumnya, termasuk 0 jika tidak ada" else null) { n->vm.day { it.copy(finance=it.finance.copy(previous=n)) } }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Numeric("Piutang",f.debt,money=true,modifier=Modifier.weight(1f)) { n->vm.day { it.copy(finance=it.finance.copy(debt=n)) } }
        Numeric("Pelunasan",f.settlement,money=true,modifier=Modifier.weight(1f)) { n->vm.day { it.copy(finance=it.finance.copy(settlement=n)) } }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Numeric("Transfer",f.transfer,money=true,modifier=Modifier.weight(1f)) { n->vm.day { it.copy(finance=it.finance.copy(transfer=n)) } }
        Numeric("Pengeluaran",f.expense,money=true,modifier=Modifier.weight(1f),done=!r.independent) { n->vm.day { it.copy(finance=it.finance.copy(expense=n)) } }
    }
    if(r.independent || !r.rules.automaticFinance)Numeric(if(r.independent) "Saldo Akhir" else "Saldo Akhir Manual",f.manualBalance,money=true,signed=true,done=true) { n->vm.day { it.copy(finance=it.finance.copy(manualBalance=n)) } }
    else TotalLine("Saldo Akhir",Calculation.safeMoney { Calculation.balance(r) })
    if(!r.independent&&r.rules.automaticFinance&&!r.rules.acknowledged) {
        Notice("Aturan saldo belum disetujui. Verifikasi sekali, atau pilih saldo manual.")
        OutlinedButton(onClick=advanced,modifier=Modifier.fillMaxWidth()) { Text("Atur Keuangan") }
    }
    if(!r.independent)TextButton(onClick={ vm.day { it.copy(finance=it.finance.copy(previous=f.previous ?: 0,debt=f.debt ?: 0,settlement=f.settlement ?: 0,transfer=f.transfer ?: 0,expense=f.expense ?: 0)) } }) { Text("Tidak Ada — Isi Uang Kosong 0") }
    TextInput("Keterangan",f.notes,true) { text->vm.day { it.copy(finance=it.finance.copy(notes=text)) } }
    TextInput("Dibuat Oleh",f.madeBy,done=true) { text->vm.day { it.copy(finance=it.finance.copy(madeBy=text)) } }
    OutlinedButton(onClick=attach,modifier=Modifier.fillMaxWidth()) { Text("Tambah Bukti Transfer / Biaya") }
    r.attachments.forEach { a->Row(verticalAlignment=Alignment.CenterVertically) { Text(a.label,Modifier.weight(1f));ConfirmButton("Hapus","Hapus lampiran ini dari laporan?") { vm.day { it.copy(attachments=it.attachments-a) } } } }
}
