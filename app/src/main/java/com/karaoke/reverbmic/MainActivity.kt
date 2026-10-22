package com.karaoke.reverbmic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)

    // True kalau user menekan switch Floating sebelum izin mic ada - begitu
    // izin diberikan, alur Floating dilanjutkan otomatis.
    private var pendingFloating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted && pendingFloating) requestFloatingMode()
        pendingFloating = false
    }

    // Panel floating butuh 2 izin: notifikasi (Android 13+, wajib buat
    // foreground service-nya) dan "gambar di atas app lain". Alurnya:
    // minta notifikasi dulu (kalau perlu) -> baru cek/minta izin overlay ->
    // baru nyalain FloatingWindowService. Ini SAMA SEKALI gak nyentuh
    // AudioEngine, jadi gak ada risiko nambah delay mic.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> proceedToOverlayCheck() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (Settings.canDrawOverlays(this)) startFloatingService(this) }

    fun requestFloatingMode() {
        // Foreground service bertipe microphone WAJIB punya izin RECORD_AUDIO
        // (Android 14+ langsung crash kalau belum), jadi minta dulu.
        if (!hasMicPermission) {
            pendingFloating = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToOverlayCheck()
    }

    private fun proceedToOverlayCheck() {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService(this)
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Output mic->speaker memakai Usage::Media (lihat openStreams() di
        // KaraokeEngine.cpp), jadi tombol fisik volume harus ke STREAM_MUSIC.
        // Dulu diarahkan ke STREAM_ALARM (sisa percobaan lama) -> tombol
        // malah menaikkan volume alarm, bukan suara mic.
        volumeControlStream = AudioManager.STREAM_MUSIC

        hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            KaraokeScreen(
                hasMicPermission = hasMicPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onRequestFloatingMode = { requestFloatingMode() },
            )
        }

        // Minta izin mic langsung saat app pertama kali dibuka.
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Lepas semua (stream + native object) pas activity beneran ditutup.
        AudioEngine.nativeShutdown()
    }
}

fun startFloatingService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, FloatingWindowService::class.java))
}

fun stopFloatingService(context: Context) {
    context.stopService(Intent(context, FloatingWindowService::class.java))
}

// Knob EQ 0..1 -> gain asli -15..+15 dB (0.5 = tengah = 0dB/flat), persis
// rentang EQ channel strip Yamaha MG yang jadi acuan fitur ini.
fun eqKnobToDb(knob: Float): Float = (knob - 0.5f) * 30f
fun dbToEqKnob(db: Float): Float = db / 30f + 0.5f

// Palet warna satu tempat biar seluruh layar konsisten.
private val BgColor = Color(0xFF101218)
private val CardColor = Color(0xFF1A1D26)
private val OffColor = Color(0xFF565C6B)
private val BlueAccent = Color(0xFF7FB3FF)
private val OrangeAccent = Color(0xFFFFA05C)
private val GreenAccent = Color(0xFF6FE39A)

// Kartu seragam: judul di kiri, (opsional) switch on/off di kanan, isi di
// bawahnya. Dipakai EQ / Reverb / Echo biar tampilannya rapi & sama persis,
// bukan 3 blok kode kembar yang beda-beda dikit.
@Composable
private fun SectionCard(
    title: String,
    accent: Color,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (checked != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color(0xFF8A90A0),
                        uncheckedTrackColor = Color(0xFF2A2E38),
                        uncheckedBorderColor = Color.Transparent,
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun KnobRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        content = content
    )
}

// Layar utama (APK): header + switch Floating + semua kontrol.
@Composable
fun KaraokeScreen(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestFloatingMode: () -> Unit = {},
) {
    val context = LocalContext.current
    val floatingOn by FloatingWindowService.running.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: judul + switch Floating (satu kontrol, tanpa teks penjelasan).
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Karaoke Mic",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Floating",
                    tint = if (floatingOn) BlueAccent else OffColor,
                    modifier = Modifier.size(20.dp)
                )
                Switch(
                    checked = floatingOn,
                    onCheckedChange = { on ->
                        if (on) onRequestFloatingMode() else stopFloatingService(context)
                    },
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BlueAccent,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color(0xFF8A90A0),
                        uncheckedTrackColor = Color(0xFF2A2E38),
                        uncheckedBorderColor = Color.Transparent,
                    )
                )
            }

            KaraokeControls(hasMicPermission, onRequestPermission)

            Spacer(Modifier.height(8.dp))
        }
    }
}

