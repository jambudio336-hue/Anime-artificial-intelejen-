# Changelog

## [9.0.3] — 2026-11-10

### Diperbaiki
- Splash awal memakai video lokal `video.mp4`; dashboard tetap memakai `Dashboard.mp4`.
- Tombol kembali di header dan tombol Back Android mengembalikan pengguna ke dashboard dari setiap modul.
- Web Check dan OSINT dibuka melalui browser eksternal, bukan iframe pihak ketiga di WebView yang memegang native bridge.
- Pipeline memeriksa keberadaan kedua video, ID paket, dan launcher activity sebelum menerbitkan APK.

### Dipertahankan
- Modul Chat, Jarvis, Agent, Cyber Lab, Dork, Web Check, OSINT, DNS VPN, Riwayat, dan Setelan.


## [8.0.0] — 2026-11-07

### Ditambah
- Tema **Dark Brutal**: hitam pekat, aksen merah darah, tipografi monospace, sudut tajam, overlay scanline, judul glitch.
- Dashboard: video hero **full-bleed** selebar layar di bagian atas, quote brutal tepat di bawahnya.
- `KiplayAccessibility`: layanan otomasi resmi (tap, swipe, ketik, tombol global, baca pohon UI, screenshot API 30+).
- Bridge baru: `caps`, `rootCheck`, `tap`, `swipe`, `type`, `clickText`, `scroll`, `key`, `dump`, `shot`, `listApps`, `installApk`.
- Panel Agent dirombak: kendali langsung, pratinjau screenshot, dan **agent otonom** (baca layar → aksi → ulang, maks 12 langkah, bisa dihentikan).
- Perintah protokol baru: `tap`, `swipe`, `click`, `text`, `key`, `scroll`, `wait`, `dump`, `shot`, `install`.
- Pengenal suara native (`SpeechRecognizer`) dan mesin suara native (`TextToSpeech`) — Jarvis sekarang hidup di APK.
- FileProvider untuk pemasangan APK dari dalam aplikasi.
- Deteksi root sungguhan dengan fallback `rish` (Shizuku).

### Diperbaiki
- Shell root: `su -c` benar, PATH lengkap, stderr digabung, timeout 30 detik, kode keluar dilaporkan.
- Tombol Root tidak lagi selalu gagal karena mengecek method yang tidak ada.
- Jarvis: status mendengar/mengolah/berbicara terpisah, waveform, loop andal, barge-in, fallback tiga lapis.
- minSdk dinaikkan ke 26; service otomasi dan izin ditulis otomatis oleh workflow.

### Keamanan
- Otomasi tetap berbasis izin eksplisit; service harus dinyalakan pengguna.
- Eksekusi perintah agent masih nonaktif secara bawaan.

## [7.1.0]
- Dashboard video layout, animasi Jarvis, eksekusi shell lebih aman, workflow rilis bertanda tangan.

## [6.1.0]
- Jarvis holografik, sapaan Indonesia, Cyber Lab, info perangkat.

## [6.0.0]
- Router hash, upload gambar/dokumen, profil, Al-Qur'an, agent dasar, TTS berlapis, PWA offline.

## [5.0.0]
- Rilis awal.
