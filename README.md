# LHS Papua

Aplikasi Android native Kotlin / Jetpack Compose untuk sales Papua. Android 8.0 (API 26) atau lebih baru. Seluruh data lokal, tanpa login, server, WebView atau izin internet.

## Penggunaan versi 1.2.0

Beranda memuat dua pilihan laporan. Mengetuk jenis laporan membuka daftar draf, bukan membuat laporan kosong. Pilih **Lanjutkan** atau **Buat Laporan Baru**. Draf terakhir juga tersedia langsung di beranda. Perubahan disimpan otomatis. Status **Tersimpan** muncul setelah antrean selesai. Snapshot input terbaru dijurnal untuk pemulihan jika proses berhenti sebelum Room selesai menulis.

- **Callsheet:** identitas → cari nama/alamat outlet langsung → pilih saran atau tambah outlet baru → tiga baris Stok/Jual → **Simpan & Tambah Outlet**. Outlet baru hanya menyalin nama dan alamat, tanpa transaksi lama. Untuk kunjungan yang sudah dicatat gunakan **Simpan Perubahan**. **Daftar Kunjungan** menyediakan pencarian, edit dan hapus dengan konfirmasi. **Periksa Laporan** menampilkan total dan ekspor.
- **Laporan Harian:** identitas ringkas dan tiga bagian **Barang, Tester, Keuangan**. Dalam Barang, buka produk untuk mengisi stok dan penjualan sekaligus. Tidak perlu membuka produk yang sama pada langkah stok lalu penjualan terpisah. Stok awal tanpa sumber langsung bisa diisi. Tester menampilkan ketiga produk bersama dan mempunyai stok sendiri. **Tidak Ada — Isi Kolom/Jumlah Kosong 0** adalah tindakan eksplisit, bukan pengisian nol diam-diam.
- **Pemeriksaan:** kesalahan membuka bagian/produk/field terkait. Harga yang belum diatur mempunyai tindakan langsung. PDF dan Excel selalu terlihat dengan aksi tetap untuk pratinjau, simpan dan bagikan. Distribusi final tersedia sesudah laporan lengkap dan diselesaikan. Laporan harian final harus dibuka kembali dengan konfirmasi sebelum diedit.
- **Pratinjau PDF:** dibuka sebagai halaman utuh, mendukung lebar layar, perbesar/perkecil, pinch/pan dan reset. Navigasi halaman muncul untuk dokumen banyak halaman. Angka zoom menunjukkan kelipatan tampilan halaman utuh, bukan ukuran cetak.
- **Pengaturan:** Profil Sales, Harga Produk, Daftar Outlet, Backup Data, dan Pengaturan Lanjutan. Harga dan aturan tetap snapshot per laporan. Lampiran disalin ke penyimpanan internal dan dibagikan sebagai file terpisah.

Callsheet dan Laporan Harian mempunyai identitas, transaksi, riwayat dan ekspor sendiri. Tidak ada pengambilan transaksi atau stok dari Callsheet ke Laporan Harian.

## Perhitungan dan stok antarhari

Satu domain calculation layer (`domain/Calculation.kt`), integer Long. Input dan hasil dibatasi 15 digit agar tetap tepat di Excel. Kosong berbeda dari 0; harga kosong tidak dianggap Rp0. Angka terlalu besar, stok negatif dan input belum lengkap menghalangi finalisasi.

- **DO adalah barang baru dari gudang**, bukan stok tersedia. **TG adalah Tutup Ganti / Sisa Stok Akhir.**
- Stok tersedia = Stok Awal + DO. Jual = G + SG + RTL. TG = Stok Awal + DO − Jual − Sample − Bns.
- Tester mempunyai ledger sendiri: TG Tester = Stok Awal Tester + DO Tester − Sampel Tester.
- Subtotal kategori = jumlah × harga; Total Produk = seluruh subtotal produk; Total Penjualan = seluruh produk.
- Rumus awal saldo = Saldo Sebelum + Total Penjualan − Piutang + Pelunasan − Transfer − Pengeluaran. Arti komponen serta arahnya harus disetujui di Pengaturan Lanjutan sebelum finalisasi, atau pilih Saldo Akhir manual yang tervalidasi. Aturan dan harga disimpan per laporan.

