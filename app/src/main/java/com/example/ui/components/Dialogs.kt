package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.data.PlanEntity
import com.example.model.DraftingPresets
import com.example.model.TempDimension
import com.example.model.UnitFormatter
import com.example.model.UnitSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DimensionInputDialog(
  title: String,
  subtitle: String,
  initialMm: Float,
  unitSystem: UnitSystem,
  onConfirmMm: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  val initialStr = remember(initialMm, unitSystem) {
    UnitFormatter.formatEditableValue(initialMm, unitSystem)
  }
  var fieldValue by remember(initialStr) {
    mutableStateOf(TextFieldValue(initialStr, selection = TextRange(0, initialStr.length)))
  }
  var errorText by remember { mutableStateOf<String?>(null) }
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  fun submit() {
    val parsed = UnitFormatter.parseDimensionInput(fieldValue.text, unitSystem)
    if (parsed == null) {
      errorText = "Enter e.g. 1200, 1.2m, or 4'6\""
    } else {
      onConfirmMm(parsed)
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
    text = {
      Column {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = fieldValue,
          onValueChange = {
            fieldValue = it
            errorText = null
          },
          singleLine = true,
          isError = errorText != null,
          label = { Text(if (unitSystem == UnitSystem.METRIC) "Distance (mm, cm, m, or ft-in)" else "Distance (ft-in, in, or mm)") },
          supportingText = {
            Text(errorText ?: "Examples: 1200, 1.5m, 4'6\", 36\"")
          },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("dimension_input_field"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { submit() },
        modifier = Modifier.testTag("dimension_input_confirm"),
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
fun TempDimensionEditDialog(
  tempDim: TempDimension,
  unitSystem: UnitSystem,
  onApply: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  DimensionInputDialog(
    title = "Adjust Exact Distance",
    subtitle = "Current distance: ${UnitFormatter.formatLength(tempDim.distanceMm, unitSystem)}",
    initialMm = tempDim.distanceMm,
    unitSystem = unitSystem,
    onConfirmMm = onApply,
    onDismiss = onDismiss,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansAndExportSheet(
  currentPlanId: String,
  currentPlanName: String,
  savedPlans: List<PlanEntity>,
  onNewEmptyPlan: (String) -> Unit,
  onLoadStarterPlan: () -> Unit,
  onClearCanvas: () -> Unit,
  onSaveAs: (String) -> Unit,
  onLoadPlan: (PlanEntity) -> Unit,
  onDeletePlan: (String) -> Unit,
  onExportPng: () -> Unit,
  onExportSvg: () -> Unit,
  onExportDxf: () -> Unit,
  onAutoExteriorDims: () -> Unit,
  onDismiss: () -> Unit,
) {
  var showSaveAsDialog by remember { mutableStateOf(false) }
  var showNewPlanDialog by remember { mutableStateOf(false) }
  val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
    ) {
      Text(
        text = "Floor Plans & Export",
        style = MaterialTheme.typography.titleLarge,
      )
      Spacer(Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = { showNewPlanDialog = true },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("new_plan_button"),
        ) {
          Icon(Icons.Default.Add, null)
          Spacer(Modifier.width(6.dp))
          Text("New Plan")
        }
        OutlinedButton(
          onClick = { showSaveAsDialog = true },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("save_as_button"),
        ) {
          Icon(Icons.Default.Save, null)
          Spacer(Modifier.width(6.dp))
          Text("Save Copy")
        }
      }

      Spacer(Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = {
            onLoadStarterPlan()
            onDismiss()
          },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp),
        ) {
          Icon(Icons.Default.Home, null)
          Spacer(Modifier.width(6.dp))
          Text("Sample Plan")
        }
        OutlinedButton(
          onClick = {
            onClearCanvas()
            onDismiss()
          },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("clear_canvas_button"),
        ) {
          Icon(Icons.Default.CleaningServices, null)
          Spacer(Modifier.width(6.dp))
          Text("Clear Canvas")
        }
      }

      Spacer(Modifier.height(8.dp))

      OutlinedButton(
        onClick = {
          onAutoExteriorDims()
          onDismiss()
        },
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("sheet_auto_exterior_dims_button"),
      ) {
        Text("Generate Automatic Exterior Dimensions (In-to-In)")
      }

      Spacer(Modifier.height(12.dp))
      Text("Export Drawing", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = {
            onDismiss()
            onExportPng()
          },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("export_png_button"),
        ) {
          Icon(Icons.Default.Image, null)
          Spacer(Modifier.width(4.dp))
          Text("PNG")
        }
        OutlinedButton(
          onClick = {
            onDismiss()
            onExportSvg()
          },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("export_svg_button"),
        ) {
          Icon(Icons.Default.Image, null)
          Spacer(Modifier.width(4.dp))
          Text("SVG")
        }
        OutlinedButton(
          onClick = {
            onDismiss()
            onExportDxf()
          },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("export_dxf_button"),
        ) {
          Icon(Icons.Default.Image, null)
          Spacer(Modifier.width(4.dp))
          Text("DXF")
        }
      }

      Spacer(Modifier.height(16.dp))
      HorizontalDivider()
      Spacer(Modifier.height(12.dp))
      Text("Saved Local Plans", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(8.dp))

      LazyColumn(
        modifier = Modifier.heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(savedPlans, key = { it.id }) { plan ->
          val isActive = plan.id == currentPlanId
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onLoadPlan(plan)
                onDismiss()
              },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Updated ${dateFmt.format(Date(plan.updatedAt))}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (!isActive) {
                IconButton(onClick = { onDeletePlan(plan.id) }) {
                  Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete saved plan ${plan.name}",
                    tint = MaterialTheme.colorScheme.error,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (showSaveAsDialog) {
    PlanNameDialog(
      title = "Save Plan Copy",
      initialName = "$currentPlanName Copy",
      onConfirm = { name ->
        showSaveAsDialog = false
        onSaveAs(name)
        onDismiss()
      },
      onDismiss = { showSaveAsDialog = false },
    )
  }

  if (showNewPlanDialog) {
    PlanNameDialog(
      title = "New Blank Plan",
      initialName = "Concept Plan",
      onConfirm = { name ->
        showNewPlanDialog = false
        onNewEmptyPlan(name)
        onDismiss()
      },
      onDismiss = { showNewPlanDialog = false },
    )
  }
}

@Composable
private fun PlanNameDialog(
  title: String,
  initialName: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        singleLine = true,
        label = { Text("Plan name") },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onConfirm(name) }) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
fun TextLabelDialog(
  title: String,
  initialText: String,
  initialSizeMm: Float,
  initialOpaqueBg: Boolean,
  onConfirm: (String, Float, Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  var value by remember(initialText) {
    mutableStateOf(TextFieldValue(initialText, selection = TextRange(0, initialText.length)))
  }
  var selectedSizeMm by remember(initialSizeMm) { mutableStateOf(initialSizeMm) }
  var opaqueBg by remember(initialOpaqueBg) { mutableStateOf(initialOpaqueBg) }
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
    text = {
      Column {
        OutlinedTextField(
          value = value,
          onValueChange = { value = it },
          singleLine = true,
          label = { Text("Label or note") },
          placeholder = { Text("e.g. Living Room, Master Bath") },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(
            onDone = {
              if (value.text.trim().isNotEmpty()) {
                onConfirm(value.text.trim(), selectedSizeMm, opaqueBg)
              }
            },
          ),
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("text_label_input_field"),
        )
        Spacer(Modifier.height(10.dp))
        Text("Quick Presets", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          DraftingPresets.roomLabelPresets.forEach { preset ->
            FilterChip(
              selected = value.text.equals(preset, ignoreCase = true),
              onClick = {
                value = TextFieldValue(preset, selection = TextRange(preset.length))
              },
              label = { Text(preset) },
              modifier = Modifier.heightIn(min = 48.dp),
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        Text("Text Size", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DraftingPresets.textSizesMm.forEach { (sizeMm, label) ->
            FilterChip(
              selected = kotlin.math.abs(selectedSizeMm - sizeMm) < 10f,
              onClick = { selectedSizeMm = sizeMm },
              label = { Text(label) },
              modifier = Modifier.heightIn(min = 48.dp),
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        Text("Background Style", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = opaqueBg,
            onClick = { opaqueBg = true },
            label = { Text("Opaque") },
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("dialog_text_bg_opaque_chip"),
          )
          FilterChip(
            selected = !opaqueBg,
            onClick = { opaqueBg = false },
            label = { Text("Transparent") },
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("dialog_text_bg_transparent_chip"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (value.text.trim().isNotEmpty()) {
            onConfirm(value.text.trim(), selectedSizeMm, opaqueBg)
          }
        },
        enabled = value.text.trim().isNotEmpty(),
        modifier = Modifier.testTag("text_label_confirm_button"),
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
