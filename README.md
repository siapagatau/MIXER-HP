# Karaoke Mic Reverb

App Android simple: suara dari **mic clip** (headset TRRS kayak di foto - colok ke jack HP)
langsung diproses dan dikeluarin ke **speaker HP** secara real-time, cuma ditambahin
efek **reverb** yang bisa diatur (Wet, Dry, Width, Damp, Room Size) - persis 5 parameter
di plugin reverb yang jadi referensi kamu.

## Kenapa ini low-delay

- Pakai [Oboe](https://github.com/google/oboe) (library audio Google buat Android),
  bukan MediaRecorder/AudioTrack biasa - Oboe otomatis milih jalur AAudio LowLatency
  ter-cepat yang HP kamu support. Output sengaja pakai mode **Shared** (bukan
  Exclusive) supaya app musik/backing-track lain tetap bisa ikut keluar bareng
  suara mic lewat system mixer - Exclusive dulu sempat dipakai buat output tapi
  bikin app lain gak kebagian jalur speaker sama sekali pas mic nyala.
- Mic dan speaker jalan **full-duplex**: setiap kali speaker butuh sample baru
  (`onAudioReady`), engine LANGSUNG narik sample yang paling baru dari mic di momen
  itu juga, proses reverb, tulis ke speaker - satu callback = satu perjalanan
  mic -> speaker. Gak ada antrian/buffer besar di tengah yang nambah delay.
- Semua proses reverb (comb + allpass filter ala Freeverb) jalan di **native C++**,
  bukan Kotlin, dan gak ada alokasi memori di audio thread - biar gak ada
  glitch/xrun pas lagi jalan.
- Input mic pakai `InputPreset.Unprocessed` supaya Android gak otomatis nyalain
  noise-suppression / echo-cancel / auto-gain bawaan sistem (fitur itu didesain
  buat telepon, kalau nyala malah bikin reverb-nya "dimakan" atau vokal jadi aneh).

## Cara pakai

1. Colok mic clip ke jack HP (atau via adapter USB-C/Lightning kalau HP kamu gak
   punya jack 3.5mm - splitter di foto kamu itu buat headset+mic jadi 1 jack TRRS,
   yang penting ujungnya tetep masuk sebagai default mic system).
2. Buka app, izinkan akses mic pas diminta.
3. Ketuk tombol mic besar di tengah buat nyalain jalur mic -> speaker.
4. Panel **Input**: knob **Mic Vol** buat naik/turunin volume mic itu sendiri
   (gain, sebelum masuk reverb) - posisi tengah knob = suara asli (1.0x/unity),
   geser ke atas buat boost mic yang pelan, ke bawah buat redam yang kekencengan.
5. Panel **Reverb**: knob **Wet** (porsi suara reverb), **Dry** (porsi suara asli
   di output), **Width** (lebar stereo reverb-nya), **Damp** (redam nada tinggi
   si reverb), **Room** (panjang ekor reverb / kesan besar-kecilnya ruangan),
   dan **Cess** (panjang & terangnya desis huruf S di ekor reverb - karakter
   reverb Alesis. Makin ke kanan, "s"-nya makin panjang: ceeesss. Ke kiri =
   reverb gelap/halus biasa).

### Soal delay

Mic Vol dan Dry itu murni PERKALIAN angka per sample - gak ada buffer atau
delay line baru, jadi gak nambah latency sama sekali. Reverb (Wet/Damp/Room
Size) memang punya delay line internal, tapi itu cuma buat MEMBENTUK ekor
dengungnya, bukan nunda sinyal asli - suara mic yang "kering" (dry) tetap
sampai ke speaker di frame yang sama persis kayak waktu ditangkep mic. Yang
lebih nentuin delay yang KERASA itu ukuran buffer stream audio, dan itu sudah
diminimalkan sejak awal (lihat bagian "Kenapa ini low-delay" di atas).

> Kalau kedengeran ngiiing/feedback (speaker "denger" reverb sendiri lewat mic
> lagi), itu wajar buat setup mic+speaker sama-sama kebuka di 1 HP. Solusinya:
> pakai earphone/headset (bukan speaker HP) buat dengerin, atau kecilin volume
> speaker & tombol Wet.

## Build via GitHub (sama kayak proyek APK DTX kamu)

1. Push folder ini ke repo GitHub.
2. Buka tab **Actions** di repo -> workflow **"Build Debug APK"** jalan otomatis
   tiap push ke `main`/`master`, atau jalanin manual lewat tombol **Run workflow**.
3. Setelah selesai (~5-10 menit), buka run-nya -> bagian **Artifacts** ->
   download **karaoke-mic-reverb-debug** -> isinya `app-debug.apk`, tinggal
   install ke HP (aktifkan "Install dari sumber tidak dikenal" kalau diminta).

## Mode Floating (panel di atas app lain)

**Switch Floating** di pojok kanan atas layar utama bakal:

1. Minta izin notifikasi (Android 13+) - wajib buat foreground service yang
   megang panel ini.
2. Minta izin **"Tampil di atas app lain"** (dialog system Android) kalau
   belum diaktifkan.
3. Nyalain panel kecil yang nongol DI ATAS app lain (misal app musik/
   backing track kamu), isinya cukup dua: tombol **Mic ON/OFF** dan slider
   **Volume Mic**. Setelan reverb/echo/EQ tetap diatur di app utama.

Panel itu:
- **Bisa digeser** - tahan & seret bagian judul (bar atas panel). Panel
  selalu dijaga utuh di dalam layar, jadi gak nyangkut di pojokan.
- **Bisa di-resize** - tahan & seret pojok kanan-bawah (kotak kecil).
- **Bisa di-minimize** - ketuk tombol "\u2013" di pojok kanan atas, panel
  ngecil jadi bulatan ikon app. Ketuk bulatannya lagi buat balik jadi panel
  penuh (bulatan lebih besar, bisa digeser-geser kayak "chat head", dan
  otomatis nempel ke tepi kiri/kanan layar pas dilepas).
- Tombol "\u2715" di title bar / matiin switch Floating di app utama buat
  nutup floating-nya total.

Posisi, ukuran, dan status minimize kesimpen otomatis (`FloatingPrefs.kt`),
jadi kalau HP di-reboot atau service sempat mati, panel balik ke kondisi
terakhir.

## EQ Audio (suara app lain: YouTube, TikTok, musik)

Kartu **EQ Audio** (switch di pojok kanannya) ngatur suara SEMUA app, terpisah
dari **EQ Mic** yang cuma buat suara mic. Isinya cuma 5 knob: **Gain**
(naik/turun volume keseluruhan, +-12 dB) dan **Sub / Low / Mid / High**
(+-12 dB, tengah = flat). Pembagian band: Sub 0-90 Hz, Low 90-400 Hz,
Mid 400 Hz-4 kHz, High 4-20 kHz.

Cara kerja (sama kayak Equalizer314 / Wavelet): efek `DynamicsProcessing`
(Android 9+) dipasang di audio session 0 = output mix, plus limiter tipis biar
boost gak bikin suara pecah. Dijaga foreground service `GlobalEqService`
(notifikasi "EQ Audio aktif") supaya efeknya gak mati pas app di-background.

Catatan:
- Karena dipasang di output mix, suara mic karaoke ikut kena EQ ini juga.
- Sebagian HP/ROM mengabaikan efek di session 0. Kalau begitu, switch balik
  OFF dan alasannya tampil di bawah knob.

## Visualizer level (Mic & Musik)

Kartu **Level** nampilin dua batang terpisah: **Mic** (suara mic setelah Gate
& Volume, dibaca langsung dari engine native) dan **Musik** (suara app lain,
dibaca lewat `Visualizer` di output mix). Karena mix akhir juga berisi suara
mic karaoke, meter Musik dihitung dengan mengurangi daya output engine mic -
jadi hasilnya perkiraan. Polling cuma jalan selama layar/panel kelihatan.

## Struktur proyek

```
app/src/main/java/com/karaoke/reverbmic/
  MainActivity.kt          -> UI Compose (tombol mic + panel knob reverb/echo)
  KnobControl.kt            -> komponen knob putar custom (drag melingkar)
  AudioEngine.kt              -> jembatan Kotlin -> native (JNI)
  FloatingWindowService.kt     -> panel floating: drag, resize, minimize ke bulatan
  FloatingPrefs.kt               -> simpan posisi/ukuran/status minimize floating
  SettingsStore.kt                  -> simpan semua nilai knob (SharedPreferences)
  GlobalEqEngine.kt                   -> EQ global (DynamicsProcessing di session 0)
  MusicMeter.kt                         -> meter level musik (Visualizer session 0)
  GlobalEqService.kt                    -> foreground service pemegang EQ global
app/src/main/res/layout/
  floating_panel.xml     -> layout panel floating penuh
  floating_bubble.xml     -> layout bulatan pas di-minimize
app/src/main/cpp/
  native-lib.cpp         -> fungsi JNI
  KaraokeEngine.h/.cpp    -> engine full-duplex Oboe + reverb Freeverb-style
```

## Kalau mau dikembangin lagi

- Nambah efek lain (delay/echo, compressor) tinggal nambah kelas baru di
  `KaraokeEngine.cpp`, sisipin di antara baca mic dan tulis output.
- Nambah preset (mis. "Vocal", "Hall", "Room Kecil") tinggal simpen beberapa
  kombinasi wet/dry/width/damp/roomSize di Kotlin, tinggal panggil
  `nativeSetReverbParams(...)` pas user pilih preset.
