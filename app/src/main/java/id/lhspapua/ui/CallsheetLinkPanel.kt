package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    val offers=report.status==ReportStatus.DRAFT && state.settings.integrationMode==IntegrationMode.TERINTEGRASI
    if(link==null && !offers)return
    val candidates=remember(report.identity,report.id,state.calls,state.days,offers) {
        if(offers)SalesReconciliation.candidates(report,state.calls,state.days) else emptyList()
    }
    val source=state.calls.firstOrNull { it.id==link?.sourceId }
    var choosing by remember(report.id) { mutableStateOf(false) }
    var selected by remember(report.id) { mutableStateOf<CallReport?>(null) }
    var unlinking by remember(report.id) { mutableStateOf(false) }

    Panel("Pencocokan Callsheet") {
        if(link==null) {
            if(candidates.isEmpty())Text("Belum ada Callsheet Final yang cocok dan tersedia untuk ditautkan.")
            else TextButton(onClick={ choosing=true },enabled=editable) { Text("Hubungkan Callsheet") }
        } else if(review!=null) {
            if(source!=null)Text(sourceLabel(source),style=MaterialTheme.typography.bodySmall)
            Text(review.source.label,fontWeight=FontWeight.SemiBold)
            review.source.problem?.let { Notice(it,error=true) }
            review.products.forEach { match ->
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(match.product.label,fontWeight=FontWeight.SemiBold)
                    Text("Callsheet ${quantity(match.callsheet)} · LHS ${quantity(match.total)}",style=MaterialTheme.typography.bodyMedium)
                    Text("G ${quantity(match.gross)} · RTL ${quantity(match.retail)}",style=MaterialTheme.typography.bodySmall)
                    if(review.source==SalesReconciliation.SourceStatus.READY) {
                        val delta=match.difference?.takeIf { it!=0L }?.let { " · Selisih ${Calculation.grouped(it)} bungkus" }.orEmpty()
                        Text(match.status.label+delta,style=MaterialTheme.typography.labelMedium,
                            color=if(match.status==SalesReconciliation.Match.MATCH)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
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
                    ) { Text(candidateLabel(candidate)) }
                }
            }
        },
        confirmButton={ TextButton(onClick={ choosing=false }) { Text("Batal") } }
    )
    selected?.takeIf { editable }?.let { candidate ->
        Confirmation("Hubungkan Callsheet dengan LHS?",
            "${candidateLabel(candidate)}\n\nJumlah Jual Callsheet akan menjadi acuan pencocokan. Grosir dan Retail tetap diisi pada LHS. Data yang sudah ada tidak akan ditimpa.",
            "Hubungkan",{
                selected=null
                vm.changeCallsheetLink(report.id,candidate.id,SalesReconciliation.fingerprint(candidate))
            },{ selected=null })
    }
    if(unlinking && editable)Confirmation("Lepaskan Tautan Callsheet?",
        "LHS ini akan kembali mandiri. Angka, harga, dan laporan tetap tersimpan.","Lepaskan",{
            unlinking=false
            vm.changeCallsheetLink(report.id,null,null)
        },{ unlinking=false })
}

private fun quantity(value:Long?)=value?.let(Calculation::grouped) ?: "—"

private fun sourceLabel(source:CallReport):String =
    listOf(source.identity.displayDate(),source.identity.sales,source.identity.area,source.identity.route,
        ReportTime.display(source.completedAt),"${source.activeOutlets().size} outlet","ID ${source.id.takeLast(8)}")
        .filter { it.isNotBlank() }.joinToString(" · ")

private fun candidateLabel(source:CallReport):String = sourceLabel(source)+"\n"+Product.entries.joinToString(" · ") {
    "${it.label} ${quantity(runCatching { Calculation.calls(source,it.ordinal,false) }.getOrNull())}"
}
