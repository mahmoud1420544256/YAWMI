package com.example.feature.tasks.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YawmiTimePickerDialog(
  title: String,
  initialTime: LocalTime,
  onConfirm: (LocalTime) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  initialInputMode: Boolean = false,
) {
  val currentLanguage =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) AppLanguage.ARABIC
    else AppLanguage.ENGLISH
  val timePickerState =
    rememberTimePickerState(
      initialHour = initialTime.hour,
      initialMinute = initialTime.minute,
      is24Hour = true,
    )
  var useTextInput by rememberSaveable { mutableStateOf(initialInputMode) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    ProvideYawmiLocale(language = currentLanguage) {
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier =
          modifier
            .widthIn(min = 280.dp, max = 380.dp)
            .padding(horizontal = 16.dp)
            .testTag("yawmi_time_picker_dialog"),
      ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          IconButton(
            onClick = { useTextInput = !useTextInput },
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("yawmi_time_picker_mode_toggle"),
          ) {
            Icon(
              imageVector =
                if (useTextInput) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
              contentDescription =
                stringResource(
                  if (useTextInput) R.string.task_time_picker_switch_to_dial
                  else R.string.task_time_picker_switch_to_input
                ),
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Keep clock dial geometry and HH:mm numeric fields in natural LTR order even under Arabic RTL.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          if (useTextInput) {
            TimeInput(
              state = timePickerState,
              modifier = Modifier.testTag("yawmi_time_picker_input"),
            )
          } else {
            TimePicker(
              state = timePickerState,
              modifier = Modifier.testTag("yawmi_time_picker_dial"),
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = onDismiss,
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("yawmi_time_picker_cancel_btn"),
          ) {
            Text(stringResource(R.string.action_cancel))
          }
          TextButton(
            onClick = {
              onConfirm(LocalTime.of(timePickerState.hour, timePickerState.minute))
            },
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("yawmi_time_picker_confirm_btn"),
          ) {
            Text(stringResource(R.string.action_confirm))
          }
        }
      }
      }
    }
  }
}
