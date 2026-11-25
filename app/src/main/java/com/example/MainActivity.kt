package com.example

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.export.PlanExporter
import com.example.geometry.DetectedRoom
import com.example.model.TempDimension
import com.example.model.UnitFormatter
import com.example.model.Vec2
import com.example.ui.canvas.DraftingCanvas
import com.example.ui.components.CustomInputKind
import com.example.ui.components.DimensionInputDialog
import com.example.ui.components.DraftingToolbar
import com.example.ui.components.PlansAndExportSheet
import com.example.ui.components.PropertiesBar
import com.example.ui.components.SymbolPickerSheet
import com.example.ui.components.TempDimensionEditDialog
import com.example.ui.components.TextLabelDialog
import com.example.ui.components.TopCommandBar
import com.example.ui.theme.ArchDraftTheme
import com.example.viewmodel.DraftingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      ArchDraftTheme {
        ArchDraftApp()
      }
    }
  }
}

@Composable
fun ArchDraftApp(viewModel: DraftingViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val savedPlans by viewModel.savedPlans.collectAsStateWithLifecycle()

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  var showSymbolPicker by remember { mutableStateOf(false) }
  var showPlansSheet by remember { mutableStateOf(false) }
  var editingTempDim by remember { mutableStateOf<TempDimension?>(null) }
  var customInputRequest by remember { mutableStateOf<Pair<CustomInputKind, Float>?>(null) }
  var pendingAddTextPos by remember { mutableStateOf<Vec2?>(null) }
  var pendingRoomLabelTarget by remember { mutableStateOf<DetectedRoom?>(null) }
  var editingTextState by remember { mutableStateOf<Triple<String, Float, Boolean>?>(null) }

  // Zero-permission SAF Export Launchers
  val exportPngLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("image/png"),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) {
          runCatching {
            val bmp = PlanExporter.renderToBitmap(state.plan, state.unitSystem, state.planName)
            context.contentResolver.openOutputStream(uri)?.use { out ->
              bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            true
          }.getOrDefault(false)
        }
        viewModel.setStatus(if (ok) "Exported PNG floor plan" else "Failed to export PNG")
      }
    }
  }

  val exportSvgLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("image/svg+xml"),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) {
          runCatching {
            val svg = PlanExporter.renderToSvg(state.plan, state.unitSystem, state.planName)
            context.contentResolver.openOutputStream(uri)?.use { out ->
              out.write(svg.toByteArray(Charsets.UTF_8))
            }
            true
          }.getOrDefault(false)
        }
        viewModel.setStatus(if (ok) "Exported SVG vector floor plan" else "Failed to export SVG")
      }
    }
  }

  val exportDxfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) {
          runCatching {
            val dxf = PlanExporter.renderToDxf(state.plan, state.unitSystem, state.planName, state.detectedRooms)
            context.contentResolver.openOutputStream(uri)?.use { out ->
              out.write(dxf.toByteArray(Charsets.UTF_8))
            }
            true
          }.getOrDefault(false)
        }
        viewModel.setStatus(if (ok) "Exported DXF CAD floor plan" else "Failed to export DXF")
      }
    }
  }

  LaunchedEffect(state.statusMessage) {
    val msg = state.statusMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(msg)
    viewModel.setStatus(null)
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.background,
  ) { _ ->
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val isWideScreen = maxWidth >= 700.dp

      // 1. Full-bleed 2D Architectural Canvas
      DraftingCanvas(
        state = state,
        onSelect = { viewModel.setSelection(it) },
        onTempDimensionTap = { td -> editingTempDim = td },
        onAddWall = { s, e -> viewModel.addWall(s, e) },
        onPlaceDoor = { p ->
          if (!viewModel.placeDoorAt(p)) {
            viewModel.setStatus("Tap near a wall to place a door")
          }
        },
        onPlaceWindow = { p ->
          if (!viewModel.placeWindowAt(p)) {
            viewModel.setStatus("Tap near a wall to place a window")
          }
        },
        onPlaceSymbol = { p -> viewModel.placeSymbolAt(p) },
        onAddDimension = { s, e, off -> viewModel.addLinearDimension(s, e, off) },
        onRoomLabelTap = { room ->
          val preset = state.activeRoomLabelPreset
          if (preset != null) {
            viewModel.placeCenteredRoomLabel(room, preset)
          } else {
            pendingRoomLabelTarget = room
          }
        },
        onRoomLabelOutside = {
          viewModel.setStatus("Tap inside a closed room to center a room label")
        },
        onRequestAddText = { p -> pendingAddTextPos = p },
        onEraseHit = { hit -> viewModel.deleteElement(hit) },
        onBeginDrag = { viewModel.beginDrag() },
        onMoveWallLive = { id, s, e -> viewModel.updateWallLive(id, s, e) },
        onMoveOpeningLive = { id, isDoor, pt -> viewModel.updateOpeningPositionLive(id, isDoor, pt) },
        onMoveSymbolLive = { id, c, rot -> viewModel.updateSymbolLive(id, c, rot) },
        onMoveDimensionLive = { id, s, e, off -> viewModel.updateDimensionLive(id, s, e, off) },
        onMoveTextLive = { id, pos -> viewModel.updateTextLive(id, pos) },
        onEndDrag = { wallId -> viewModel.endDrag(wallId) },
      )

      // 2. Top Command Bar
      TopCommandBar(
        state = state,
        isWideScreen = isWideScreen,
        onOpenPlansSheet = { showPlansSheet = true },
        onUndo = { viewModel.undo() },
        onRedo = { viewModel.redo() },
        onToggleUnit = { viewModel.toggleUnitSystem() },
        onToggleGridSnap = { viewModel.toggleGridSnap() },
        onToggleAngleSnap = { viewModel.toggleAngleSnap() },
        onAutoExteriorDims = { viewModel.addAutoExteriorInToInDimensions() },
        onFitToPlan = { viewModel.requestFitToPlan() },
        onResetView = { viewModel.requestResetView() },
        onExportPng = {
          val safeName = state.planName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
          exportPngLauncher.launch("${safeName}.png")
        },
        onExportSvg = {
          val safeName = state.planName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
          exportSvgLauncher.launch("${safeName}.svg")
        },
        onExportDxf = {
          val safeName = state.planName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
          exportDxfLauncher.launch("${safeName}.dxf")
        },
        modifier = Modifier.align(Alignment.TopCenter),
      )

      // 2b. Compact Floor Area Calculator Pill (toggles room area badges on canvas)
      if (state.detectedRooms.isNotEmpty()) {
        Surface(
          onClick = { viewModel.toggleShowRoomAreas() },
          shape = RoundedCornerShape(12.dp),
          color = if (state.showRoomAreas) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
          } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
          },
          tonalElevation = 2.dp,
          shadowElevation = 3.dp,
          modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(top = 72.dp, start = 12.dp)
            .heightIn(min = 48.dp)
            .testTag("room_area_summary_pill"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.SquareFoot,
              contentDescription = "Toggle room floor area overlay",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            val roomCount = state.detectedRooms.size
            val roomWord = if (roomCount == 1) "room" else "rooms"
            Text(
              text = "$roomCount $roomWord • ${UnitFormatter.formatArea(state.totalAreaSqMm, state.unitSystem)}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      // 3. Right-edge compact Zoom / Fit / Reset HUD
      Surface(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .padding(end = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
      ) {
        Column(
          modifier = Modifier.padding(4.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          IconButton(
            onClick = { viewModel.requestZoomStep(1.25f) },
            modifier = Modifier.testTag("zoom_in_button"),
          ) {
            Icon(Icons.Default.Add, contentDescription = "Zoom in")
          }
          IconButton(
            onClick = { viewModel.requestZoomStep(0.8f) },
            modifier = Modifier.testTag("zoom_out_button"),
          ) {
            Icon(Icons.Default.Remove, contentDescription = "Zoom out")
          }
          IconButton(
            onClick = { viewModel.requestFitToPlan() },
            modifier = Modifier.testTag("hud_fit_button"),
          ) {
            Icon(Icons.Default.FitScreen, contentDescription = "Fit to plan")
          }
          IconButton(
            onClick = { viewModel.requestResetView() },
            modifier = Modifier.testTag("hud_reset_button"),
          ) {
            Icon(Icons.Default.RestartAlt, contentDescription = "Reset view")
          }
        }
      }

      // 4. Adaptive Toolbar & Contextual Properties Bar
      if (isWideScreen) {
        DraftingToolbar(
          activeTool = state.activeTool,
          isWideScreen = true,
          onSelectTool = { viewModel.selectTool(it) },
          onOpenSymbolPicker = { showSymbolPicker = true },
          modifier = Modifier.align(Alignment.CenterStart),
        )

        PropertiesBar(
          state = state,
          onSetDefaultWallThickness = { viewModel.setDefaultWallThickness(it) },
          onSetSelectedWallThickness = { viewModel.updateSelectedWallThickness(it) },
          onSetDefaultDoorWidth = { viewModel.setDefaultDoorWidth(it) },
          onSetSelectedDoorWidth = { viewModel.updateSelectedDoorWidth(it) },
          onSetDefaultDoorType = { viewModel.setDefaultDoorType(it) },
          onSetSelectedDoorType = { viewModel.updateSelectedDoorType(it) },
          onFlipDoorSwing = { viewModel.flipSelectedDoorSwing() },
          onFlipDoorHinge = { viewModel.flipSelectedDoorHinge() },
          onSetDefaultWindowWidth = { viewModel.setDefaultWindowWidth(it) },
          onSetSelectedWindowWidth = { viewModel.updateSelectedWindowWidth(it) },
          onRotateSymbol = { viewModel.rotateSelectedSymbol(it) },
          onOpenSymbolPicker = { showSymbolPicker = true },
          onAutoExteriorDims = { viewModel.addAutoExteriorInToInDimensions() },
          onSetDefaultTextSize = { viewModel.setDefaultTextSize(it) },
          onSetDefaultTextOpaqueBg = { viewModel.setDefaultTextOpaqueBg(it) },
          onSelectRoomLabelPreset = { viewModel.setActiveRoomLabelPreset(it) },
          onEditSelectedText = { txt, size, opaque -> editingTextState = Triple(txt, size, opaque) },
          onSetSelectedTextSize = { viewModel.updateSelectedTextSize(it) },
          onToggleSelectedTextOpaqueBg = { viewModel.toggleSelectedTextOpaqueBg() },
          onRotateSelectedText = { viewModel.rotateSelectedText(it) },
          onOpenCustomInput = { kind, current -> customInputRequest = kind to current },
          onDuplicateSelected = { viewModel.duplicateSelected() },
          onDeleteSelected = { viewModel.deleteSelected() },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 14.dp, start = 84.dp),
        )
      } else {
        Column(
          modifier = Modifier.align(Alignment.BottomCenter),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          PropertiesBar(
            state = state,
            onSetDefaultWallThickness = { viewModel.setDefaultWallThickness(it) },
            onSetSelectedWallThickness = { viewModel.updateSelectedWallThickness(it) },
            onSetDefaultDoorWidth = { viewModel.setDefaultDoorWidth(it) },
            onSetSelectedDoorWidth = { viewModel.updateSelectedDoorWidth(it) },
            onSetDefaultDoorType = { viewModel.setDefaultDoorType(it) },
            onSetSelectedDoorType = { viewModel.updateSelectedDoorType(it) },
            onFlipDoorSwing = { viewModel.flipSelectedDoorSwing() },
            onFlipDoorHinge = { viewModel.flipSelectedDoorHinge() },
            onSetDefaultWindowWidth = { viewModel.setDefaultWindowWidth(it) },
            onSetSelectedWindowWidth = { viewModel.updateSelectedWindowWidth(it) },
            onRotateSymbol = { viewModel.rotateSelectedSymbol(it) },
            onOpenSymbolPicker = { showSymbolPicker = true },
            onAutoExteriorDims = { viewModel.addAutoExteriorInToInDimensions() },
            onSetDefaultTextSize = { viewModel.setDefaultTextSize(it) },
            onSetDefaultTextOpaqueBg = { viewModel.setDefaultTextOpaqueBg(it) },
            onSelectRoomLabelPreset = { viewModel.setActiveRoomLabelPreset(it) },
            onEditSelectedText = { txt, size, opaque -> editingTextState = Triple(txt, size, opaque) },
            onSetSelectedTextSize = { viewModel.updateSelectedTextSize(it) },
            onToggleSelectedTextOpaqueBg = { viewModel.toggleSelectedTextOpaqueBg() },
            onRotateSelectedText = { viewModel.rotateSelectedText(it) },
            onOpenCustomInput = { kind, current -> customInputRequest = kind to current },
            onDuplicateSelected = { viewModel.duplicateSelected() },
            onDeleteSelected = { viewModel.deleteSelected() },
          )
          DraftingToolbar(
            activeTool = state.activeTool,
            isWideScreen = false,
            onSelectTool = { viewModel.selectTool(it) },
            onOpenSymbolPicker = { showSymbolPicker = true },
          )
        }
      }
    }
  }

  // Sheets & Dialogs
  if (showSymbolPicker) {
    SymbolPickerSheet(
      activeSymbol = state.activeSymbolType,
      unitSystem = state.unitSystem,
      onSelectSymbol = { viewModel.selectSymbolForPlacement(it) },
      onDismiss = { showSymbolPicker = false },
    )
  }

  if (showPlansSheet) {
    PlansAndExportSheet(
      currentPlanId = state.planId,
      currentPlanName = state.planName,
      savedPlans = savedPlans,
      onNewEmptyPlan = { name -> viewModel.createNewPlan(name, loadStarter = false) },
      onLoadStarterPlan = { viewModel.createNewPlan("Studio Concept", loadStarter = true) },
      onClearCanvas = { viewModel.clearCanvas() },
      onSaveAs = { name -> viewModel.savePlanAs(name) },
      onLoadPlan = { entity -> viewModel.loadSavedPlan(entity) },
      onDeletePlan = { id -> viewModel.deleteSavedPlan(id) },
      onExportPng = {
        val safeName = state.planName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        exportPngLauncher.launch("${safeName}.png")
      },
      onExportSvg = {
        val safeName = state.planName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        exportSvgLauncher.launch("${safeName}.svg")
      },
      onExportDxf = {
        val safeName = state.planName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        exportDxfLauncher.launch("${safeName}.dxf")
      },
      onAutoExteriorDims = { viewModel.addAutoExteriorInToInDimensions() },
      onDismiss = { showPlansSheet = false },
    )
  }

  editingTempDim?.let { td ->
    TempDimensionEditDialog(
      tempDim = td,
      unitSystem = state.unitSystem,
      onApply = { newMm ->
        viewModel.applyTempDimensionEdit(td, newMm)
        editingTempDim = null
      },
      onDismiss = { editingTempDim = null },
    )
  }

  customInputRequest?.let { (kind, currentMm) ->
    val title = when (kind) {
      CustomInputKind.WALL_THICKNESS_DEFAULT, CustomInputKind.WALL_THICKNESS_SELECTED -> "Wall Thickness"
      CustomInputKind.DOOR_WIDTH_DEFAULT, CustomInputKind.DOOR_WIDTH_SELECTED -> "Door Width"
      CustomInputKind.WINDOW_WIDTH_DEFAULT, CustomInputKind.WINDOW_WIDTH_SELECTED -> "Window Width"
      CustomInputKind.WINDOW_SILL_SELECTED -> "Window Sill Height"
    }
    DimensionInputDialog(
      title = title,
      subtitle = "Enter custom measurement in mm, cm, m, or ft-in.",
      initialMm = currentMm,
      unitSystem = state.unitSystem,
      onConfirmMm = { mm ->
        when (kind) {
          CustomInputKind.WALL_THICKNESS_DEFAULT -> viewModel.setDefaultWallThickness(mm)
          CustomInputKind.WALL_THICKNESS_SELECTED -> viewModel.updateSelectedWallThickness(mm)
          CustomInputKind.DOOR_WIDTH_DEFAULT -> viewModel.setDefaultDoorWidth(mm)
          CustomInputKind.DOOR_WIDTH_SELECTED -> viewModel.updateSelectedDoorWidth(mm)
          CustomInputKind.WINDOW_WIDTH_DEFAULT -> viewModel.setDefaultWindowWidth(mm)
          CustomInputKind.WINDOW_WIDTH_SELECTED -> viewModel.updateSelectedWindowWidth(mm)
          CustomInputKind.WINDOW_SILL_SELECTED -> viewModel.updateSelectedWindowSill(mm)
        }
        customInputRequest = null
      },
      onDismiss = { customInputRequest = null },
    )
  }

  pendingRoomLabelTarget?.let { room ->
    TextLabelDialog(
      title = "Room Name (${UnitFormatter.formatArea(room.areaSqMm, state.unitSystem)})",
      initialText = "",
      initialSizeMm = state.defaultTextSizeMm,
      initialOpaqueBg = state.defaultTextOpaqueBg,
      onConfirm = { text, sizeMm, opaqueBg ->
        viewModel.setDefaultTextSize(sizeMm)
        viewModel.setDefaultTextOpaqueBg(opaqueBg)
        viewModel.placeCenteredRoomLabel(room, text, sizeMm, opaqueBg)
        pendingRoomLabelTarget = null
      },
      onDismiss = { pendingRoomLabelTarget = null },
    )
  }

  pendingAddTextPos?.let { pos ->
    TextLabelDialog(
      title = "Add Text Label",
      initialText = "",
      initialSizeMm = state.defaultTextSizeMm,
      initialOpaqueBg = state.defaultTextOpaqueBg,
      onConfirm = { text, sizeMm, opaqueBg ->
        viewModel.setDefaultTextSize(sizeMm)
        viewModel.setDefaultTextOpaqueBg(opaqueBg)
        viewModel.addTextLabel(text, pos, sizeMm, opaqueBg)
        pendingAddTextPos = null
      },
      onDismiss = { pendingAddTextPos = null },
    )
  }

  editingTextState?.let { (curText, curSize, curOpaque) ->
    TextLabelDialog(
      title = "Edit Text Label",
      initialText = curText,
      initialSizeMm = curSize,
      initialOpaqueBg = curOpaque,
      onConfirm = { text, sizeMm, opaqueBg ->
        viewModel.updateSelectedTextContent(text, sizeMm, opaqueBg)
        editingTextState = null
      },
      onDismiss = { editingTextState = null },
    )
  }
}
