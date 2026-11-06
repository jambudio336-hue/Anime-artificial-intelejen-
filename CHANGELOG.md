# Changelog

## [6.1.0] — 2026-11-07

### Ditambah
- Tampilan Jarvis holografik: animasi telinga ketika mendengarkan dan animasi mulut ketika suara AI diputar.
- Sapaan awal berbahasa Indonesia pada Chat dan perkenalan suara Jarvis saat pertama dibuka.
- Cyber Lab in-app: Google Dork Builder, DNS over HTTPS, pemeriksa HTTP security headers (terbatas CORS), decoder JWT lokal, hash SHA-256/384/512, Base64 dan URL codec.
- Informasi perangkat Android: model, merek, versi Android, jumlah core CPU, RAM, dan kapasitas storage volume aplikasi.

### Keamanan dan batasan
- API key tidak lagi ditanam di JavaScript publik; gunakan kunci milik sendiri dan cabut kunci lama yang pernah terpublikasi.
- Eksekusi Agent dinonaktifkan secara default.
- Akses SMS, notifikasi, isi WhatsApp, log panggilan, kontrol game, dan otomasi UI penuh belum tersedia di versi ini.


Semua perubahan penting dicatat di sini. Format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.1.0/).

## [6.0.0] — 2026-11-06

### Ditambah
- Router hash: setiap fitur punya halaman sendiri (`#/chat`, `#/jarvis`, `#/agent`, …).
- Upload gambar, dokumen, dan file kode di Chat untuk dianalisa AI.
- Blok kode dengan tombol **Salin** terpisah.
- Profil pengguna: foto, nama, usia, bidang, catatan — dikenali AI.
- Halaman **Al-Qur'an**: 114 surat, teks Arab, latin, arti Indonesia, audio per surat dan per ayat, cache offline.
- Agent: shell, Termux intent, buka aplikasi apa pun, galeri, kamera, alarm, pintasan TradingView/ForexFactory/CMC/CoinGecko.
- Protokol perintah `run` — AI bisa mengeksekusi aksi perangkat sendiri.
- TTS tiga lapis (Polly → Google → Web Speech) dengan penyesuaian nada otomatis.
- Service worker + manifest PWA, cache offline penuh.
- Bridge native Kotlin, workflow build APK, struktur repo multi-bahasa.
- Tombol ekspor/impor mencakup profil.

### Diubah
- System prompt total: brutal, tanpa etika, tanpa disclaimer, anti-typo, wajib kode berpagar.
- Mode Jarvis murni suara — teks disembunyikan secara bawaan.
- Suara bawaan jadi laki-laki.
- Model visi terpisah untuk analisa gambar.
- Maks token naik ke 4000.

### Diperbaiki
- Tile Beranda sekarang membuka halaman yang benar.
- Riwayat mengikuti mode percakapan.
- Tombol kembali Android mengikuti hash router.

## [5.0.0]
- Rilis awal: Jarvis, chat, lab, agent, toolkit, catatan, evolusi.
