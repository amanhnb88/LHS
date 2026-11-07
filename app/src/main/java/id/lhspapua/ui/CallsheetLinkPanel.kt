package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*

@Composable internal fun rememberCallsheetReview(report:DailyReport,state:UiState):SalesReconciliation.Review? {
    val link=report.callsheetLink
    val source=state.calls.firstOrNull { it.id==link?.sourceId }
    val conflict=link!=null && SalesReconciliation.claimed(link.sourceId,report.id,state.days)
    return remember(report,source,conflict) { SalesReconciliation.review(report,source,conflict) }
}

@Composable internal fun CallsheetLinkPanel(
    report:DailyReport,state:UiState,vm:LhsViewModel,review:SalesReconciliation.Review?=rememberCallsheetReview(report,state)
) {
    val link=report.callsheetLink
    val editable=report.status==ReportStatus.DRAFT && !state.busy
    val offers=report.status==ReportStatus.DRAFT && (report.reportMode?.integrated ?: state.settings.integrationMode.integrated)
    if(link==null && !offers)return
    val candidates=remember(report.identity,report.id,state.calls,state.days,offers,link) {
        if(offers && link==null)SalesReconciliation.candidates(report,state.calls,state.days) else emptyList()
    }
    val source=state.calls.firstOrNull { it.id==link?.sourceId }
    var choosing by remember(report.id) { mutableStateOf(false) }
    var selected by remember(report.id) { mutableStateOf<CallReport?>(null) }
    var unlinking by remember(report.id) { mutableStateOf(false) }
    var details by remember(report.id) { mutableStateOf(false) }

    Panel(if(link==null) "Hubungkan Callsheet" else "Callsheet Terhubung") {
        if(link==null) {
            when(candidates.size) {
                0 -> Text("Belum ada Callsheet Final yang sesuai.")
                1 -> {
                    Text(sourceLabel(candidates.single()),style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick={ selected=candidates.single() },enabled=editable) { Text("Hubungkan") }
                }
                else -> TextButton(onClick={ choosing=true },enabled=editable) { Text("Pilih Callsheet") }
            }
        } else if(review!=null) {
            if(source!=null)Text(sourceLabel(source),style=MaterialTheme.typography.bodySmall)
            val sourceProblem=review.source.problem
            if(sourceProblem!=null)Notice(sourceProblem,error=true)
            else {
                Text("Pencocokan Penjualan",fontWeight=FontWeight.SemiBold)
                Text("Callsheet / LHS · bungkus",style=MaterialTheme.typography.bodySmall)
                review.products.forEach { match ->
                    val status=when(match.status) {
                        SalesReconciliation.Match.MATCH -> "Cocok"
                        SalesReconciliation.Match.DIFFERENT -> "Belum Cocok · selisih ${quantity(kotlin.math.abs(match.difference!!))}"
                        SalesReconciliation.Match.INCOMPLETE -> "Belum Lengkap"
                        SalesReconciliation.Match.INVALID -> "Angka Tidak Valid"
                    }
                    val shownTotal=match.total ?: when {
                        report.salesMode==IntegrationMode.SALES -> match.retail
                        report.salesMode==IntegrationMode.LEADER -> match.gross
                        match.gross==null -> match.retail
                        match.retail==null -> match.gross
                        else -> null
                    }
                    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(match.product.label,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
                            Text("${quantity(match.callsheet)} / ${quantity(shownTotal)}",style=MaterialTheme.typography.bodySmall)
                            Text(status,Modifier.weight(1.35f),textAlign=TextAlign.End,style=MaterialTheme.typography.labelSmall,
                                color=if(match.status==SalesReconciliation.Match.MATCH)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        if(details)Text("Grosir ${quantity(match.gross)} · Retail ${quantity(match.retail)}",style=MaterialTheme.typography.bodySmall)
                        if(editable && match.callsheet!=null && report.salesMode in listOf(IntegrationMode.MANDIRI,IntegrationMode.TERINTEGRASI)) {
                            val missing=when {
                                match.gross==null && match.retail!=null -> Tier.G
                                match.retail==null && match.gross!=null -> Tier.RTL
                                else -> null
                            }
                            if(missing!=null) {
                                val label=if(missing==Tier.G) "Grosir" else "Retail"
                                TextButton(onClick={
                                    vm.day { current ->
                                        val stock=current.products[match.product.ordinal]
                                        val sale=stock.sales[missing.ordinal]
                                        if(current.id!=report.id || current.callsheetLink!=link || sale.quantity!=null) current
                                        else current.copy(products=current.products.replace(match.product.ordinal,
                                            stock.copy(sales=stock.sales.replace(missing.ordinal,sale.copy(quantity=0)))))
                                    }
                                },modifier=Modifier.testTag("call-zero-${match.product.ordinal}-${missing.label}")) {
                                    Text("Tidak Ada $label — Isi 0")
                                }
                            }
                        }
                    }
                }
                if(review.products.any { it.status==SalesReconciliation.Match.INCOMPLETE }) {
                    Text(when(report.salesMode) {
                        IntegrationMode.SALES -> "Isi Retail. Masukkan 0 jika tidak ada penjualan."
                        IntegrationMode.LEADER -> "Isi Grosir. Masukkan 0 jika tidak ada penjualan."
                        else -> "Isi Grosir dan Retail. Masukkan 0 jika tidak ada penjualan."
                    },style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={ details=!details }) { Text(if(details) "Tutup Detail" else "Lihat Detail") }
            }
            if(editable) {
                if(source!=null && review.source==SalesReconciliation.SourceStatus.CHANGED) {
                    TextButton(onClick={ selected=source }) { Text("Konfirmasi Ulang Callsheet") }
                }
                TextButton(onClick={ unlinking=true }) { Text("Lepaskan Tautan Callsheet") }
            }
        }
    }
    if(choosing && editable)AlertDialog(
        onDismissRequest={ choosing=false },title={ Text("Pilih Callsheet Final") },
        text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { candidate ->
                    TextButton(
                        onClick={ choosing=false;selected=candidate },
                        modifier=Modifier.fillMaxWidth().testTag("call-source-${candidate.id}")
                    ) { Text(sourceLabel(candidate,includeRoute=true)) }
                }
            }
        },
        confirmButton={ TextButton(onClick={ choosing=false }) { Text("Batal") } }
    )
    selected?.takeIf { editable }?.let { candidate ->
        val entry=when(report.salesMode) {
            IntegrationMode.SALES -> "Retail tetap diisi pada LHS."
            IntegrationMode.LEADER -> "Grosir tetap diisi pada LHS."
            else -> "Grosir dan Retail tetap diisi pada LHS."
        }
        Confirmation("Hubungkan Callsheet dengan LHS?",
            "${sourceLabel(candidate,includeRoute=true)}\n\nJumlah Jual Callsheet akan menjadi acuan pencocokan. $entry",
            "Hubungkan",{
                selected=null
                vm.changeCallsheetLink(report.id,candidate.id,SalesReconciliation.fingerprint(candidate))
            },{ selected=null })
    }
    if(unlinking && editable)Confirmation("Lepaskan Tautan Callsheet?",
        "Tautan sumber akan dilepas. Mode laporan, angka, dan harga tetap tersimpan.","Lepaskan",{
            unlinking=false
            vm.changeCallsheetLink(report.id,null,null)
        },{ unlinking=false })
}

private fun quantity(value:Long?)=value?.let(Calculation::grouped) ?: "—"

private fun sourceLabel(source:CallReport,includeRoute:Boolean=false):String {
    val identity=source.identity
    val main=listOf(identity.displayDate(),identity.sales,"${source.activeOutlets().size} outlet").joinToString(" · ")
    if(!includeRoute)return main
    val extra=listOfNotNull(identity.area,identity.route,source.completedAt?.let { ReportTime.display(it) }).filter { it.isNotBlank() }
    return if(extra.isEmpty()) main else main+"\n"+extra.joinToString(" · ")
}
