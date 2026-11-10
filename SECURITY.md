# Kebijakan Keamanan

## Versi yang didukung

| Versi | Didukung |
|-------|----------|
| 9.x   | Ya       |
| 8.x   | Tidak    |

## Melaporkan kerentanan

Kirim ke maintainer lewat Issue GitHub dengan label `security`. Sertakan:

- Versi aplikasi
- Langkah reproduksi
- Dampak yang mungkin
- Saran perbaikan bila ada

Jangan membuka detail teknis di publik sebelum konfirmasi.

## Model keamanan

**API key** disimpan di `localStorage` perangkat, tidak pernah dikirim ke server selain penyedia model yang dipilih pengguna. Tidak ada backend perantara.

**Riwayat chat** juga hanya di perangkat. Menghapus data aplikasi berarti menghapus riwayat permanen.

**VPN** berjalan lokal lewat `VpnService` Android. Tidak ada akun, tidak ada log terpusat. Trafik DNS diteruskan langsung ke resolver pilihan pengguna.

**WebView** dikonfigurasi dengan `allowFileAccess` dan `allowContentAccess` aktif agar aset lokal terbaca. Navigasi `http`/`https` tetap di dalam WebView; skema lain dibuka ke aplikasi eksternal.

## Batasan yang diketahui

- `mixedContentMode` diset `COMPATIBILITY_MODE`, jadi sumber `http` masih boleh dimuat di halaman `file://`
- `usesCleartextTraffic` aktif untuk endpoint yang belum punya TLS
- Mode VPN `DNS Shield` hanya mengalihkan DNS, bukan seluruh trafik
- API key tersimpan tanpa enkripsi tambahan; perangkat yang sudah di-root bisa membacanya
