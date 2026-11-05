# Verifikasi LHS Papua 1.2.0 — Stage 3

Status: **build GitHub pada 428e6fe gagal pada empat timeout tes UI; perbaikan harness sudah dikompilasi dan menunggu verifikasi workflow terbaru**. Jangan menyebut APK rilis berhasil sebelum artifact dan signature tersedia.

Baseline aktual yang diaudit: `71c26846cbbfa5d45cf00743a0b39c2ea48f2b35`, `main`. Application ID tetap `id.lhspapua`, versionCode 3, versionName 1.2.0. Source Android yang ada dilanjutkan tanpa branch, PR, reset atau perubahan signing. Audit timestamp dan akar penyebab ada di [STAGE3_AUDIT.md](STAGE3_AUDIT.md). Laporan Stage 2 dipertahankan sebagai [VERIFICATION_STAGE2.md](VERIFICATION_STAGE2.md); hasilnya bukan bukti pengujian Stage 3.

## Perubahan yang sudah ditulis

| Area | Sebelum | Implementasi Stage 3 |
|---|---|---|
| Beranda | Menu membuat UUID/laporan baru | Menu membuka hub tanpa menulis laporan. Lanjutkan draf, daftar beberapa draf dan Buat Laporan Baru eksplisit |
| Callsheet | Cari hanya kunjungan laporan aktif, master di picker lain | Cari nama/alamat dari kunjungan dan outlet tersimpan. Saran langsung, tambah outlet baru dari pencarian, tanpa salin stok/jual |
| Simpan kunjungan | Satu label untuk tambah/edit | Simpan & Tambah Outlet untuk baru, Simpan Perubahan untuk edit, daftar ringkas dan konfirmasi hapus |
| Harian | Lima langkah, produk dikunjungi pada stok lalu penjualan lalu tester | Identitas ringkas. Tiga bagian Barang/Tester/Keuangan. Satu editor stok dan penjualan per produk. Ketiga tester bersama |
| Stok awal | Read-only AUTO walaupun sumber tidak ada | Tanpa sumber langsung dapat diisi. Peralihan AUTO tanpa sumber ditolak dan angka manual dipertahankan |
| Validasi | Dialog Informasi berisi daftar panjang | Pesan field dan tindakan yang membuka bagian/produk/harga terkait |
| Keyboard | Inset root dan anak diterapkan lagi | Inset Scaffold dikonsumsi sekali, footer ringkas saat IME, Next/Done, bring-into-view |
| Ekspor | Satu tombol berganti peran | PDF dan Excel terlihat, aksi pratinjau/simpan/bagikan tetap. Finalisasi terpisah |
| Autosave | Setiap ketukan mengantre write Room | Snapshot terbaru per ID digabung. Jurnal pemulihan sebelum write. Barrier saat pindah/background/operasi lanjutan. Repository menulis hanya laporan yang berubah |

Perbandingan struktural, bukan hasil pengukuran waktu di HP: stok dan penjualan satu produk sekarang cukup satu pembukaan editor, sebelumnya dua tahap/tab terpisah. Tester tidak memerlukan pemilihan tiga tab. Mencari master outlet tidak memerlukan Pengaturan atau picker terpisah. Belum ada klaim jumlah ketukan atau kecepatan tugas lapangan yang telah diukur.

## Hasil yang benar-benar diuji

Kompilasi `compileReleaseKotlin` **berhasil**. `compileReleaseUnitTestKotlin` juga **berhasil** setelah anotasi API pengujian ditambahkan.

Dependensi runtime pengujian belum seluruhnya tersimpan. Unduhan `https://dl.google.com:443` diblokir oleh kebijakan jaringan lingkungan. `testReleaseUnitTest --offline` gagal sebelum menjalankan suite karena dependency tidak tersedia. Tidak ada hasil UI/Robolectric Stage 3 yang boleh dinyatakan lulus.

Sebagai pemeriksaan lokal tanpa jaringan, kelas hasil kompilasi dijalankan melalui JUnitCore dengan JAR yang tersedia. **27 tes lulus, 0 gagal**:

| Kelas | Tes | Hasil |
|---|---:|---|
| CalculationTest | 8 | Lulus |
| StockLedgerTest | 6 | Lulus |
| ExportTest | 5 | Lulus |
| Stage2ExportTest | 3 | Lulus |
| Stage3LogicTest | 5 | Lulus |

Cakupan: 50 + 200 − 150 − 10 − 5 = TG **85**; hari berikutnya 85 + 100 − 120 − 5 = **60**. Tester **12**, kemudian **11**. Hari tanpa laporan, pemilik terpisah, draf bukan sumber, urutan ganda, perubahan sumber dan final terkait tetap dibekukan/ditandai. Harga snapshot, bilangan besar/overflow, kosong berbeda dari nol dan harga belum diatur diperiksa. Pencarian Toko Aimas dari master tanpa kunjungan ditemukan; transaksi kunjungan baru tetap kosong. Parser Rupiah, posisi kursor visual, penggabungan 200 snapshot dan target validasi diuji.

