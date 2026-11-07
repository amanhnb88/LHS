@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.lhspapua.domain.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable internal fun SettingsScreen(section:String,select:(String)->Unit,settings:Settings,vm:LhsViewModel,backup:()->Unit,restore:()->Unit) {
    when(section) {
        "" -> {
            listOf(
                Triple("profil","Profil Sales","Identitas yang sering dipakai."),
                Triple("harga","Harga Produk","Harga resmi Grosir dan Retail."),
                Triple("outlet","Daftar Outlet","Nama dan alamat outlet tersimpan."),
                Triple("fitur-lhs","Pengaturan Fitur LHS","Atur input yang dapat digunakan dalam Laporan Harian."),
                Triple("integrasi","Integrasi Laporan","Hubungkan hasil penjualan Callsheet dengan Laporan Harian."),
                Triple("backup","Backup Data","Simpan salinan laporan dan lampiran."),
                Triple("lanjutan","Pengaturan Lanjutan","Pengaturan untuk laporan lama.")
            ).forEach { (key,title,description)->MainCard(title,description) { select(key) } }
        }
        "profil" -> {
            Text("Profil Sales",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            IdentityEditor(settings.profile,profile=true) { i->vm.settings { it.copy(profile=i.copy(stockOwnerId=if(i.sales.trim().equals(it.profile.sales.trim(),true))i.stockOwnerId else "")) } }
            Text("Profil digunakan sebagai isian awal. Laporan yang sudah tersimpan tetap memakai identitasnya sendiri.",style=MaterialTheme.typography.bodySmall)
        }
        "harga" -> {
            var product by rememberSaveable { mutableIntStateOf(0) }
            Text("Harga Produk",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Harga resmi untuk laporan baru.")
            ProductTabs(product) { product=it }
            Panel(Product.entries[product].label) {
                val prices = OfficialPrices.values[product]
                Text("Grosir (G): ${Calculation.rupiah(prices[Tier.G.ordinal])}")
                Text("Retail (RTL): ${Calculation.rupiah(prices[Tier.RTL.ordinal])}")
                Text("Sub Grosir (SG): Tidak berlaku")
            }
        }
        "outlet" -> SavedOutlets(settings,vm)
        "backup" -> {
            Text("Backup Data",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Backup mencakup laporan, harga, profil, daftar outlet, stok antarhari, dan seluruh lampiran. Simpan secara berkala sebelum mengganti HP atau menghapus data aplikasi.")
            Button(onClick=backup,modifier=Modifier.fillMaxWidth()) { Text("Simpan Backup ZIP") }
            OutlinedButton(onClick=restore,modifier=Modifier.fillMaxWidth()) { Text("Pulihkan Backup ZIP") }
            ConfirmButton("Bersihkan lampiran tidak terpakai","Hapus hanya file lampiran yang sudah tidak dipakai oleh laporan?") { vm.cleanup() }
        }
        "fitur-lhs" -> LhsFeatureOptions(settings.lhsFeatures,vm)
        "integrasi" -> IntegrationOptions(settings.integrationMode,vm)
        "lanjutan" -> AdvancedSettings(settings.rules,vm)
    }
}
@Composable private fun IntegrationOptions(mode:IntegrationMode,vm:LhsViewModel) {
    val current by remember(vm) { vm.state.map { it.day to it.busy }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(vm.state.value.day to vm.state.value.busy)
    val draft=current.first?.takeIf { it.status==ReportStatus.DRAFT && it.automaticSold }
    var requested by remember { mutableStateOf<IntegrationMode?>(null) }
    fun choose(next:IntegrationMode) {
        if(draft!=null && draft.reportMode!=next) {
            if(draft.products.any { stock -> stock.sales.any { it.quantity!=null } }) requested=next
            else vm.changeIntegrationMode(next,applyToDraft=true)
        } else vm.changeIntegrationMode(next)
    }
    Text("Integrasi Laporan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    listOf(IntegrationMode.MANDIRI,IntegrationMode.SALES,IntegrationMode.LEADER).forEach { option ->
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            RadioButton(selected=mode==option,onClick={ choose(option) },enabled=!current.second,
                modifier=Modifier.testTag("integration-mode-${option.name}"))
            TextButton(onClick={ choose(option) },enabled=!current.second) { Text(option.label) }
        }
    }
    Text(when(mode) {
        IntegrationMode.MANDIRI -> "Grosir dan Retail aktif. Callsheet dan LHS diisi terpisah."
        IntegrationMode.SALES -> "Retail aktif, Grosir dikunci. Callsheet dihubungkan setelah konfirmasi."
        IntegrationMode.LEADER -> "Grosir aktif, Retail dikunci. Callsheet dihubungkan setelah konfirmasi."
        IntegrationMode.TERINTEGRASI -> "Mode Terintegrasi lama tetap memakai Grosir dan Retail. Pilih mode baru bila diperlukan."
    },style=MaterialTheme.typography.bodySmall)
    Text("Pilihan awal untuk laporan baru. Laporan Final dan tautan yang sudah ada tetap tersimpan.",style=MaterialTheme.typography.bodySmall)
    if(draft!=null) {
        Text("Draft aktif: ${draft.salesMode.label} · ${draft.identity.displayDate()}",style=MaterialTheme.typography.bodySmall)
        if(draft.reportMode!=mode)TextButton(onClick={ choose(mode) },enabled=!current.second) { Text("Terapkan ke Draft Aktif") }
    }
    requested?.let { next ->
        val conflicts=draft?.let { Calculation.modeConflicts(it,next) }.orEmpty()
        AlertDialog(onDismissRequest={ requested=null },title={ Text("Ubah Mode Laporan?") },
            text={ Text(if(conflicts.isEmpty())
                "Draft ${draft?.identity?.displayDate().orEmpty()} akan memakai ${next.label}. Angka dan harga tetap tersimpan."
                else conflicts.joinToString("\n")+"\n\nMode draft belum berubah. Anda dapat memakai pilihan ini hanya untuk laporan baru.") },
            confirmButton={ TextButton(onClick={ requested=null;vm.changeIntegrationMode(next,applyToDraft=conflicts.isEmpty()) }) {
                Text(if(conflicts.isEmpty()) "Terapkan ke Draft" else "Hanya Laporan Baru")
            } },
            dismissButton={ Column {
                if(conflicts.isEmpty())TextButton(onClick={ requested=null;vm.changeIntegrationMode(next) }) { Text("Hanya Laporan Baru") }
                TextButton(onClick={ requested=null }) { Text("Batal") }
            } })
    }
}
@Composable private fun LhsFeatureOptions(features:LhsFeatureSettings,vm:LhsViewModel) {
    Text("Pengaturan Fitur LHS",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Text("Atur input yang dapat digunakan dalam Laporan Harian.")
    FeatureSwitch("Input Stok","feature-stock",features.stockEnabled) { enabled ->
        vm.settings { it.copy(lhsFeatures=it.lhsFeatures.copy(stockEnabled=enabled)) }
    }
    FeatureSwitch("Input Tester","feature-tester",features.testerEnabled) { enabled ->
        vm.settings { it.copy(lhsFeatures=it.lhsFeatures.copy(testerEnabled=enabled)) }
    }
    FeatureSwitch("Input Keuangan","feature-finance",features.financeEnabled) { enabled ->
        vm.settings { it.copy(lhsFeatures=it.lhsFeatures.copy(financeEnabled=enabled)) }
    }
}

@Composable private fun FeatureSwitch(label:String,tag:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f))
        Switch(
            checked=checked,onCheckedChange=change,
            modifier=Modifier.testTag(tag).semantics { contentDescription=label }
        )
    }
}

@Composable internal fun AdvancedSettings(current:Rules,vm:LhsViewModel) {
    var rules by remember(current) { mutableStateOf(current) }
    var confirm by remember { mutableStateOf(false) }
    Text("Pengaturan Lanjutan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Notice("Aturan ini tersedia untuk laporan lama. Laporan baru memakai isian stok, tester, dan saldo manual.")
    Text("Aturan Keuangan",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Text("Piutang: penjualan baru yang belum dibayar. Pelunasan: uang dari pembayaran piutang. Transfer: uang yang disetor. Pengeluaran: biaya operasional. Verifikasi arti dan arah perhitungannya dengan perusahaan.")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=rules.automaticFinance,onClick={ rules=rules.copy(automaticFinance=true,acknowledged=false) },label={ Text("Saldo otomatis") })
        FilterChip(selected=!rules.automaticFinance,onClick={ rules=rules.copy(automaticFinance=false,acknowledged=false) },label={ Text("Saldo manual") })
    }
    if(rules.automaticFinance) {
        listOf("Piutang" to rules.debtSign,"Pelunasan" to rules.settlementSign,"Transfer" to rules.transferSign,"Pengeluaran" to rules.expenseSign).forEachIndexed { i,(name,value)->
            Text(name,fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(-1 to "Kurangi",1 to "Tambahkan").forEach { (sign,text)->FilterChip(selected=value==sign,onClick={ rules=when(i) { 0->rules.copy(debtSign=sign,acknowledged=false);1->rules.copy(settlementSign=sign,acknowledged=false);2->rules.copy(transferSign=sign,acknowledged=false);else->rules.copy(expenseSign=sign,acknowledged=false) } },label={ Text(text) }) } }
        }
        Text(rules.financeText())
        Text(if(rules.acknowledged)"Aturan ini sudah disetujui." else "Aturan belum disetujui.",color=if(rules.acknowledged)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    } else Text("Sales mengisi Saldo Akhir secara langsung. Tidak ada rumus saldo yang diasumsikan sebagai aturan perusahaan.")
    Button(onClick={ confirm=true },modifier=Modifier.fillMaxWidth()) { Text(if(rules.automaticFinance)"Verifikasi & Simpan Aturan" else "Gunakan Saldo Manual") }
    if(confirm)Confirmation("Simpan aturan keuangan?",if(rules.automaticFinance)"Saya sudah memverifikasi aturan berikut: ${rules.financeText()}. Berlaku untuk draf laporan lama yang sedang dibuka." else "Saldo Akhir akan diisi manual pada draf laporan lama yang sedang dibuka.","Setujui & Simpan",{ vm.saveRules(rules.copy(acknowledged=rules.automaticFinance));confirm=false },{ confirm=false })
}
@Composable internal fun SavedOutlets(s:Settings,vm:LhsViewModel) {
    var search by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<SavedOutlet?>(null) }
    Text("Daftar Outlet",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Panel("Tambah Outlet") {
        TextInput("Nama Outlet",name) { name=it };TextInput("Alamat",address,true) { address=it }
        Button(onClick={ if(name.isBlank())vm.error("Isi nama outlet.") else { vm.saveOutlet(Outlet(name=name,address=address));name="";address="" } }) { Text("Tambahkan") }
    }
    TextInput("Cari outlet tersimpan",search) { search=it }
    s.outlets.filter { it.name.contains(search,true)||it.address.contains(search,true) }.forEach { o->Panel(o.name) {
        Text(o.address)
        Row { TextButton(onClick={ edit=o }) { Text("Edit") };ConfirmButton("Hapus","Hapus ${o.name} dari daftar outlet tersimpan? Transaksi laporan tidak ikut dihapus.") { vm.settings { it.copy(outlets=it.outlets-o) } } }
    } }
    edit?.let { original->
        var editedName by remember(original) { mutableStateOf(original.name) };var editedAddress by remember(original) { mutableStateOf(original.address) }
        AlertDialog(onDismissRequest={ edit=null },title={ Text("Edit Outlet") },text={ Column { TextInput("Nama Outlet",editedName) { editedName=it };TextInput("Alamat",editedAddress,true) { editedAddress=it } } },confirmButton={ TextButton(onClick={ if(editedName.isNotBlank()) { vm.settings { it.copy(outlets=it.outlets.map { row->if(row==original)SavedOutlet(editedName,editedAddress) else row }) };edit=null } }) { Text("Simpan Perubahan") } },dismissButton={ TextButton(onClick={ edit=null }) { Text("Batal") } })
    }
}
