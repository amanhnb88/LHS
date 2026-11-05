# LHS Papua

Aplikasi Android native Kotlin / Jetpack Compose untuk sales Papua. Android 8.0 (API 26) atau lebih baru. Seluruh data lokal, tanpa login, server, WebView atau izin internet.

## Penggunaan

1. Buka **Pengaturan**: simpan profil sales, harga G/SG/RTL untuk MARTIL 12, MARTIL dan HD LIGHT, lalu verifikasi makna TG dan aturan saldo dengan perusahaan.
2. Pilih **CALLSHEET** atau **LAPORAN HARIAN** untuk membuat laporan baru. Transaksi kedua formulir independen, termasuk pada tanggal yang sama.
3. Callsheet: Tambah Outlet, isi stok dan jual, Simpan dan Lanjut. Nama/alamat outlet dapat dipakai ulang. Setiap halaman ekspor berisi 30 baris.
4. Laporan Harian: buka kartu produk, isi DO, G/SG/RTL, sample, bonus. Isi Tester dan Keuangan lewat tab masing-masing. Harga dan aturan disimpan dalam laporan; pengaturan baru tidak mengubah riwayat.
5. Buka **Ringkasan & Ekspor**, periksa validasi, buat PDF atau Excel, kemudian Simpan atau Bagikan.
6. Foto/PDF bukti diambil melalui pemilih dokumen Android dan disalin ke penyimpanan internal. Saat dibagikan bersama laporan, lampiran tetap berupa file terpisah.
7. Backup ZIP melalui Pengaturan. Simpan backup secara berkala sebelum menghapus data aplikasi atau mengganti HP. Pemulihan menggabungkan ID dan mengganti laporan dengan ID yang sama setelah konfirmasi.

Perubahan disimpan ke Room melalui antrean terurut tanpa debounce. Status menampilkan hasil penyimpanan. Tombol Simpan mengulang penyimpanan jika sebelumnya gagal. Nilai kosong tetap berbeda dari 0. Harga kosong tidak dihitung sebagai Rp0. Stok negatif/harga belum diatur/aturan belum diverifikasi menghalangi ekspor final.

## Perhitungan

Satu domain calculation layer (`domain/Calculation.kt`), integer Long. Input dan hasil dibatasi 15 digit agar tetap tepat saat disimpan ke Excel. Perkalian dan penjumlahan memakai pemeriksaan overflow. Tidak ada floating point untuk nilai Rupiah.

- Subtotal = jumlah × harga; Jual = jumlah G + SG + RTL; total = seluruh subtotal.
- Awal TG = DO − Jual − Sample − Bns; TG Tester = DO − Sampel. Makna TG masih asumsi, harus diverifikasi. Bisa manual; pengurangan sample/bonus dapat diatur.
- Awal saldo = saldo sebelum + penjualan − piutang + pelunasan − transfer − pengeluaran. Arah tiap komponen bisa diatur dan rumus aktif ditampilkan.

## Ekspor

`export/Layout` menghasilkan satu grid fisik untuk PDF vektor dan XLSX OOXML asli. Callsheet A4 landscape, LHS A4 portrait, margin 10 mm. Merged cells, print area, A4, orientasi, angka bertipe numerik, formula aktif dan force recalculation. Input teks selalu inline string (aman dari formula injection). Nilai formula menggunakan cache dari domain yang sama. Tidak menggunakan POI di APK; POI hanya untuk memverifikasi formula saat unit test. Setiap halaman Callsheet mempunyai JUMLAH halaman tersebut; ringkasan Android memuat total seluruh outlet. Teks yang tidak muat bahkan pada 6 pt ditolak dengan pesan agar dipendekkan, bukan dipotong diam-diam.

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
