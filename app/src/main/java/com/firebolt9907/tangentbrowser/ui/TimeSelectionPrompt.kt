package com.firebolt9907.tangentbrowser.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import mozilla.components.concept.engine.prompt.PromptRequest
import java.util.Calendar
import java.util.Date

/**
 * Presents a native Android date/time picker for an HTML `<input type="date|time|datetime-local|
 * month">` element (a [PromptRequest.TimeSelection]). Without this, tapping those inputs did nothing —
 * they fell through to the unhandled branch. Mirrors Fenix, which also drives these with the platform
 * pickers. [onFinished] always runs exactly once (confirm or cancel) so the caller can clear the
 * pending prompt.
 */
fun showTimeSelectionPrompt(
    context: Context,
    request: PromptRequest.TimeSelection,
    onFinished: () -> Unit,
) {
    val calendar = Calendar.getInstance().apply { time = request.initialDate }
    // Guard so onDismiss/onConfirm + onFinished can never both fire (dialog dismiss listener plus a
    // button callback would otherwise double-invoke).
    var settled = false
    fun confirm(date: Date) {
        if (settled) return
        settled = true
        request.onConfirm(date)
        onFinished()
    }
    fun cancel() {
        if (settled) return
        settled = true
        request.onDismiss()
        onFinished()
    }

    when (request.type) {
        PromptRequest.TimeSelection.Type.TIME -> {
            showTimePicker(context, calendar, ::confirm, ::cancel)
        }
        PromptRequest.TimeSelection.Type.DATE,
        PromptRequest.TimeSelection.Type.MONTH -> {
            showDatePicker(context, calendar, request, onDate = { c -> confirm(c.time) }, onCancel = ::cancel)
        }
        PromptRequest.TimeSelection.Type.DATE_AND_TIME -> {
            // Chain: pick the date first, then the time, committing the combined value.
            showDatePicker(
                context,
                calendar,
                request,
                onDate = { dateCal ->
                    showTimePicker(
                        context,
                        dateCal,
                        onConfirm = { timeDate ->
                            val merged = Calendar.getInstance().apply {
                                time = timeDate
                                set(Calendar.YEAR, dateCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, dateCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, dateCal.get(Calendar.DAY_OF_MONTH))
                            }
                            confirm(merged.time)
                        },
                        onCancel = ::cancel,
                    )
                },
                onCancel = ::cancel,
            )
        }
    }
}

private fun showDatePicker(
    context: Context,
    calendar: Calendar,
    request: PromptRequest.TimeSelection,
    onDate: (Calendar) -> Unit,
    onCancel: () -> Unit,
) {
    val dialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val picked = Calendar.getInstance().apply {
                time = calendar.time
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
            }
            onDate(picked)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    )
    request.minimumDate?.let { dialog.datePicker.minDate = it.time }
    request.maximumDate?.let { dialog.datePicker.maxDate = it.time }
    dialog.setOnCancelListener { onCancel() }
    dialog.show()
}

private fun showTimePicker(
    context: Context,
    calendar: Calendar,
    onConfirm: (Date) -> Unit,
    onCancel: () -> Unit,
) {
    val dialog = TimePickerDialog(
        context,
        { _, hourOfDay, minute ->
            val picked = Calendar.getInstance().apply {
                time = calendar.time
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
            }
            onConfirm(picked.time)
        },
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        android.text.format.DateFormat.is24HourFormat(context),
    )
    dialog.setOnCancelListener { onCancel() }
    dialog.show()
}