`domain/StockLedger.kt` mencari laporan **final valid terakhir** sebelum tanggal/urutan aktif dengan ID pemilik stok yang sama. Hari tanpa laporan boleh dilewati. Urutan pada tanggal sama eksplisit dan duplikat ditolak. Draf biasa dan laporan versi lama bukan sumber stok. Jika belum ada sumber, Stok Awal barang jual dan tester tetap kosong sampai pengguna mencatatnya; tidak diubah menjadi nol secara otomatis.

Perubahan sumber menghitung ulang draf terkait dan memberi pemberitahuan. Angka laporan final terkait dibekukan dan ditandai untuk diperiksa, termasuk rantai sesudahnya. Sumber yang dibuka kembali menghalangi penggunaan stok sampai difinalisasi ulang. Penyesuaian manual Stok Awal memerlukan konfirmasi dan tersimpan bersama laporan.

## Kompatibilitas data

Application ID tetap `id.lhspapua`; versi 1.2.0 memakai versionCode 3 dan kunci penandatanganan yang sama. SQL Room tetap schema version 1, tanpa destructive migration. Kolom baru bersifat tambahan pada payload JSON dengan nilai default aman. Laporan lama mempertahankan ID, transaksi, harga, aturan, lampiran dan hasil rumus lamanya. Konversi ke pencatatan Stok Awal hanya melalui tindakan **Lengkapi Stok Awal Laporan Lama** dan konfirmasi.

Backup versi 2 mempertahankan sumber, urutan, pembukaan stok, status final dan lampiran. Restore menerima versi 1 dan 2, memvalidasi sebelum transaksi, lalu merekonsiliasi seluruh laporan bersama. Simpan backup sebelum mengganti HP atau menghapus data aplikasi.

## Ekspor

`export/Layout` menghasilkan satu grid fisik untuk PDF vektor dan XLSX OOXML asli. Callsheet A4 landscape, LHS A4 portrait, margin 10 mm. Merged cells, print area, A4, orientasi, angka bertipe numerik, formula aktif dan force recalculation. Stok Awal internal berada pada worksheet tersembunyi `_StokAwal`, bukan kolom baru di formulir resmi. Rumus TG merujuk worksheet tersebut; saldo manual disimpan sebagai angka, bukan rumus yang belum disetujui. Input teks selalu inline string (aman dari formula injection). Nilai formula menggunakan cache dari domain yang sama. Tidak menggunakan POI di APK; POI hanya untuk memverifikasi formula saat unit test. Setiap halaman Callsheet mempunyai JUMLAH halaman tersebut; ringkasan Android memuat total seluruh outlet. Teks yang tidak muat bahkan pada 6 pt ditolak dengan pesan agar dipendekkan, bukan dipotong diam-diam.

## Build

JDK 17, Android SDK 35, Gradle wrapper 8.11.1, AGP 8.9.2, Kotlin/Compose compiler 2.1.20.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Release memerlukan environment `LHS_KEYSTORE_PATH`, `LHS_KEYSTORE_PASSWORD`, `LHS_KEY_ALIAS`, `LHS_KEY_PASSWORD`:

```sh
./gradlew testReleaseUnitTest assembleRelease
```

Satu workflow manual `.github/workflows/android-release.yml`, tanpa push trigger, matrix, atau job ganda. Empat GitHub Secrets: `LHS_KEYSTORE_BASE64`, `LHS_KEYSTORE_PASSWORD`, `LHS_KEY_ALIAS`, `LHS_KEY_PASSWORD`. Jangan commit keystore/password. Gunakan kunci yang sama untuk semua pembaruan APK.

Lihat `docs/VERIFICATION.md` untuk bukti pengujian dan batas verifikasi. Tidak ada klaim pengujian perangkat fisik jika belum dijalankan.

Jika Secrets belum dipasang melalui konektor, ekstrak arsip signing recovery yang diberikan secara pribadi. Dengan GitHub CLI yang sudah login sebagai `amanhnb88`, jalankan:

```sh
python3 scripts/configure-signing-secrets.py /lokasi/signing-recovery.json
```

Script hanya mengisi empat Secrets di `amanhnb88/LHS`, tidak memicu build dan tidak mencetak nilainya. Alternatifnya, isi empat Secrets melalui Settings → Secrets and variables → Actions menggunakan data di arsip tersebut.

Draf outlet yang sama sekali belum diisi tidak dicetak; isian parsial tetap muncul untuk diperiksa. Hasil perhitungan dan status verifikasi release dirangkum di `docs/VERIFICATION.md`.
