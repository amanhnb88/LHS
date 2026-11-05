# Verifikasi LHS Papua 1.1.0 — Stage 2

Repository `amanhnb88/LHS`, branch `main`, baseline `a59e4551fb2565296e13b42a4932ada2c3d88115`. Proyek Android yang sudah ada dilanjutkan; tidak ada branch/PR/fork atau workflow baru. Application ID tetap `id.lhspapua`, versionCode 2, versionName 1.1.0, minimum Android 8.0/API 26.

## Perubahan alur

| Sebelum | Sesudah |
|---|---|
| Enam kotak jumlah outlet disusun vertikal | Tiga baris produk, masing-masing Stok/Jual berdampingan |
| Tombol Simpan bertumpuk dengan ringkasan | Autosave + satu tombol utama Simpan & Outlet Berikutnya; ringkasan dibuka setelah input |
| Seluruh produk harian berupa formulir panjang | Lima langkah, tiga tab produk, total langsung terlihat |
| Pengaturan rumus menonjol saat input | Pengaturan Lanjutan terpisah; verifikasi keuangan sekali atau saldo manual |
| Pratinjau dokumen eksternal tanpa kontrol viewport | Dialog native dengan halaman utuh, lebar layar, zoom/pan/reset dan pergantian halaman |

Callsheet: identitas → tambah/pilih outlet → tiga baris produk → outlet berikutnya → daftar/edit/cari/hapus dengan konfirmasi → ringkasan → PDF/Excel. Outlet kosong yang disediakan untuk kunjungan berikutnya dipakai ulang agar nomor tidak meloncat. Nama/alamat tersimpan tidak menyalin jumlah transaksi lama.

Harian: Informasi Harian → Stok & Barang Masuk → Penjualan → Tester → Keuangan → Periksa Laporan → Selesaikan Laporan → ekspor. Back mempertahankan input; langkah aktif dipulihkan. Laporan final harus dibuka kembali dengan konfirmasi untuk diedit. Callsheet dan harian tetap independen.

## Pengujian otomatis

`testReleaseUnitTest` menjalankan **31 pengujian, 0 gagal, 0 dilewati**:

| Area | Jumlah |
|---|---:|
| Perhitungan dan regresi versi lama | 8 |
| Ledger stok antarhari | 6 |
| Ekspor PDF/Excel dan regresi | 8 |
| Room, kompatibilitas payload, backup/lampiran | 5 |
| Matematika viewport dan URI Sharesheet | 2 |
| Interaksi Compose native, Robolectric API 34 | 2 |

Tes Compose mencatat 10 outlet × tiga produk, mengedit penjualan, mencari outlet, memeriksa total, membatalkan lalu menyetujui hapus, memakai ulang outlet kosong dan berpindah formulir. Tes harian mengisi Stok Awal/DO ketiga produk, melewati lima langkah, mencatat tester terpisah, mengisi saldo, kembali dan memeriksa ringkasan. Tangkapan layar native simulasi diperiksa untuk input outlet, stok, penjualan, dan keuangan. Ini bukan pengujian pada HP fisik.

## Stok dan keuangan

- Contoh resmi: Stok Awal 50 + DO 200 − Jual 150 − Sample 10 − Bns 5 = **TG 85**.
- Laporan berikutnya (melewati hari tanpa laporan): Stok Awal otomatis 85 + DO 100 − Jual 120 − Sample 5 − Bns 0 = **TG 60**. Tetap benar setelah database ditutup dan dibuka kembali.
- Tester terpisah: awal 5 + DO 10 − sampel 3 = **12**; berikutnya 12 + DO 1 − sampel 2 = **11**.
- ID pemilik berbeda tidak berbagi stok. Draf biasa/laporan lama tidak menjadi sumber. Stok awal yang tidak diketahui tetap null. Tanggal sama memakai urutan eksplisit; urutan ganda menghalangi finalisasi.
- Perubahan sumber menghitung ulang draf. Angka final berikutnya tetap utuh dan ditandai; rantai yang bergantung pada final bermasalah tidak mengambil sumber lama secara diam-diam. Pembukaan ulang sumber menjadi penghalang sampai diperiksa/final kembali.
- Harga snapshot, kosong versus nol, stok negatif, harga belum diatur, batas integer 15 digit/overflow, laporan lama dan keuangan manual diuji.
- Rumus awal saldo masih memerlukan persetujuan perusahaan melalui Pengaturan Lanjutan. Arah piutang/pelunasan/transfer/pengeluaran terlihat dan dapat diatur. Saldo manual menggunakan angka yang divalidasi, tanpa formula keuangan yang diasumsikan terkonfirmasi.

## PDF dan Excel A4

| Formulir | PDF MediaBox (pt) | Excel |
|---|---|---|
| Callsheet | 841.8898 × 595.2756 | A4 landscape, Print Area, fit lebar 1 halaman |
| Laporan Harian | 595.2756 × 841.8898 | A4 portrait, Print Area, fit 1 halaman |

