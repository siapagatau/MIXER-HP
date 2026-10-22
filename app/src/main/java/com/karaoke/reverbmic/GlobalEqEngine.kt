package com.karaoke.reverbmic

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// EQ GLOBAL: ngubah suara SEMUA app (YouTube, TikTok, app musik, dll).
//
// Caranya sama kayak Equalizer314 / Wavelet: pasang efek DynamicsProcessing
// (API resmi Android 9+) di audio session 0 = "output mix" - mixer akhir
// sebelum suara keluar ke speaker/earphone. Efeknya berlaku ke semua app
// tanpa app lain perlu ikut kerja sama.
//
// Isinya cuma yang dibutuhin: 1 gain + 4 band (Sub / Low / Mid / High) +
// limiter tipis biar boost gak bikin suara pecah (clipping).
//
// Catatan jujur: sebagian HP/ROM (terutama beberapa merek tertentu) mengabaikan
// efek di session 0. Kalau itu kejadian, efek gagal dibuat / gak kedengeran
// dan alasannya ditampilin lewat [error] ke UI - bukan crash.
object GlobalEqEngine {
    // Batas ATAS tiap band (Hz). DynamicsProcessing membagi spektrum jadi
    // band berurutan: Sub 0-90, Low 90-400, Mid 400-4k, High 4k-20k.
    private val CUTOFFS_HZ = floatArrayOf(90f, 400f, 4000f, 20000f)
    private const val BAND_COUNT = 4
    private const val CHANNELS = 2

    private var dp: DynamicsProcessing? = null

    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    // Pesan kenapa EQ global gak bisa nyala (null = gak ada masalah).
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // Knob 0..1 -> dB. 0.5 = tengah = 0 dB (flat).
    fun gainKnobToDb(knob: Float): Float = (knob - 0.5f) * 24f // +-12 dB
    fun bandKnobToDb(knob: Float): Float = (knob - 0.5f) * 24f // +-12 dB

    @Synchronized
    fun start(s: ReverbSettings): Boolean {
        _error.value = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _error.value = "EQ Audio butuh Android 9 ke atas"
            return false
        }
        if (dp == null) {
            try {
                val effect = DynamicsProcessing(0, 0, buildConfig())
                val r = effect.setEnabled(true)
                if (r != AudioEffect.SUCCESS) {
                    runCatching { effect.release() }
                    _error.value = "HP ini menolak EQ global (kode $r)"
                    return false
                }
                dp = effect
            } catch (t: Throwable) {
                _error.value = "HP ini tidak mendukung EQ global"
                return false
            }
        }
        applySettings(s)
        _active.value = true
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { dp?.setEnabled(false) }
        runCatching { dp?.release() }
        dp = null
        _active.value = false
    }

    // Kirim gain + 4 band ke efek yang lagi jalan. Aman dipanggil kapan pun
    // (kalau efek belum nyala, gak ngapa-ngapain).
    @Synchronized
    fun applySettings(s: ReverbSettings) {
        val effect = dp ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val bandDb = floatArrayOf(
            bandKnobToDb(s.gEqSub),
            bandKnobToDb(s.gEqLow),
            bandKnobToDb(s.gEqMid),
            bandKnobToDb(s.gEqHigh),
        )
        try {
            effect.setInputGainAllChannelsTo(gainKnobToDb(s.gEqGain))
            for (i in 0 until BAND_COUNT) {
                effect.setPreEqBandAllChannelsTo(
                    i, DynamicsProcessing.EqBand(true, CUTOFFS_HZ[i], bandDb[i])
                )
            }
        } catch (t: Throwable) {
            // Efek bisa "dicabut" sistem (mis. app lain ambil alih) - jangan crash.
            _error.value = "EQ global terputus, nyalakan ulang"
        }
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val eq = DynamicsProcessing.Eq(true, true, BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, CUTOFFS_HZ[i], 0f))
        }
        // Limiter pengaman: boost gain/band gak bikin suara pecah.
        val limiter = DynamicsProcessing.Limiter(
            true, true, 0, 1f, 60f, 10f, -2f, 0f
        )
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, BAND_COUNT,   // pre-EQ: dipakai
            false, 0,           // MBC: gak dipakai
            false, 0,           // post-EQ: gak dipakai
            true                // limiter: dipakai
        )
            .setPreEqAllChannelsTo(eq)
            .setLimiterAllChannelsTo(limiter)
            .build()
    }
}
