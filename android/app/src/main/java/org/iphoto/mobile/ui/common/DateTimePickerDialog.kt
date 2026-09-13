package org.iphoto.mobile.ui.common

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Two-step date -> time picker (mirrors the native DatePickerDialog -> TimePickerDialog chaining
 * feel) used for editing a photo's taken-at date, single or multi-select alike (the caller decides
 * what "confirm" does with the result).
 *
 * Material3's DatePicker works in UTC internally (selectedDateMillis is UTC midnight of the picked
 * calendar date, regardless of device timezone) - both the initial value and the read-back convert
 * through ZoneOffset.UTC to avoid the date silently shifting by a day near midnight in timezones
 * behind/ahead of UTC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit
) {
    var step by remember { mutableIntStateOf(0) } // 0 = date, 1 = time

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = is24Hour
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "Pick date" else "Pick time") },
        text = {
            if (step == 0) {
                DatePicker(state = dateState, showModeToggle = false)
            } else {
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (step == 0) {
                    step = 1
                } else {
                    val millis = dateState.selectedDateMillis
                    if (millis == null) {
                        onDismiss()
                    } else {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute)))
                    }
                }
            }) {
                Text(if (step == 0) "Next" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