PDF vektor diperiksa dengan PDFBox dan dirender dengan Poppler; Excel dibuka ulang dan dihitung dengan Apache POI, kemudian diimpor/dihitung/dirender menggunakan mesin spreadsheet independen. Tidak ditemukan formula error pada sampel. Seluruh label, bentuk dan koordinat grid resmi tetap dipertahankan. Sampel visual dibandingkan dengan gambar final; margin sekitar 10 mm. Callsheet diuji 1/30/31/61 outlet, 30 baris per halaman, tanpa halaman kosong tambahan. JUMLAH per halaman menjumlahkan halaman tersebut, sedangkan ringkasan Android menjumlahkan semua outlet.

Rumus aktif Excel diuji dengan mengubah jumlah, harga, DO dan stok awal: subtotal, total produk, seluruh penjualan, Jual, TG, TG Tester, dan saldo otomatis yang disetujui ikut berubah. Stok Awal berada pada sheet tersembunyi `_StokAwal` yang tidak mempunyai Print Area; formulir resmi tidak mendapat kolom tambahan. Saldo manual berupa cell numerik. Teks `=1+1` tetap string aman. Nilai rupiah integer; pemisah ribuan tampilan Excel mengikuti locale aplikasi spreadsheet.

## Data lama dan backup

SQL Room tetap schema version 1; tidak diperlukan perubahan tabel dan tidak ada destructive migration. Field JSON baru mempunyai default kompatibel. Payload dengan field versi lama diuji setelah database ditutup/dibuka; ID, transaksi, harga dan hasil lama tetap sama. Laporan lama baru menggunakan pencatatan Stok Awal setelah tindakan konversi dengan konfirmasi, tanpa menghapus transaksi atau lampiran.

Backup versi 2 mempertahankan ledger, status final, sumber, revisi dan lampiran. Restore versi 1 tetap diterima. Round-trip kedua versi, pemisahan formulir, salinan lampiran dan penolakan ZIP path traversal diuji. Laporan final yang diimpor direkonsiliasi bersama seluruh sumbernya agar urutan isi backup tidak mengubah perhitungan.

## Release dan batas akses

Release memakai kunci lama; tidak ada keystore baru atau rahasia di source. Empat nama Secrets tetap `LHS_KEYSTORE_BASE64`, `LHS_KEYSTORE_PASSWORD`, `LHS_KEY_ALIAS`, `LHS_KEY_PASSWORD`.

Satu workflow yang sama, `android-release.yml`, tetap manual `workflow_dispatch`, satu job, cache Gradle, tanpa matrix/push trigger. Nama artifact disesuaikan menjadi `LHS-Papua-1.1.0-signed`. Build dan pengujian Stage 2 dilakukan lokal agar tidak memakai kuota Actions. Run GitHub terakhir yang sudah berhasil untuk versi sebelumnya: https://github.com/amanhnb88/LHS/actions/runs/42021535910 .

Konektor GitHub sesi ini tidak menyediakan operasi dispatch workflow baru atau upload artifact Actions. Tidak dilakukan rerun SHA lama. Untuk mendapatkan artifact Actions Stage 2, jalankan **Run workflow → main** pada workflow yang sama sesudah commit Stage 2. APK signed hasil build lokal disediakan langsung; jangan menyebut run versi lama sebagai build Stage 2.

Belum diuji di HP fisik/emulator penuh: pemasangan upgrade, interupsi telepon/proses Android, keyboard/nav bar pada perangkat pengguna, render/pinch native PdfRenderer, pemilih dokumen, pengiriman nyata WhatsApp/Gmail, serta pembukaan langsung di Excel/WPS. Geometri preview dan URI izin berbagi diuji otomatis. Robolectric tidak menyediakan render PDF native yang nyata pada konfigurasi ini; tes render dua halaman disediakan di `NativeFlowTest` untuk `connectedDebugAndroidTest`, belum dijalankan. Sertifikat/app ID/versionCode dan kompatibilitas database diperiksa; ini bukan klaim upgrade fisik sudah diuji.

## APK hasil build lokal

`testReleaseUnitTest assembleRelease` dan lint vital release **lulus**. Android `apksigner verify --verbose --print-certs` menyatakan signature v2 valid, satu signer RSA 3072. Sertifikat SHA-256 sama dengan versi 1.0.0: `ba6db11cb0fdc995bd288463cc2596951ca8566775c926d7acccd2cf16b4a6b7`. `aapt dump badging` memverifikasi package `id.lhspapua`, versi 1.1.0/code 2, launcher LHS Papua, minSdk 26 dan targetSdk 35. Tidak ada izin internet.

SHA-256 APK: `27ab07fe9a5b2f686eef3ca67fd95f9db096b3a9821d67d6acf1478aae701dc9`.
Ukuran APK: 7,451,503 byte.
