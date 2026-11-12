# Kebijakan Keamanan Mas Kiplay

## Versi yang didukung

| Versi | Dukungan |
|---|---|
| 9.x | Perbaikan keamanan mengikuti rilis terbaru |
| 8.x dan lebih lama | Tidak didukung |

## Pelaporan kerentanan

Laporkan kerentanan secara privat kepada maintainer melalui kanal kontak yang tersedia di profil repository. Jangan memublikasikan exploit, kredensial, token, atau data pengguna sebelum masalah dikonfirmasi dan diperbaiki.

Sertakan versi aplikasi, langkah reproduksi minimal, dampak, dan bukti yang sudah disamarkan. Jangan mengirim API key, private key WireGuard, konfigurasi VPN lengkap, atau data pribadi.

## Batas kepercayaan dan penyimpanan data

- API key AI disimpan di penyimpanan lokal aplikasi/WebView. API key bukan rahasia yang aman dari perangkat yang sudah di-root, malware lokal, backup yang tidak aman, atau inspeksi perangkat. Jangan memasukkan key yang tidak boleh terekspos.
- Riwayat chat dan preferensi disimpan lokal pada perangkat. Menghapus data aplikasi dapat menghapus data tersebut.
- Konfigurasi WireGuard disimpan menggunakan Android Keystore dan AES-GCM pada implementasi native. Kehilangan/reset kunci perangkat dapat membuat ciphertext lama tidak dapat dipulihkan.
- Jangan pernah membagikan private key WireGuard. Pembuatan keypair saja tidak membuat koneksi VPN.

## WebView

- Halaman utama aplikasi menggunakan native bridge untuk fungsi aplikasi. Karena itu, navigasi konten utama harus tetap pada aset lokal aplikasi yang dipercaya.
- Browser situs pihak ketiga dibuka pada WebView terpisah tanpa native JavaScript bridge.
- Browser internal membatasi navigasi ke HTTPS dan menolak sertifikat TLS yang tidak valid. HTTP dan skema non-HTTPS tidak boleh dianggap aman.
- Cookies dan DOM storage memungkinkan situs mempertahankan sesi login. Pengguna harus menghindari login pada situs yang tidak dipercaya dan keluar dari sesi setelah digunakan.
- Embedded WebView tidak menjamin kompatibilitas semua situs. Google OAuth, CAPTCHA, DRM, upload, dan kebijakan situs dapat mengharuskan browser tepercaya atau aplikasi resmi.

## Jaringan dan VPN

- WireGuard GoBackend adalah jalur full-tunnel yang didukung ketika pengguna memasukkan konfigurasi peer/server yang valid, memberi izin VPN Android, dan rute konfigurasi mencakup trafik yang diinginkan.
- DNS Shield lama adalah layanan DNS-only dan **bukan** VPN seluruh trafik perangkat.
- OpenVPN 3 belum menjadi mesin VPN siap pakai: adapter TUN Android, proteksi socket, lifecycle sesi, dan uji kebocoran IPv4/IPv6/DNS belum selesai. Jangan mengiklankannya sebagai VPN aktif.
- Network Scanner melakukan TCP connect pada 51 port umum untuk satu host. Fitur ini bukan Nmap dan tidak melakukan UDP scan, SYN scan, fingerprint OS, atau subnet discovery. Pindai hanya host yang dimiliki atau yang secara eksplisit diizinkan untuk diuji.

## Aksesibilitas dan otomasi perangkat

Layanan Accessibility dapat membaca elemen antarmuka dan melakukan gestur ketika pengguna mengaktifkannya melalui pengaturan Android. Aktifkan hanya jika benar-benar membutuhkan otomasi, pahami izin yang diminta, dan matikan kembali jika tidak digunakan. Jangan gunakan untuk mengumpulkan data pihak lain atau melewati kontrol akses.

## Catatan verifikasi

Build CI yang berhasil hanya membuktikan source dapat dikompilasi dan pemeriksaan otomatis yang tersedia lulus. Build tersebut bukan pengganti pengujian fisik untuk login, pemutaran media, mikrofon, VPN, DNS/IP leak, atau perilaku situs pihak ketiga.