// Isi panel FLOATING: memakai KaraokeControls yang SAMA dengan layar utama
// (Mic, Volume, Gate, EQ, Reverb, Echo - semua knob, switch, bentuk, dan
// posisi persis seperti di APK), jadi live dua arah lewat SettingsBus /
// MicStateBus. Header (geser/minimize/tutup) dan handle resize ada di
// layout native floating_panel.xml.
@Composable
fun KaraokeFloatingBody(hasMicPermission: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        KaraokeControls(hasMicPermission = hasMicPermission, onRequestPermission = {})
        Spacer(Modifier.height(36.dp)) // ruang buat handle resize di pojok
    }
}

// Satu baris meter: label di kiri, batang level di kanan.
@Composable
private fun LevelBar(label: String, level: Float, color: Color) {
    val l = level.coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = Color(0xFFE5E7EB), fontSize = 13.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF2A2E38))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(l)
                    .background(if (l > 0.92f) Color(0xFFFF6B6B) else color)
            )
        }
    }
}

// Amplitudo (0..1) -> posisi meter 0..1 dengan skala dB (-60 dB .. 0 dB).
private fun levelFromAmp(amp: Float): Float =
    ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)

// Kartu Level: meter MIC dan meter MUSIK terpisah. Polling cuma jalan selama
// layar ini kelihatan (RESUMED), dan Visualizer musik dibuat/dilepas ikut itu.
@Composable
private fun LevelCard(hasMicPermission: Boolean) {
    var micLevel by remember { mutableFloatStateOf(0f) }
    var musicLevel by remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var active by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val musicError by MusicMeter.error.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) active = true
            else if (event == Lifecycle.Event.ON_PAUSE) active = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(active, hasMicPermission) {
        if (!active) {
            micLevel = 0f; musicLevel = 0f
            return@LaunchedEffect
        }
        // Visualizer butuh izin mic (sudah diminta app ini).
        if (hasMicPermission) MusicMeter.acquire()
        try {
            while (true) {
                delay(50)
                val (micRms, musicRms) = withContext(Dispatchers.Default) {
                    val running = AudioEngine.nativeIsRunning()
                    val mic = if (running) sqrt(AudioEngine.nativeGetMicMeanSquare()) else 0f
                    val engineMs = if (running) AudioEngine.nativeGetOutputMeanSquare() else 0f
                    val mix = if (hasMicPermission) MusicMeter.readMixRms() else -1f
                    // Mix akhir = musik + suara mic engine -> kurangi daya engine.
                    val music = if (mix < 0f) 0f else sqrt(max(0f, mix * mix - engineMs))
                    mic to music
                }
                val micNow = levelFromAmp(micRms)
                val musicNow = levelFromAmp(musicRms)
                // Naik cepat, turun pelan biar batangnya enak dilihat.
                micLevel = if (micNow > micLevel) micNow else max(micNow, micLevel - 0.06f)
                musicLevel = if (musicNow > musicLevel) musicNow else max(musicNow, musicLevel - 0.06f)
            }
        } finally {
            if (hasMicPermission) MusicMeter.release()
        }
    }

    SectionCard(title = "Level", accent = GreenAccent) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LevelBar("Mic", micLevel, GreenAccent)
            LevelBar("Musik", musicLevel, BlueAccent)
            if (musicError != null) {
                Text(musicError ?: "", color = OffColor, fontSize = 12.sp)
            }
        }
    }
}

