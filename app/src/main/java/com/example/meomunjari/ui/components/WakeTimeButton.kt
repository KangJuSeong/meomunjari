package com.example.meomunjari.ui.components

import android.app.TimePickerDialog
import android.content.DialogInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime

@Composable
fun WakeTimeButton(value: LocalTime?, onSelect: (LocalTime?) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    SecondaryButton(
        text = "기상 예정 " + (value?.toString() ?: "설정하기 · 기본 8시간"),
        onClick = { open = true },
        enabled = enabled,
    )
    val context = LocalContext.current
    if (open && enabled) DisposableEffect(context) {
        val initial = value ?: LocalTime.of(7, 0)
        val dialog = TimePickerDialog(context, { _, hour, minute -> onSelect(LocalTime.of(hour, minute)) },
            initial.hour, initial.minute, true)
        dialog.setTitle("기상 예정 시각")
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "기본 8시간") { _, _ -> onSelect(null) }
        dialog.setOnDismissListener { open = false }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}
