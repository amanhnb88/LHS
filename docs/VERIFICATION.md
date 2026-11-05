# Verifikasi LHS Papua 1.0.0

Repository `amanhnb88/LHS`, branch `main`. Baseline awal `2804fcf8059875f7d3c92f1e38d2191fca392edd` hanya berisi README. Implementasi ini tidak membuat branch, PR, fork, atau workflow otomatis pada push.

## Implementasi

- Kotlin native, Compose Material 3, Room, ViewModel/StateFlow dan Coroutines. Package `id.lhspapua`, versi 1.0.0 (1), Android minimal 8.0/API 26.
- Callsheet dan Laporan Harian memakai tabel database serta ID independen, dengan profil sales sebagai isian awal identitas saja.
- Tiga produk tetap: MARTIL 12, MARTIL, HD LIGHT. Harga G/SG/RTL dan aturan rumus disimpan sebagai snapshot pada tiap Laporan Harian.
- Input vertikal, outlet tersimpan, pencarian, riwayat, edit, autosave, konfirmasi hapus, tanggal yang dapat diganti, tester, keuangan, lampiran, backup/pemulihan ZIP.
- PDF vektor dan XLSX OOXML asli, ekspor terpisah, SAF untuk menyimpan dan content URI/FileProvider untuk Sharesheet. Tidak ada izin internet, login atau backend.
- Draf outlet yang sama sekali belum diisi tidak dimasukkan ke ekspor; draf dengan sebagian data tetap divalidasi. Teks yang melebihi ruang pada 6 pt diberi penjelasan untuk dipendekkan, tanpa pemotongan diam-diam.

## Pengujian otomatis

`testReleaseUnitTest` menjalankan 17 pengujian, 0 gagal, 0 dilewati:

| Area | Pengujian | Hasil |
|---|---:|---|
| Domain perhitungan | 8 | Lulus |
| Ekspor PDF/XLSX | 5 | Lulus |
| Penyimpanan dan backup | 3 | Lulus |
| Alur Compose native | 1 | Lulus |

Contoh yang diuji per produk: DO 100, G 10 × 10.000, SG 5 × 11.000, RTL 2 × 12.000, Sample 2, Bns 1 menghasilkan Jual 17, total Rp179.000 dan TG 80. Tiga produk menghasilkan Rp537.000; saldo sebelumnya 50.000, piutang 20.000, pelunasan 5.000, transfer 100.000 dan pengeluaran 10.000 menghasilkan saldo akhir Rp462.000.

Kasus lain mencakup kosong versus 0, harga belum diatur, angka 15 digit/overflow, stok tidak mencukupi, TG manual dan aturan yang diubah, snapshot harga, Callsheet 1/30/31/61 outlet, nama/alamat panjang, teks outlet `=1+1` yang tetap teks, buka ulang database, pemisahan kedua laporan, penggantian isi backup, lampiran, serta penolakan path traversal pada ZIP.

Pengujian native menggunakan Robolectric: API 28 untuk Room/backup dan API 34 untuk membuka Callsheet, mengisi outlet, kembali ke daftar, membuka Laporan Harian, Tester dan Keuangan. `NativeFlowTest` juga disediakan untuk perangkat melalui `connectedDebugAndroidTest`, tetapi belum dijalankan pada perangkat fisik.

## Verifikasi ekspor

| Formulir | PDF MediaBox (pt) | Excel |
|---|---|---|
| Callsheet | 841.8898 × 595.2756 | A4 landscape, Print Area, fit 1 halaman per sheet |
| Laporan Harian | 595.2756 × 841.8898 | A4 portrait, Print Area, fit 1 halaman |

PDF diperiksa dengan PDFBox dan dirender menggunakan Poppler. Visual diperiksa terhadap dua gambar final di `docs/references/`. Ketiga produk, 30 baris Callsheet, stok/jual, stock/penjualan, tester, keuangan, Keterangan bergaris, pemisah dan Dibuat Oleh dipertahankan. Margin sekitar 10 mm. Callsheet tambahan menggunakan halaman/sheet baru; baris JUMLAH pada setiap halaman menjumlahkan halaman tersebut, sementara ringkasan Android menjumlahkan seluruh outlet.

XLSX dibuka ulang dan dihitung dengan Apache POI. Perubahan jumlah/harga pada cell input diuji untuk memperbarui subtotal, total produk, total penjualan, Jual, TG, TG Tester dan Saldo Akhir. Merged cells dan normal style diperiksa, lebar seluruh kolom positif, `paperSize=9`, orientasi/Print Area/fit-to-page dan force recalculation diverifikasi. Workbook juga diimpor serta dirender dengan mesin spreadsheet independen; tidak ditemukan formula error. Pemisah ribuan tampilan Excel mengikuti locale aplikasi spreadsheet; nilai Rupiah tetap numerik bulat dengan format `"Rp "#,##0`.

## Build, signing dan GitHub Actions

Build lokal `testReleaseUnitTest assembleRelease` dan lint vital release lulus. APK ditandatangani RSA 3072 dengan APK Signature Scheme v2 dan diverifikasi menggunakan Android `apksigner`. Kunci dan password tidak disimpan di repository. Cadangan signing diberikan terpisah secara pribadi.

Sertifikat SHA-256: `ba6db11cb0fdc995bd288463cc2596951ca8566775c926d7acccd2cf16b4a6b7`.

Satu workflow `android-release.yml`, hanya `workflow_dispatch`, satu job, tanpa matrix, cache Gradle, concurrency dan timeout 25 menit. Tidak ada GitHub Actions yang dijalankan dalam pekerjaan ini: 0 run/0 menit. Konektor tidak menyediakan pembuatan Secrets maupun dispatch baru; sesi browser yang tersedia juga tidak memiliki akses repository ini. Empat Secrets belum dibuat. Untuk build Actions berikutnya, pemilik perlu memasang `LHS_KEYSTORE_BASE64`, `LHS_KEYSTORE_PASSWORD`, `LHS_KEY_ALIAS`, `LHS_KEY_PASSWORD` dari arsip recovery; script `scripts/configure-signing-secrets.py` tersedia untuk GitHub CLI. Karena itu belum ada artifact GitHub Actions; APK dikirim sebagai file unduhan langsung.

## Batas verifikasi

Belum dilakukan instalasi pada HP fisik, pemanggilan pemilih dokumen/Sharesheet pada HP, pengiriman nyata ke WhatsApp/Gmail, atau pembukaan file langsung di Microsoft Excel/WPS. Uji native yang lulus adalah simulasi Robolectric, bukan bukti pengujian seluruh alur pada perangkat pengguna. Asumsi TG/keuangan tetap memerlukan verifikasi perusahaan sebelum ekspor final; aplikasi meminta konfirmasi aturan tersebut.

APK final: 7,369,583 byte. SHA-256: `283a7765ddf244fd49df09bf0ea53d12d97a836ecc57733ad965b00efea63e3f`.
