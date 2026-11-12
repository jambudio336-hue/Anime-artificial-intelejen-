# Changelog

## [9.4.1] — 2026-11-12

### Keamanan
- Setiap perintah shell lokal maupun ROOT wajib disetujui melalui dialog native Android; prompt JavaScript tidak dianggap sebagai batas keamanan.
- Eksekusi perintah melalui Termux juga meminta persetujuan native Android.
- Batasi panjang perintah shell lokal dan ROOT hingga 4.096 karakter.

### WebView
- Tile ChatGPT, Hacker AI, Shannon, G0DM0D3, ePorner 18+, Artex, FlickReels, dan Dracinema membuka browser internal dalam satu ketukan.
- Browser internal mempertahankan HTTPS, menolak HTTP dan sertifikat TLS tidak valid, serta tidak mengekspos native JavaScript bridge ke halaman pihak ketiga.
- Batasan embedded WebView, Google OAuth, CAPTCHA, DRM, dan proteksi situs didokumentasikan.

### Batasan
- WireGuard memerlukan konfigurasi peer/server valid dan izin VPN Android.
- OpenVPN 3 belum mempunyai adapter TUN/sesi Android yang siap produksi.
- Network Scanner adalah TCP connect scanner satu host, bukan Nmap penuh.
- Uji VPN, mikrofon, pemutaran video, dan otomasi tetap memerlukan perangkat Android fisik.

## [9.4.0] — 2026-11-12

### Diperbaiki
- Validasi konfigurasi WireGuard memakai parser resmi sebelum konfigurasi disimpan atau tunnel dijalankan.
- Batas ukuran konfigurasi WireGuard 64 KiB untuk mencegah input berlebihan.
- Ciphertext konfigurasi yang tidak dapat didekripsi dibersihkan agar tidak terus memicu kegagalan.
- User-Agent WebView diselaraskan dengan versi aplikasi.

### Pemeriksaan rilis
- Pipeline memvalidasi registrasi plugin native Watch, WireGuard, dan Network Scanner.
- Pipeline memeriksa penggunaan Android Keystore AES-GCM untuk penyimpanan konfigurasi WireGuard.
- Status DNS-only, WireGuard full-tunnel, dan batasan OpenVPN 3/Network Scanner diperiksa agar tidak salah diklaim.

### Batasan
- OpenVPN 3 masih belum mempunyai adapter TUN/sesi Android; PR #9 tetap belum layak digabungkan.
- Network Scanner adalah TCP connect scanner, bukan Nmap.
- Fitur jaringan, video, dan aksesibilitas tetap memerlukan pengujian pada perangkat Android fisik.

## [9.3.0] — 2026-11-12
- PR #11 dan #12 digabungkan: pembaruan katalog media, cache offline, browser internal, agent Termux, ePorner 18+, dan Artex Pentester AI.

## [9.2.0] — 2026-11-12

### Ditambahkan
- Modul Watch untuk katalog Dracin Melolo, pencarian Anime melalui mirror Hianime-compatible, dan pemutaran MP4/HLS melalui Media3 ExoPlayer native.
- Riwayat tontonan, daftar tontonan, penyimpanan progres, dan sinkronisasi lokal berkala.
- Pengaturan mirror Anime dan input URL video manual.
- Kontrol WireGuard GoBackend: izin VPN Android, konfigurasi terenkripsi, pembuatan keypair, sambung, putus, dan status.
- Perbaikan sesi mikrofon Jarvis/chat agar callback lama tidak masuk ke sesi baru.
- Browser internal terisolasi untuk Web Check, OSINT, dan Google Dork: HTTPS-only, tanpa native bridge, serta menolak sertifikat TLS tidak valid.
- Network Scanner TCP native untuk 51 port umum pada satu host, tersedia di Watch dan Cyber Lab.

### Batasan yang dinyatakan
- Network Scanner adalah TCP connect scanner sederhana buatan proyek, bukan Nmap; tidak menyediakan UDP, SYN scan, fingerprint OS, atau pemindaian subnet.
- WireGuard membutuhkan konfigurasi server/peer yang valid. Status tunnel harus diverifikasi pada perangkat.
- OpenVPN 3 Core di PR terpisah hanya capability probe; belum membangun sesi VPN atau TUN Android.
- Ketersediaan Dracin/Anime bergantung pada API dan mirror pihak ketiga. Pemutaran dan decoder perlu diuji pada perangkat fisik.

### Pipeline
- Version name 9.2.0 dan versionCode 910020.
- APK release hanya diterbitkan setelah build, verifikasi tanda tangan, dan pemeriksaan artefak berhasil.

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
