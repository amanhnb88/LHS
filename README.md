# LHS Papua

Aplikasi Android native Kotlin / Jetpack Compose untuk sales Papua. Android 8.0 (API 26) atau lebih baru. Seluruh data lokal, tanpa login, server, WebView atau izin internet.

## Penggunaan versi 1.2.3

Tab bawah menyediakan **Beranda**, **Callsheet**, **LHS**, dan **Riwayat**. Pilih **Buat Laporan Baru** untuk menambah laporan. Beranda menyediakan **Lanjutkan Laporan** saat ada draf. Ikon Pengaturan tersedia di bilah atas tab utama. Tema mengikuti setelan sistem.

Back dari editor kembali melalui layar terkait dan tab asal. Back dari tab selain Beranda menuju Beranda. Dari Beranda, konfirmasi **Keluar** menunggu antrean simpan. Jika simpan gagal, pilih **Coba Lagi** atau **Batal** untuk tetap di aplikasi. Perubahan disimpan otomatis dan input terbaru dijurnal untuk pemulihan saat proses berhenti sebelum Room selesai menulis.

- **Callsheet:** identitas → cari nama/alamat outlet langsung → pilih saran atau tambah outlet baru → tiga baris Stok/Jual → **Simpan & Tambah Outlet**. Outlet baru hanya menyalin nama dan alamat, tanpa transaksi lama. Untuk kunjungan yang sudah dicatat gunakan **Simpan Perubahan**. **Daftar Kunjungan** menyediakan pencarian, edit dan hapus dengan konfirmasi. **Periksa Laporan** menampilkan total dan ekspor. Keenam kolom angka boleh diisi sendiri-sendiri. Jumlah dihitung per kolom dan kolom tanpa angka tampil kosong.
- **Laporan Harian:** pilih produk pada bilah **Stok & Penjualan**, lalu isi kolom yang diketahui. DO, Jual, Sample, Bns, dan TG diisi terpisah dari G/RTL. **Tester** menyediakan DO, Sampel, dan TG manual. Pada **Keuangan**, Total Penjualan dihitung otomatis dan saldo serta transaksi lainnya diisi manual.
- **Pemeriksaan:** **Periksa Laporan** menyediakan pilihan PDF dan Excel dengan aksi sesuai format. Laporan harian baru dapat diekspor saat masih draf. Pilih **Selesaikan Laporan** untuk mengunci draf dan **Buka Kembali untuk Edit** bila perlu memperbaikinya.
- **Pratinjau PDF:** dibuka sebagai halaman utuh, mendukung lebar layar, ketuk dua kali, pinch, geser, dan reset. Indikator halaman dan kontrol zoom ada di bilah bawah.
- **Pengaturan:** Profil Sales, Harga Produk, Daftar Outlet, Backup Data, dan Pengaturan Lanjutan. Harga dan aturan tetap snapshot per laporan. Lampiran disalin ke penyimpanan internal dan dibagikan sebagai file terpisah.

Callsheet dan Laporan Harian mempunyai identitas, transaksi, riwayat dan ekspor sendiri. Tidak ada pengambilan transaksi atau stok dari Callsheet ke Laporan Harian.

## Perhitungan laporan harian

Kuantitas dan nilai rupiah memakai bilangan bulat dengan batas 15 digit untuk Excel. Kolom yang dilewati tetap kosong. Angka nol hanya muncul sebagai isian eksplisit atau hasil perhitungannya.

| Produk | Grosir | Retail |
|---|---:|---:|
| MARTIL 12 | Rp14.000 | Rp14.500 |
| MARTIL | Rp15.000 | Rp15.500 |
| HD LIGHT | Rp17.500 | Rp18.000 |

Subtotal = jumlah × harga. Total produk menjumlahkan subtotal G dan RTL. Total Penjualan menjumlahkan total ketiga produk. Baris SG pada cetakan laporan baru menampilkan tanda `-` di harga.

