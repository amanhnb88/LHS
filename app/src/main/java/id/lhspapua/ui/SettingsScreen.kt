@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package id.lhspapua.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.lhspapua.domain.*

@Composable internal fun SettingsScreen(section:String,select:(String)->Unit,state:UiState,vm:LhsViewModel,backup:()->Unit,restore:()->Unit) {
    when(section) {
        "" -> {
            listOf(Triple("profil","Profil Sales","Identitas yang sering dipakai."),Triple("harga","Harga Produk","Harga Grosir, Sub Grosir, dan Retail."),Triple("outlet","Daftar Outlet","Nama dan alamat outlet tersimpan."),Triple("backup","Backup Data","Simpan salinan laporan dan lampiran."),Triple("lanjutan","Pengaturan Lanjutan","Aturan keuangan dan penjelasan stok.")).forEach { (key,title,description)->MainCard(title,description) { select(key) } }
        }
        "profil" -> {
            Text("Profil Sales",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            IdentityEditor(state.settings.profile,profile=true) { i->vm.settings { it.copy(profile=i.copy(stockOwnerId=if(i.sales.trim().equals(it.profile.sales.trim(),true))i.stockOwnerId else "")) } }
            Text("Profil digunakan sebagai isian awal. Laporan yang sudah tersimpan tetap memakai identitasnya sendiri.",style=MaterialTheme.typography.bodySmall)
        }
        "harga" -> {
            var product by rememberSaveable { mutableIntStateOf(0) }
            Text("Harga Produk",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Notice("Berlaku untuk laporan baru. Harga pada laporan lama tidak berubah.")
            ProductTabs(product) { product=it }
            Panel(Product.entries[product].label) {
                Tier.entries.forEachIndexed { i,_ ->
                    val title=listOf("Grosir (G)","Sub Grosir (SG)","Retail (RTL)")[i]
                    Numeric("Harga $title",state.settings.prices[product][i],money=true) { n ->
                        vm.settings { settings -> settings.copy(prices=settings.prices.replace(product,settings.prices[product].replace(i,n))) }
                    }
                }
            }
        }
        "outlet" -> SavedOutlets(state.settings,vm)
        "backup" -> {
            Text("Backup Data",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Backup mencakup laporan, harga, profil, daftar outlet, stok antarhari, dan seluruh lampiran. Simpan secara berkala sebelum mengganti HP atau menghapus data aplikasi.")
            Button(onClick=backup,modifier=Modifier.fillMaxWidth()) { Text("Simpan Backup ZIP") }
            OutlinedButton(onClick=restore,modifier=Modifier.fillMaxWidth()) { Text("Pulihkan Backup ZIP") }
            ConfirmButton("Bersihkan lampiran tidak terpakai","Hapus hanya file lampiran yang sudah tidak dipakai oleh laporan?") { vm.cleanup() }
        }
        "lanjutan" -> AdvancedSettings(state.settings.rules,vm)
    }
}
@Composable internal fun AdvancedSettings(current:Rules,vm:LhsViewModel) {
    var rules by remember(current) { mutableStateOf(current) }
    var confirm by remember { mutableStateOf(false) }
    Text("Pengaturan Lanjutan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Notice("DO = barang baru dari gudang. TG = Tutup Ganti / Sisa Stok Akhir. Stok tersedia = Stok Awal + DO. TG = Stok Awal + DO − Jual − Sampel − Bonus. Stok tester dihitung sendiri.")
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
    if(confirm)Confirmation("Simpan aturan keuangan?",if(rules.automaticFinance)"Saya sudah memverifikasi aturan berikut: ${rules.financeText()}. Berlaku untuk laporan baru dan draf yang sedang dibuka. Laporan final lain tetap memakai aturannya sendiri." else "Saldo Akhir akan diisi manual pada laporan baru dan draf yang sedang dibuka. Nilai laporan final lain dipertahankan.","Setujui & Simpan",{ vm.saveRules(rules.copy(acknowledged=rules.automaticFinance));confirm=false },{ confirm=false })
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