// Semua kartu kontrol + state-nya. Dipakai layar utama DAN panel floating.
@Composable
fun KaraokeControls(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    // Muat pengaturan terakhir yang kesimpen - sekali doang lewat `remember`.
    val saved = remember { SettingsStore.load(context) }

    var isRunning by remember { mutableStateOf(AudioEngine.nativeIsRunning()) }
    var wet by remember { mutableFloatStateOf(saved.wet) }
    var dry by remember { mutableFloatStateOf(saved.dry) }
    var width by remember { mutableFloatStateOf(saved.width) }
    var damp by remember { mutableFloatStateOf(saved.damp) }
    var roomSize by remember { mutableFloatStateOf(saved.roomSize) }
    var cess by remember { mutableFloatStateOf(saved.cess) }
    var gate by remember { mutableFloatStateOf(saved.gate) }
    // Knob 0..1 di-map ke gain 0..2x (tengah = 1.0x = suara asli).
    var micVol by remember { mutableFloatStateOf(saved.micVol) }
    var reverbEnabled by remember { mutableStateOf(saved.reverbEnabled) }
    var echoDelay by remember { mutableFloatStateOf(saved.echoDelay) }
    var echoFeedback by remember { mutableFloatStateOf(saved.echoFeedback) }
    var echoMix by remember { mutableFloatStateOf(saved.echoMix) }
    var echoEnabled by remember { mutableStateOf(saved.echoEnabled) }
    // EQ: 0.5 = flat (0 dB).
    var eqLow by remember { mutableFloatStateOf(saved.eqLow) }
    var eqMid by remember { mutableFloatStateOf(saved.eqMid) }
    var eqHigh by remember { mutableFloatStateOf(saved.eqHigh) }
    var eqEnabled by remember { mutableStateOf(saved.eqEnabled) }
    // EQ Audio global (suara app lain): gain + Sub/Low/Mid/High, 0.5 = flat.
    var gEqGain by remember { mutableFloatStateOf(saved.gEqGain) }
    var gEqSub by remember { mutableFloatStateOf(saved.gEqSub) }
    var gEqLow by remember { mutableFloatStateOf(saved.gEqLow) }
    var gEqMid by remember { mutableFloatStateOf(saved.gEqMid) }
    var gEqHigh by remember { mutableFloatStateOf(saved.gEqHigh) }
    var gEqEnabled by remember { mutableStateOf(saved.gEqEnabled) }
    val gEqError by GlobalEqEngine.error.collectAsState()

    fun applyFresh(f: ReverbSettings) {
        wet = f.wet; dry = f.dry; width = f.width; damp = f.damp
        roomSize = f.roomSize; cess = f.cess; gate = f.gate; micVol = f.micVol
        reverbEnabled = f.reverbEnabled
        echoDelay = f.echoDelay; echoFeedback = f.echoFeedback
        echoMix = f.echoMix; echoEnabled = f.echoEnabled
        eqLow = f.eqLow; eqMid = f.eqMid; eqHigh = f.eqHigh; eqEnabled = f.eqEnabled
        gEqGain = f.gEqGain; gEqSub = f.gEqSub; gEqLow = f.gEqLow
        gEqMid = f.gEqMid; gEqHigh = f.gEqHigh; gEqEnabled = f.gEqEnabled
    }

    // Sinkron saat layar ini balik ke depan (nilai bisa berubah lewat panel
    // floating selagi activity di-pause).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applyFresh(SettingsStore.load(context))
                isRunning = AudioEngine.nativeIsRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Update real-time dua arah antara layar utama <-> panel floating (hanya
    // update tampilan lokal - jangan persist() lagi, sudah disimpan pengirimnya).
    LaunchedEffect(Unit) { SettingsBus.updates.collect { applyFresh(it) } }
    LaunchedEffect(Unit) { MicStateBus.updates.collect { isRunning = it } }
    // Kalau EQ Audio tersimpan ON tapi prosesnya sempat mati, nyalakan lagi.
    LaunchedEffect(Unit) {
        if (saved.gEqEnabled && !GlobalEqService.running.value) startGlobalEq(context)
    }

    fun current() = ReverbSettings(
        wet, dry, width, damp, roomSize, micVol, reverbEnabled,
        echoDelay, echoFeedback, echoMix, echoEnabled,
        eqLow, eqMid, eqHigh, eqEnabled, cess, gate,
        gEqGain = gEqGain, gEqSub = gEqSub, gEqLow = gEqLow,
        gEqMid = gEqMid, gEqHigh = gEqHigh, gEqEnabled = gEqEnabled,
    )

    fun persist() {
        val c = current()
        SettingsStore.save(context, c)
        SettingsBus.publish(c)
    }

    fun pushParams() {
        AudioEngine.nativeSetReverbParams(wet, dry, width, damp, roomSize)
        persist()
    }

    fun pushCess() {
        AudioEngine.nativeSetCess(cess)
        persist()
    }

    fun pushGate() {
        AudioEngine.nativeSetGate(gate)
        persist()
    }

    fun pushMicGain() {
        AudioEngine.nativeSetMicGain(micVol * 2f)
        persist()
    }

    fun pushReverbEnabled() {
        AudioEngine.nativeSetReverbEnabled(reverbEnabled)
        persist()
    }

    fun pushEQParams() {
        AudioEngine.nativeSetEQParams(eqKnobToDb(eqLow), eqKnobToDb(eqMid), eqKnobToDb(eqHigh))
        persist()
    }

    fun pushEQEnabled() {
        AudioEngine.nativeSetEQEnabled(eqEnabled)
        persist()
    }

    // Knob 0..1 -> delay 50-600ms, feedback 0-0.75, mix 0-1.
    fun pushEchoParams() {
        AudioEngine.nativeSetEchoParams(50f + echoDelay * 550f, echoFeedback * 0.75f, echoMix)
        persist()
    }

    fun pushEchoEnabled() {
        AudioEngine.nativeSetEchoEnabled(echoEnabled)
        persist()
    }

    fun pushGlobalEq() {
        GlobalEqEngine.applySettings(current())
        persist()
    }

    fun pushGlobalEqEnabled() {
        persist() // simpan dulu: service membaca status ON dari SettingsStore
        if (gEqEnabled) startGlobalEq(context) else stopGlobalEq(context)
    }

    fun toggleEngine() {
        if (!hasMicPermission) {
            onRequestPermission()
            return
        }
        if (isRunning) {
            AudioEngine.nativeStop()
            isRunning = false
            MicStateBus.publish(false)
        } else {
            if (AudioEngine.nativeStart()) {
                pushAllSettingsToEngine(current())
                isRunning = true
                MicStateBus.publish(true)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Kartu 1: tombol Mic ON/OFF (berdiri sendiri).
        SectionCard(title = "Mic", accent = OrangeAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) Color(0xFF2E7D32) else Color(0xFF2A2E38))
                        .border(
                            width = 3.dp,
                            color = if (isRunning) GreenAccent else Color(0xFF3A3F4B),
                            shape = CircleShape
                        )
                        .clickable { toggleEngine() }
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (isRunning) "Matikan mic" else "Nyalakan mic",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(Modifier.width(20.dp))
                Text(
                    if (isRunning) "ON" else "OFF",
                    color = if (isRunning) GreenAccent else OffColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Meter level: mic dan musik terpisah.
        LevelCard(hasMicPermission)

        // Kartu 2: Volume + Gate.
        SectionCard(title = "Volume", accent = OrangeAccent) {
            KnobRow {
                RotaryKnob(
                    label = "Volume",
                    value = micVol,
                    onValueChange = { micVol = it; pushMicGain() },
                    accentColor = OrangeAccent
                )
                RotaryKnob(
                    label = "Gate",
                    value = gate,
                    onValueChange = { gate = it; pushGate() },
                    accentColor = OrangeAccent
                )
            }
        }

        SectionCard(
            title = "EQ Mic", accent = GreenAccent,
            checked = eqEnabled,
            onCheckedChange = { eqEnabled = it; pushEQEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Low", eqLow, { eqLow = it; pushEQParams() }, enabled = eqEnabled, accentColor = GreenAccent)
                RotaryKnob("Mid", eqMid, { eqMid = it; pushEQParams() }, enabled = eqEnabled, accentColor = GreenAccent)
                RotaryKnob("High", eqHigh, { eqHigh = it; pushEQParams() }, enabled = eqEnabled, accentColor = GreenAccent)
            }
        }

        // EQ Audio GLOBAL: ngatur suara app lain (YouTube, TikTok, musik).
        SectionCard(
            title = "EQ Audio", accent = OrangeAccent,
            checked = gEqEnabled,
            onCheckedChange = { gEqEnabled = it; pushGlobalEqEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Gain", gEqGain, { gEqGain = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
                RotaryKnob("Sub", gEqSub, { gEqSub = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
                RotaryKnob("Low", gEqLow, { gEqLow = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
            }
            Spacer(Modifier.height(14.dp))
            KnobRow {
                RotaryKnob("Mid", gEqMid, { gEqMid = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
                RotaryKnob("High", gEqHigh, { gEqHigh = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
            }
            if (gEqError != null) {
                Spacer(Modifier.height(10.dp))
                Text(gEqError ?: "", color = OffColor, fontSize = 12.sp)
            }
        }

        SectionCard(
            title = "Reverb", accent = BlueAccent,
            checked = reverbEnabled,
            onCheckedChange = { reverbEnabled = it; pushReverbEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Wet", wet, { wet = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Dry", dry, { dry = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Width", width, { width = it; pushParams() }, enabled = reverbEnabled)
            }
            Spacer(Modifier.height(14.dp))
            KnobRow {
                RotaryKnob("Damp", damp, { damp = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Room", roomSize, { roomSize = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Cess", cess, { cess = it; pushCess() }, enabled = reverbEnabled)
            }
        }

        SectionCard(
            title = "Echo", accent = GreenAccent,
            checked = echoEnabled,
            onCheckedChange = { echoEnabled = it; pushEchoEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Delay", echoDelay, { echoDelay = it; pushEchoParams() }, accentColor = GreenAccent, enabled = echoEnabled)
                RotaryKnob("Repeat", echoFeedback, { echoFeedback = it; pushEchoParams() }, accentColor = GreenAccent, enabled = echoEnabled)
                RotaryKnob("Mix", echoMix, { echoMix = it; pushEchoParams() }, accentColor = GreenAccent, enabled = echoEnabled)
            }
        }
    }
}