Laporan format 3 menyimpan Jual STOCK, TG STOCK, TG TESTER, serta Saldo Akhir sebagai angka manual. Laporan format 2 tetap memakai pencatatan stok antarhari. Aturan, harga, dan transaksi SG pada laporan historis mengikuti snapshot laporan masing-masing.

## Kompatibilitas data

Application ID tetap `id.lhspapua`; versi 1.2.3 memakai versionCode 6 dan kunci penandatanganan yang sama. SQL Room tetap schema version 1, tanpa destructive migration. Kolom baru bersifat tambahan pada payload JSON dengan nilai default aman. Laporan lama mempertahankan ID, transaksi, harga, aturan, lampiran dan hasil rumus lamanya. Konversi ke pencatatan Stok Awal hanya melalui tindakan **Lengkapi Stok Awal Laporan Lama** dan konfirmasi.

Backup versi 2 mempertahankan sumber, urutan, pembukaan stok, status final dan lampiran. Restore menerima versi 1 dan 2, memvalidasi sebelum transaksi, lalu merekonsiliasi seluruh laporan bersama. Simpan backup sebelum mengganti HP atau menghapus data aplikasi.

## Ekspor

`export/Layout` menghasilkan satu grid fisik untuk PDF vektor dan XLSX OOXML asli. Callsheet A4 landscape, LHS A4 portrait, margin 10 mm. Merged cells, print area, A4, orientasi, angka bertipe numerik, formula aktif dan force recalculation. Worksheet tersembunyi `_StokAwal` hanya ada pada laporan format 2 untuk rumus historis. Pada laporan baru, Excel menyimpan nilai STOCK, TESTER, dan keuangan manual sebagai angka biasa. Rumus aktif ada pada subtotal, total produk, dan Total Penjualan. Input teks selalu inline string (aman dari formula injection). Nilai formula menggunakan cache dari domain yang sama. Tidak menggunakan POI di APK; POI hanya untuk memverifikasi formula saat unit test. Setiap halaman Callsheet mempunyai JUMLAH halaman tersebut; ringkasan Android memuat total seluruh outlet. Teks yang tidak muat bahkan pada 6 pt ditolak dengan pesan agar dipendekkan, bukan dipotong diam-diam.

## Build

JDK 17, Android SDK 35, Gradle wrapper 8.11.1, AGP 8.9.2, Kotlin/Compose compiler 2.1.20.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Release memerlukan environment `LHS_KEYSTORE_PATH`, `LHS_KEYSTORE_PASSWORD`, `LHS_KEY_ALIAS`, `LHS_KEY_PASSWORD`:

```sh
./gradlew testReleaseUnitTest assembleRelease
```

Satu workflow `.github/workflows/android-release.yml` untuk rilis manual. Push Stage 3.3 dari baseline yang telah diverifikasi menjalankan satu build rilis; push berikutnya tidak memenuhi kondisi job tersebut. Empat GitHub Secrets: `LHS_KEYSTORE_BASE64`, `LHS_KEYSTORE_PASSWORD`, `LHS_KEY_ALIAS`, `LHS_KEY_PASSWORD`. Jangan commit keystore/password. Gunakan kunci yang sama untuk semua pembaruan APK.

Lihat `docs/VERIFICATION.md` untuk bukti pengujian dan batas verifikasi. Tidak ada klaim pengujian perangkat fisik jika belum dijalankan.

Jika Secrets belum dipasang melalui konektor, ekstrak arsip signing recovery yang diberikan secara pribadi. Dengan GitHub CLI yang sudah login sebagai `amanhnb88`, jalankan:

```sh
python3 scripts/configure-signing-secrets.py /lokasi/signing-recovery.json
```

Script hanya mengisi empat Secrets di `amanhnb88/LHS`, tidak memicu build dan tidak mencetak nilainya. Alternatifnya, isi empat Secrets melalui Settings → Secrets and variables → Actions menggunakan data di arsip tersebut.

Draf outlet yang sama sekali belum diisi tidak dicetak; isian parsial tetap muncul untuk diperiksa. Hasil perhitungan dan status verifikasi release dirangkum di `docs/VERIFICATION.md`.
