# Verifikasi LHS Papua 1.2.0 — Stage 3

Status: **implementasi source tersedia, verifikasi Android dan APK rilis masih terhambat**. Jangan menyebut versi ini telah lulus uji UX atau siap produksi.

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

Tes yang **sudah ditulis dan dikompilasi, belum dijalankan**: UiSmokeTest (master outlet/resume draf, 10 outlet/edit/hapus, harian tiga produk/tester/keuangan, fokus IME/paste/kursor, layar 360×640/font 130%/inset IME sintetis), DraftRecoveryTest (penyimpanan cepat, pemulihan jurnal, perlindungan stok manual), PersistenceTest (Room/backup/data lama), PreviewShareTest (viewport dan content URI), serta NativeFlowTest untuk perangkat/emulator. Tidak ada screenshot UI baru yang berhasil dibuat. Tidak ada emulator/perangkat fisik tersedia dan tidak ada klaim pengujian Samsung, instalasi upgrade atau proses aplikasi benar-benar dimatikan.

## PDF dan Excel

| Formulir | PDF MediaBox (pt) | Excel |
|---|---|---|
| Callsheet | 841.8898 × 595.2756 | A4 landscape, Print Area, fit width |
| Harian | 595.2756 × 841.8898 | A4 portrait, Print Area, fit width |

Delapan tes ekspor lulus. Callsheet 1/30/31/61 outlet, tiga produk, 30 baris per halaman, jumlah, teks `=1+1` aman, nama/alamat panjang, tanpa halaman kosong tambahan diuji. Workbook dibuka kembali di Apache POI dan nilai input diubah: SUM Callsheet, subtotal, total produk/penjualan, Jual, TG, TG Tester dan saldo ikut dihitung ulang. Stok awal pada sheet tersembunyi `_StokAwal`. Nilai cache dibandingkan dengan domain Android. PDF dirender dengan Poppler dan diperiksa visual: bagian utama ada, margin aman, tanpa potongan tabel pada sampel. Source grid/PDF/Excel tetap sama dengan baseline. Tidak ada klaim identik piksel dengan foto golden reference atau pengujian langsung Microsoft Excel/WPS pada Stage 3.

## Data dan kompatibilitas

Database.kt dan schema SQL version 1 tidak berubah. Tidak ada destructive migration. CallReport mendapat field JSON tambahan `completed=false` untuk membedakan draf/selesai; payload lama dapat memakai default. Calculation.kt dan StockLedger.kt tidak berubah. Optimasi Repository.saveDay hanya menyaring payload yang tidak berubah sebelum upsert, masih di dalam transaksi yang sama. Tes Room/pemulihan sesungguhnya harus dijalankan saat dependency tersedia; hasil lama Stage 2 tidak dianggap hasil Stage 3.

## Release dan kendala yang tersisa

Build release lokal offline gagal pada `mergeReleaseNativeLibs` karena tiga dependency runtime belum tersimpan: `androidx.lifecycle:lifecycle-common-java8:2.8.7`, `androidx.collection:collection-ktx:1.5.0`, `com.google.guava:listenablefuture:1.0`. Tidak ada APK 1.2.0 yang berhasil dibuat, tidak ada signature APK baru yang diverifikasi, dan tidak ada tautan APK palsu. Konfigurasi memakai keystore yang sama, tidak mengganti signing identity atau empat nama Secrets.

Satu workflow lama tetap `workflow_dispatch`, tanpa pemicu push, matrix atau workflow baru. Nama artifact diperbarui untuk versi 1.2.0. **Tidak ada GitHub Actions run Stage 3 yang dipicu.** Konektor tersedia hanya menyediakan pembacaan dan rerun run/job lama, tanpa dispatch workflow baru atau upload artifact. Rerun commit lama tidak membuktikan source baru sehingga tidak digunakan.

Langkah berikut yang diperlukan: jalankan workflow manual pada `main` setelah commit Stage 3 tersedia. Job menjalankan seluruh `testReleaseUnitTest` sebelum `assembleRelease`, memverifikasi signature dan hanya mengunggah APK jika berhasil. Jika akses dispatch tersedia kembali, pekerjaan dilanjutkan dari source ini. Sampai seluruh tes Android dan build lulus, kriteria penyelesaian pengguna **belum terpenuhi**.