Build GitHub [42065701932](https://github.com/amanhnb88/LHS/actions/runs/42065701932), commit `428e6fed1326fefef4837fefe4865d822eeaebe5`, sudah menjalankan **42 tes: 38 lulus dan 4 gagal**. Ini memperbarui keterbatasan pemeriksaan lokal di atas.

| Area CI | Hasil pada 428e6fe |
|---|---|
| 27 tes kalkulasi, ledger, ekspor dan logika baru | Lulus |
| 5 tes Room/kompatibilitas/backup | Lulus |
| 3 tes penggabungan write, pemulihan jurnal dan penjagaan stok manual | Lulus |
| 2 tes matematika viewport dan URI Sharesheet | Lulus |
| Callsheet 10 outlet, edit, validasi dan hapus | Lulus |
| Pencarian master/resume, fokus/paste, harian, layar kecil/font/IME | Timeout pada persiapan atau menunggu simpan, belum lulus |

Tiga kegagalan berhenti di `UiSmokeTest.kt:35` menunggu `ready`; satu di baris 40 menunggu `pending/busy`. Log tidak membuktikan kegagalan rumus atau signing. `validateSigningRelease` dan pemeriksaan empat Secrets telah lolos, tetapi pemeriksaan signature APK dan upload APK dilewati karena suite gagal.

Perbaikan harness: polling state disinkronkan melalui `compose.runOnIdle` supaya antrean main Android/Robolectric ikut diproses, bukan hanya membaca StateFlow. Setiap skenario memakai instance Room terpisah dan ViewModel-nya dibatalkan sesudah tes untuk mencegah sisa pekerjaan/singleton antar skenario. Timeout tetap 20 detik dan semua assertion dipertahankan. Pesan kegagalan berikutnya mencakup ready/pending/busy/status/error sehingga tidak berhenti pada timeout tanpa konteks. Workflow yang sama sekarang menyimpan laporan JUnit/HTML dan capture UI ketika tersedia, termasuk saat gagal.

`compileReleaseUnitTestKotlin --offline` setelah perbaikan **berhasil**. Hasil runtime harness yang diperbaiki belum tersedia. NativeFlowTest untuk perangkat/emulator belum dijalankan. Tidak ada emulator/perangkat fisik tersedia dan tidak ada klaim pengujian Samsung atau instalasi upgrade.

## PDF dan Excel

| Formulir | PDF MediaBox (pt) | Excel |
|---|---|---|
| Callsheet | 841.8898 × 595.2756 | A4 landscape, Print Area, fit width |
| Harian | 595.2756 × 841.8898 | A4 portrait, Print Area, fit width |

Delapan tes ekspor lulus. Callsheet 1/30/31/61 outlet, tiga produk, 30 baris per halaman, jumlah, teks `=1+1` aman, nama/alamat panjang, tanpa halaman kosong tambahan diuji. Workbook dibuka kembali di Apache POI dan nilai input diubah: SUM Callsheet, subtotal, total produk/penjualan, Jual, TG, TG Tester dan saldo ikut dihitung ulang. Stok awal pada sheet tersembunyi `_StokAwal`. Nilai cache dibandingkan dengan domain Android. PDF dirender dengan Poppler dan diperiksa visual: bagian utama ada, margin aman, tanpa potongan tabel pada sampel. Source grid/PDF/Excel tetap sama dengan baseline. Tidak ada klaim identik piksel dengan foto golden reference atau pengujian langsung Microsoft Excel/WPS pada Stage 3.

## Data dan kompatibilitas

Database.kt dan schema SQL version 1 tidak berubah. Tidak ada destructive migration. CallReport mendapat field JSON tambahan `completed=false` untuk membedakan draf/selesai; payload lama dapat memakai default. Calculation.kt dan StockLedger.kt tidak berubah. Optimasi Repository.saveDay hanya menyaring payload yang tidak berubah sebelum upsert, masih di dalam transaksi yang sama. Tes Room dan pemulihan jurnal lulus pada run Stage 3 di atas; hasil lama Stage 2 tidak digunakan sebagai bukti pengujian baru.

## Release dan kendala yang tersisa

Build release lokal offline gagal pada `mergeReleaseNativeLibs` karena tiga dependency runtime belum tersimpan: `androidx.lifecycle:lifecycle-common-java8:2.8.7`, `androidx.collection:collection-ktx:1.5.0`, `com.google.guava:listenablefuture:1.0`. Tidak ada APK 1.2.0 yang berhasil dibuat, tidak ada signature APK baru yang diverifikasi, dan tidak ada tautan APK palsu. Konfigurasi memakai keystore yang sama, tidak mengganti signing identity atau empat nama Secrets.

Satu workflow lama tetap `workflow_dispatch`, tanpa pemicu push, matrix atau workflow baru. Build Stage 3 pertama telah dijalankan pengguna dan gagal pada empat timeout UI yang dijelaskan di atas. Tidak ada APK atau artifact rilis dari run tersebut. Perbaikan harness tidak mengubah kode bisnis, database produksi, layout ekspor, versi aplikasi atau signing.

Konektor tersedia hanya menyediakan pembacaan dan rerun run/job lama, tanpa dispatch workflow baru. Rerun commit lama tetap memakai source lama sehingga tidak digunakan. Setelah commit perbaikan tersimpan, pilih **Run workflow → main** pada [workflow release](https://github.com/amanhnb88/LHS/actions/workflows/android-release.yml). Job tetap menjalankan seluruh tes sebelum menerima APK rilis; pemeriksaan tidak dilewati. Sampai seluruh suite dan build lulus, kriteria penyelesaian pengguna **belum terpenuhi**.
