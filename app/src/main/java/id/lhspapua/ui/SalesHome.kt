package id.lhspapua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.lhspapua.domain.*
import java.math.BigInteger
import java.time.format.DateTimeFormatter

@Composable internal fun HomeScreen(state:UiState,vm:LhsViewModel) {
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val reports=reportEntries(state,vm)
    val latest=remember(reports) { reports.filter { it.draft }.minWithOrNull(recentDrafts) }
    ScrollPage {
        Text("Ringkasan Penjualan",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        dashboard?.let { data ->
            val summary=data.summary
            if(summary.salesNames.size>1 || data.sales!=null) SalesFilter(data,vm::dashboardSales)
            Panel("") {
                Metric("Penjualan Hari Ini",SalesDashboard.money(summary.today),"sales-today")
                Metric("Penjualan Bulan Ini",SalesDashboard.money(summary.month),"sales-month")
                Metric("LHS selesai · ${data.period.label}",summary.finalCount.toString(),"sales-count")
            }
            EqualTabs(SalesPeriod.entries.map { it.label },data.period.ordinal,"sales-period") {
                vm.dashboardPeriod(SalesPeriod.entries[it])
            }
            if(!summary.hasFinals) {
                Text("Belum Ada Data Penjualan",style=MaterialTheme.typography.titleMedium)
                Text("Selesaikan Laporan Harian untuk melihat grafik penjualan.")
            } else {
                if(summary.excludedCount>0)Notice(
                    "${summary.excludedCount} LHS Final tanpa total penjualan yang dapat dihitung. Periksa jumlah atau harga di Riwayat."
                )
                if(summary.days.none { it.total!=null })Text("Belum ada omzet tercatat pada periode ini.")
                else Panel("Omzet Harian") { SalesBars(summary.days) }
                Panel("Penjualan per Produk") {
                    Product.entries.forEach { product ->
                        Metric(product.label,SalesDashboard.money(summary.products[product.ordinal]),"sales-product-${product.ordinal}")
                    }
                }
            }
        }
        if(latest!=null) {
            Panel("Lanjutkan Draft") {
                Text("${latest.type} · ${latest.identity.displayDate()} · ${latest.sales}")
                TextButton(onClick={
                    vm.navigate("drafts")
                    latest.open()
                },enabled=!state.busy) { Text("Lanjutkan Laporan") }
            }
        } else Text("Belum ada draf",style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun Metric(label:String,value:String,tag:String) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium)
        Text(value,Modifier.testTag(tag),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    }
}

@Composable private fun SalesFilter(data:DashboardUi,select:(String?)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={ expanded=true }) {
            Text("Sales · "+(data.sales?.let { data.summary.salesNames[it] ?: "Pilihan sebelumnya" } ?: "Semua Sales"))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) {
            DropdownMenuItem(text={ Text("Semua Sales") },onClick={ expanded=false;select(null) })
            data.summary.salesNames.forEach { (id,name) ->
                DropdownMenuItem(text={ Text(name) },onClick={ expanded=false;select(id) })
            }
        }
    }
}

@Composable private fun SalesBars(days:List<SalesPoint>) {
    val colors=MaterialTheme.colorScheme
    val max=days.mapNotNull { it.total }.maxOrNull() ?: BigInteger.ZERO
    val axisDate=remember { DateTimeFormatter.ofPattern("dd/MM") }
    val ticks=minOf(days.size,5)
    val labels=if(ticks==1)listOf(days.first()) else List(ticks) { days[it*(days.size-1)/(ticks-1)] }
    Text(SalesDashboard.money(max),style=MaterialTheme.typography.labelSmall)
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().height(94.dp).testTag("sales-bars"),
        horizontalArrangement=Arrangement.spacedBy(2.dp),
        verticalAlignment=Alignment.Bottom
    ) {
        days.forEach { point ->
            val value=point.total
            val fraction=if(value==null || max.signum()==0)0f else (value.toDouble()/max.toDouble()).toFloat()
            val description="${point.date.format(axisDate)} · "+(value?.let { SalesDashboard.money(it) } ?: "Tidak ada omzet tercatat")
            Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription=description },contentAlignment=Alignment.BottomCenter) {
                if(value!=null)Box(Modifier.fillMaxWidth().height(maxOf(2.dp,94.dp*fraction)).background(colors.primary))
            }
        }
    }
    HorizontalDivider()
    Text("Rp0",style=MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        labels.forEach { Text(it.date.format(axisDate),style=MaterialTheme.typography.labelSmall) }
    }
    Text("Tanpa batang = belum ada omzet tercatat.",style=MaterialTheme.typography.bodySmall)
}
