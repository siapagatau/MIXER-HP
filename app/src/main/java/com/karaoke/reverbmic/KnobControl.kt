package com.karaoke.reverbmic

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

// Knob muter ala plugin audio (Wet/Dry/Width/Damp/Room Size di gambar
// referensi user). Interaksinya: knob diputar dengan gerakan MELINGKAR -
// sudut jari dihitung relatif ke titik pusat knob, lalu selisih sudut
// antar-frame dikonversi jadi perubahan value. Ini pakai delta sudut
// (bukan sudut absolut) biar knob gak "lompat" pas jari pertama nyentuh
// jauh dari posisi jarum, tapi tetap kerasa "muter beneran" pas digeser
// melingkar di sekitar knob.
@Composable
fun RotaryKnob(
    label: String,
    value: Float, // 0f..1f
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF7FB3FF),
    enabled: Boolean = true,
) {
    // FIX bug "knob ke-reset pas digeser": Modifier.pointerInput(Unit) cuma
    // ngejalanin blok gesture-nya SEKALI (key-nya Unit, gak pernah berubah),
    // jadi variabel `value` yang kepakek DI DALAM detectDragGestures
    // ke-"freeze" ke nilai pas pertama kali komponen ini muncul di layar -
    // gak ikut update walaupun state di parent udah berubah. Akibatnya tiap
    // geser dihitung dari nilai LAMA terus, kerasa kayak "kegeser balik".
    // rememberUpdatedState nge-fix ini: selalu ngasih nilai TERBARU ke
    // closure gesture, tanpa perlu restart gesture detector-nya (yang malah
    // bisa motong gesture drag di tengah jalan kalau di-restart tiap frame).
    val latestValue = rememberUpdatedState(value)
    val latestEnabled = rememberUpdatedState(enabled)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        var lastAngleDeg by remember { mutableStateOf(0f) }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .pointerInput(Unit) {
                    val center = Offset(size.width / 2f, size.height / 2f)

                    fun angleAt(pos: Offset): Float {
                        val dx = pos.x - center.x
                        val dy = pos.y - center.y
                        return atan2(dy, dx) * 180f / PI.toFloat()
                    }

                    detectDragGestures(
                        onDragStart = { offset ->
                            lastAngleDeg = angleAt(offset)
                        },
                        onDrag = { change, _ ->
                            if (!latestEnabled.value) return@detectDragGestures
                            change.consume()

                            val angleNow = angleAt(change.position)
                            // Normalisasi selisih sudut ke rentang -180..180
                            // biar gak "loncat" pas ngelewatin batas 180/-180.
                            var deltaAngle = angleNow - lastAngleDeg
                            if (deltaAngle > 180f) deltaAngle -= 360f
                            if (deltaAngle < -180f) deltaAngle += 360f
                            lastAngleDeg = angleNow

                            // Knob-nya nyapu 270 derajat dari 0 ke 1,
                            // jadi 1 derajat putaran = 1/270 perubahan value.
                            val deltaValue = deltaAngle / 270f
                            onValueChange((latestValue.value + deltaValue).coerceIn(0f, 1f))
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokeWidth = 5.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val activeColor = if (enabled) accentColor else Color(0xFF565C6B)

                // Track belakang (abu-abu redup, penuh 270 derajat)
                drawArc(
                    color = Color(0xFF3A3F4B),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                // Progress (warna aksen, sepanjang value)
                drawArc(
                    color = activeColor,
                    startAngle = 135f,
                    sweepAngle = 270f * value.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                // Body knob (bulat gradasi biar keliatan 3D dikit)
                val bodyRadius = radius - strokeWidth
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF6B7280), Color(0xFF2B2F38)),
                        center = Offset(center.x - bodyRadius * 0.3f, center.y - bodyRadius * 0.3f),
                        radius = bodyRadius * 1.6f
                    ),
                    radius = bodyRadius,
                    center = center
                )
                // Indikator jarum
                val angleDeg = 135f + 270f * value.coerceIn(0f, 1f)
                val angleRad = angleDeg * PI.toFloat() / 180f
                val needleLen = bodyRadius * 0.8f
                val start = Offset(
                    center.x + cos(angleRad) * bodyRadius * 0.25f,
                    center.y + sin(angleRad) * bodyRadius * 0.25f
                )
                val end = Offset(
                    center.x + cos(angleRad) * needleLen,
                    center.y + sin(angleRad) * needleLen
                )
                drawLine(
                    color = Color.White,
                    start = start,
                    end = end,
                    strokeWidth = 4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color(0xFFE5E7EB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
