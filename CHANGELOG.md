# Changelog

## [9.4.0] — 2026-11-12

### Diperbaiki
- Tile dan menu ChatGPT, Hacker AI, Shannon AI, G0DM0D3, ePorner 18+, Artex, FlickReels, dan Dracinema kini langsung membuka situs melalui browser internal dalam satu ketukan.
- Tombol pada halaman layanan memakai satu fungsi pembuka yang sama agar URL dan status tidak berbeda antar-entry point.
- Browser internal menampilkan pesan saat halaman gagal dimuat atau server membalas HTTP error.
- WebView mengizinkan navigasi HTTPS dan merutekan popup/window.open ke WebView yang sama; HTTP dan sertifikat TLS tidak valid tetap ditolak.
- Browser internal mempertahankan cookie pihak pertama/ketiga, DOM storage, JavaScript, dan pemutaran media.

### Batasan yang diketahui
- Google OAuth memblokir autentikasi dari embedded WebView. Untuk login yang memakai Google, layanan dapat mewajibkan browser tepercaya/Custom Tabs. Aplikasi tidak mencoba melewati kebijakan tersebut.
- Login, CAPTCHA, DRM, proteksi wilayah, pemutar video, dan fitur upload dapat berbeda menurut layanan, versi Android System WebView, jaringan, dan akun.
- WebView internal tidak dapat menjamin semua situs pihak ketiga akan mengizinkan embedded browser.

## [9.3.0] — 2026-11-12
- PR #11 dan #12 digabungkan: pembaruan katalog media, cache offline, browser internal, agent Termux, ePorner 18+, dan Artex Pentester AI.

## [9.2.0] — 2026-11-12

### Ditambahkan
- Modul Watch, WireGuard GoBackend, Network Scanner TCP, browser internal, dan perbaikan Jarvis.
- Lihat riwayat perubahan sebelumnya pada entri versi di bawah.
