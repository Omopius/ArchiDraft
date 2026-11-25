package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PlanEntity
import com.example.data.PlanJsonCodec
import com.example.data.PlanRepository
import com.example.data.PreferencesRepository
import com.example.geometry.DetectedRoom
import com.example.geometry.GeometryMath
import com.example.geometry.RoomAreaEngine
import com.example.geometry.TempDimensionEngine
import com.example.model.DoorOpening
import com.example.model.DoorType
import com.example.model.DraftingTool
import com.example.model.FloorPlanData
import com.example.model.LinearDimension
import com.example.model.PlacedSymbol
import com.example.model.SamplePlans
import com.example.model.SelectionTarget
import com.example.model.SymbolType
import com.example.model.TempDimension
import com.example.model.TextLabel
import com.example.model.UnitSystem
import com.example.model.Vec2
import com.example.model.Wall
import com.example.model.WindowOpening
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val initialStarterPlan = SamplePlans.starterApartment()
private val initialDetectedRooms = RoomAreaEngine.detectRooms(initialStarterPlan)

data class DraftingUiState(
  val planId: String = "starter_studio_v2",
  val planName: String = "Studio Concept",
  val plan: FloorPlanData = initialStarterPlan,
  val detectedRooms: List<DetectedRoom> = initialDetectedRooms,
  val totalAreaSqMm: Float = initialDetectedRooms.sumOf { it.areaSqMm.toDouble() }.toFloat(),
  val showRoomAreas: Boolean = true,
  val activeTool: DraftingTool = DraftingTool.SELECT,
  val selection: SelectionTarget? = null,
  val tempDimensions: List<TempDimension> = emptyList(),
  val unitSystem: UnitSystem = UnitSystem.METRIC,
  val gridSnap: Boolean = true,
  val angleSnap: Boolean = true,
  val defaultWallThicknessMm: Float = 150f,
  val defaultDoorWidthMm: Float = 900f,
  val defaultDoorType: DoorType = DoorType.SINGLE_SWING,
  val defaultWindowWidthMm: Float = 1200f,
  val defaultWindowSillMm: Float = 900f,
  val activeSymbolType: SymbolType = SymbolType.SOFA_3SEAT,
  val defaultTextSizeMm: Float = 220f,
  val defaultTextOpaqueBg: Boolean = true,
  val activeRoomLabelPreset: String? = null,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val statusMessage: String? = null,
  val fitRequestCounter: Int = 0,
  val resetRequestCounter: Int = 0,
  val zoomStepDelta: Pair<Int, Float> = 0 to 1f,
)

class DraftingViewModel(application: Application) : AndroidViewModel(application) {

  private val planRepository: PlanRepository
  private val prefsRepository = PreferencesRepository(application)

  private val _uiState = MutableStateFlow(DraftingUiState())
  val uiState: StateFlow<DraftingUiState> = _uiState.asStateFlow()

  val savedPlans: StateFlow<List<PlanEntity>>

  private val undoStack = ArrayDeque<FloorPlanData>()
  private val redoStack = ArrayDeque<FloorPlanData>()
  private var dragStartSnapshot: FloorPlanData? = null

  init {
    val db = AppDatabase.getInstance(application)
    planRepository = PlanRepository(db.planDao())
    savedPlans = planRepository.allPlans.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

    viewModelScope.launch {
      val prefs = prefsRepository.preferences.first()
      _uiState.update {
        it.copy(
          unitSystem = prefs.unitSystem,
          gridSnap = prefs.gridSnap,
          angleSnap = prefs.angleSnap,
          defaultWallThicknessMm = prefs.defaultWallThicknessMm,
        )
      }

      // Ensure starter plan exists in local DB on first launch, or load last active plan
      val existing = planRepository.getPlanById(prefs.activePlanId)
      if (existing != null) {
        val parsed = PlanJsonCodec.fromJson(existing.jsonContent)
        if (parsed != null) {
          val rooms = RoomAreaEngine.detectRooms(parsed)
          _uiState.update {
            it.copy(
              planId = existing.id,
              planName = existing.name,
              plan = parsed,
              detectedRooms = rooms,
              totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
              fitRequestCounter = it.fitRequestCounter + 1,
            )
          }
          return@launch
        }
      }

      val starter = SamplePlans.starterApartment()
      persistCurrentPlan(
        id = "starter_studio_v2",
        name = "Studio Concept",
        plan = starter,
      )
      _uiState.update { it.copy(fitRequestCounter = it.fitRequestCounter + 1) }
    }
  }

  private fun commitPlanMutation(newPlan: FloorPlanData, newSelection: SelectionTarget? = _uiState.value.selection) {
    val current = _uiState.value
    if (newPlan != current.plan) {
      undoStack.addLast(current.plan)
      if (undoStack.size > 50) undoStack.removeFirst()
      redoStack.clear()
    }
    val validSelection = validateSelection(newPlan, newSelection)
    val tempDims = TempDimensionEngine.computeForSelection(newPlan, validSelection)
    val rooms = RoomAreaEngine.detectRooms(newPlan)
    _uiState.update {
      it.copy(
        plan = newPlan,
        detectedRooms = rooms,
        totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
        selection = validSelection,
        tempDimensions = tempDims,
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
      )
    }
    autoSave()
  }

  private fun validateSelection(plan: FloorPlanData, sel: SelectionTarget?): SelectionTarget? {
    if (sel == null) return null
    return when (sel) {
      is SelectionTarget.SelectedWall -> if (plan.walls.any { it.id == sel.id }) sel else null
      is SelectionTarget.SelectedDoor -> if (plan.doors.any { it.id == sel.id }) sel else null
      is SelectionTarget.SelectedWindow -> if (plan.windows.any { it.id == sel.id }) sel else null
      is SelectionTarget.SelectedSymbol -> if (plan.symbols.any { it.id == sel.id }) sel else null
      is SelectionTarget.SelectedDimension -> if (plan.dimensions.any { it.id == sel.id }) sel else null
      is SelectionTarget.SelectedText -> if (plan.texts.any { it.id == sel.id }) sel else null
    }
  }

  fun selectTool(tool: DraftingTool) {
    _uiState.update { state ->
      val keepSelection = if (tool == DraftingTool.SELECT) state.selection else null
      state.copy(
        activeTool = tool,
        selection = keepSelection,
        tempDimensions = if (keepSelection != null) TempDimensionEngine.computeForSelection(state.plan, keepSelection) else emptyList(),
      )
    }
  }

  fun setSelection(target: SelectionTarget?) {
    _uiState.update { state ->
      val valid = validateSelection(state.plan, target)
      state.copy(
        selection = valid,
        tempDimensions = TempDimensionEngine.computeForSelection(state.plan, valid),
      )
    }
  }

  fun toggleUnitSystem() {
    val next = if (_uiState.value.unitSystem == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC
    _uiState.update { it.copy(unitSystem = next) }
    viewModelScope.launch { prefsRepository.setUnitSystem(next) }
  }

  fun toggleGridSnap() {
    val next = !_uiState.value.gridSnap
    _uiState.update { it.copy(gridSnap = next) }
    viewModelScope.launch { prefsRepository.setGridSnap(next) }
  }

  fun toggleAngleSnap() {
    val next = !_uiState.value.angleSnap
    _uiState.update { it.copy(angleSnap = next) }
    viewModelScope.launch { prefsRepository.setAngleSnap(next) }
  }

  fun setDefaultWallThickness(mm: Float) {
    val safe = mm.coerceIn(50f, 600f)
    _uiState.update { it.copy(defaultWallThicknessMm = safe) }
    viewModelScope.launch { prefsRepository.setDefaultWallThickness(safe) }
  }

  fun setDefaultDoorWidth(mm: Float) {
    _uiState.update { it.copy(defaultDoorWidthMm = mm.coerceIn(500f, 2400f)) }
  }

  fun setDefaultDoorType(type: DoorType) {
    _uiState.update { it.copy(defaultDoorType = type) }
  }

  fun setDefaultWindowWidth(mm: Float) {
    _uiState.update { it.copy(defaultWindowWidthMm = mm.coerceIn(300f, 3600f)) }
  }

  fun setDefaultWindowSill(mm: Float) {
    _uiState.update { it.copy(defaultWindowSillMm = mm.coerceIn(0f, 2400f)) }
  }

  fun selectSymbolForPlacement(type: SymbolType) {
    _uiState.update { it.copy(activeSymbolType = type, activeTool = DraftingTool.FIXTURE) }
  }

  fun addWall(start: Vec2, end: Vec2) {
    if (GeometryMath.distance(start, end) < 80f) return
    val state = _uiState.value
    val rawWall = Wall(
      start = start,
      end = end,
      thicknessMm = state.defaultWallThicknessMm,
    )
    val snappedWall = GeometryMath.snapWallEndpointJoin(rawWall, state.plan.walls)
    val nextPlan = state.plan.copy(walls = state.plan.walls + snappedWall)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedWall(snappedWall.id))
  }

  fun placeDoorAt(worldPoint: Vec2): Boolean {
    val state = _uiState.value
    val proj = GeometryMath.findNearestWall(worldPoint, state.plan.walls, maxDistanceMm = 450f)
      ?: return false
    val t = GeometryMath.clampOpeningT(proj.wall, proj.t, state.defaultDoorWidthMm)
    val door = DoorOpening(
      wallId = proj.wall.id,
      t = t,
      widthMm = state.defaultDoorWidthMm,
      doorType = state.defaultDoorType,
      swingLeft = true,
      flipHinge = false,
    )
    val nextPlan = state.plan.copy(doors = state.plan.doors + door)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedDoor(door.id))
    return true
  }

  fun placeWindowAt(worldPoint: Vec2): Boolean {
    val state = _uiState.value
    val proj = GeometryMath.findNearestWall(worldPoint, state.plan.walls, maxDistanceMm = 450f)
      ?: return false
    val t = GeometryMath.clampOpeningT(proj.wall, proj.t, state.defaultWindowWidthMm)
    val win = WindowOpening(
      wallId = proj.wall.id,
      t = t,
      widthMm = state.defaultWindowWidthMm,
      sillHeightMm = state.defaultWindowSillMm,
    )
    val nextPlan = state.plan.copy(windows = state.plan.windows + win)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedWindow(win.id))
    return true
  }

  fun placeSymbolAt(worldPoint: Vec2, type: SymbolType = _uiState.value.activeSymbolType) {
    val state = _uiState.value
    val snapped = if (state.gridSnap) {
      GeometryMath.snapPointToGrid(worldPoint, GeometryMath.gridIncrementMm(state.unitSystem))
    } else {
      worldPoint
    }
    val sym = PlacedSymbol(
      type = type,
      center = snapped,
      rotationDeg = 0f,
      widthMm = type.defaultWidthMm,
      depthMm = type.defaultDepthMm,
    )
    val nextPlan = state.plan.copy(symbols = state.plan.symbols + sym)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedSymbol(sym.id))
  }

  fun addLinearDimension(start: Vec2, end: Vec2, offsetMm: Float = 450f) {
    if (GeometryMath.distance(start, end) < 80f) return
    val dim = LinearDimension(start = start, end = end, offsetMm = offsetMm)
    val nextPlan = _uiState.value.plan.copy(dimensions = _uiState.value.plan.dimensions + dim)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedDimension(dim.id))
  }

  fun toggleShowRoomAreas() {
    _uiState.update { it.copy(showRoomAreas = !it.showRoomAreas) }
  }

  fun addAutoExteriorInToInDimensions() {
    val state = _uiState.value
    val generated = GeometryMath.generateExteriorInToInDimensions(state.plan.walls)
    if (generated.isEmpty()) {
      setStatus("Draw closed rooms first to generate exterior in-to-in dimensions")
      return
    }
    val taggedGenerated = generated.mapIndexed { idx, dim ->
      dim.copy(id = "dim_ext_auto_${idx}_${UUID.randomUUID().toString().take(6)}")
    }
    val remainingDims = state.plan.dimensions.filterNot {
      it.id.startsWith("dim_ext_")
    }
    val nextPlan = state.plan.copy(dimensions = remainingDims + taggedGenerated)
    commitPlanMutation(nextPlan)
    setStatus("Updated ${taggedGenerated.size} exterior in-to-in dimensions")
  }

  fun setDefaultTextSize(mm: Float) {
    _uiState.update { it.copy(defaultTextSizeMm = mm.coerceIn(100f, 600f)) }
  }

  fun setDefaultTextOpaqueBg(opaque: Boolean) {
    _uiState.update { it.copy(defaultTextOpaqueBg = opaque) }
  }

  fun setActiveRoomLabelPreset(preset: String?) {
    _uiState.update { it.copy(activeRoomLabelPreset = preset) }
  }

  fun placeCenteredRoomLabel(
    room: DetectedRoom,
    text: String,
    fontSizeMm: Float = _uiState.value.defaultTextSizeMm,
    hasOpaqueBackground: Boolean = _uiState.value.defaultTextOpaqueBg,
  ) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    val state = _uiState.value
    val existingCentered = state.plan.texts.firstOrNull {
      GeometryMath.distance(it.position, room.center) <= 320f
    }
    val targetLabel: TextLabel
    val nextTexts = if (existingCentered != null) {
      targetLabel = existingCentered.copy(
        text = clean,
        position = room.center,
        fontSizeMm = fontSizeMm.coerceIn(100f, 600f),
        hasOpaqueBackground = hasOpaqueBackground,
      )
      state.plan.texts.map { if (it.id == existingCentered.id) targetLabel else it }
    } else {
      targetLabel = TextLabel(
        text = clean,
        position = room.center,
        fontSizeMm = fontSizeMm.coerceIn(100f, 600f),
        hasOpaqueBackground = hasOpaqueBackground,
      )
      state.plan.texts + targetLabel
    }
    val nextPlan = state.plan.copy(texts = nextTexts)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedText(targetLabel.id))
    setStatus("Centered \"$clean\" in room")
  }

  fun addTextLabel(
    text: String,
    position: Vec2,
    fontSizeMm: Float = _uiState.value.defaultTextSizeMm,
    hasOpaqueBackground: Boolean = _uiState.value.defaultTextOpaqueBg,
  ) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    val state = _uiState.value
    val snapped = if (state.gridSnap) {
      GeometryMath.snapPointToGrid(position, GeometryMath.gridIncrementMm(state.unitSystem))
    } else {
      position
    }
    val label = TextLabel(
      text = clean,
      position = snapped,
      fontSizeMm = fontSizeMm.coerceIn(100f, 600f),
      hasOpaqueBackground = hasOpaqueBackground,
    )
    val nextPlan = state.plan.copy(texts = state.plan.texts + label)
    commitPlanMutation(nextPlan, SelectionTarget.SelectedText(label.id))
  }

  // Live drag updates (do not flood undo stack until drag ends)
  fun beginDrag() {
    dragStartSnapshot = _uiState.value.plan
  }

  fun updateWallLive(wallId: String, newStart: Vec2, newEnd: Vec2) {
    _uiState.update { state ->
      val updated = state.plan.copy(
        walls = state.plan.walls.map { if (it.id == wallId) it.copy(start = newStart, end = newEnd) else it },
      )
      val rooms = RoomAreaEngine.detectRooms(updated)
      state.copy(
        plan = updated,
        detectedRooms = rooms,
        totalAreaSqMm = rooms.sumOf { it.areaSqMm.toDouble() }.toFloat(),
        tempDimensions = TempDimensionEngine.computeForSelection(updated, state.selection),
      )
    }
  }

  fun updateOpeningPositionLive(id: String, isDoor: Boolean, worldPoint: Vec2) {
    _uiState.update { state ->
      val proj = GeometryMath.findNearestWall(worldPoint, state.plan.walls, maxDistanceMm = 800f)
        ?: return@update state
      val updated = if (isDoor) {
        state.plan.copy(
          doors = state.plan.doors.map { d ->
            if (d.id != id) d
            else d.copy(
              wallId = proj.wall.id,
              t = GeometryMath.clampOpeningT(proj.wall, proj.t, d.widthMm),
            )
          },
        )
      } else {
        state.plan.copy(
          windows = state.plan.windows.map { w ->
            if (w.id != id) w
            else w.copy(
              wallId = proj.wall.id,
              t = GeometryMath.clampOpeningT(proj.wall, proj.t, w.widthMm),
            )
          },
        )
      }
      state.copy(
        plan = updated,
        tempDimensions = TempDimensionEngine.computeForSelection(updated, state.selection),
      )
    }
  }

  fun updateSymbolLive(symbolId: String, center: Vec2? = null, rotationDeg: Float? = null) {
    _uiState.update { state ->
      val updated = state.plan.copy(
        symbols = state.plan.symbols.map { sym ->
          if (sym.id != symbolId) sym
          else sym.copy(
            center = center ?: sym.center,
            rotationDeg = rotationDeg ?: sym.rotationDeg,
          )
        },
      )
      state.copy(
        plan = updated,
        tempDimensions = TempDimensionEngine.computeForSelection(updated, state.selection),
      )
    }
  }

  fun updateDimensionLive(dimId: String, start: Vec2? = null, end: Vec2? = null, offsetMm: Float? = null) {
    _uiState.update { state ->
      val updated = state.plan.copy(
        dimensions = state.plan.dimensions.map { d ->
          if (d.id != dimId) d
          else d.copy(
            start = start ?: d.start,
            end = end ?: d.end,
            offsetMm = offsetMm ?: d.offsetMm,
          )
        },
      )
      state.copy(plan = updated)
    }
  }

  fun updateTextLive(textId: String, position: Vec2) {
    _uiState.update { state ->
      val updated = state.plan.copy(
        texts = state.plan.texts.map { t ->
          if (t.id == textId) t.copy(position = position) else t
        },
      )
      state.copy(plan = updated)
    }
  }

  fun endDrag(snappedWallId: String? = null) {
    val before = dragStartSnapshot
    dragStartSnapshot = null
    var current = _uiState.value.plan
    if (snappedWallId != null) {
      val movedWall = current.walls.find { it.id == snappedWallId }
      if (movedWall != null) {
        val snapped = GeometryMath.snapWallEndpointJoin(movedWall, current.walls)
        if (snapped != movedWall) {
          current = current.copy(
            walls = current.walls.map { if (it.id == snappedWallId) snapped else it },
          )
          val rooms = RoomAreaEngine.detectRooms(current)
          _uiState.update {
            it.copy(
              plan = current,
              detectedRooms = rooms,
              totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
              tempDimensions = TempDimensionEngine.computeForSelection(current, it.selection),
            )
          }
        }
      }
    }
    if (before != null && before != current) {
      undoStack.addLast(before)
      if (undoStack.size > 50) undoStack.removeFirst()
      redoStack.clear()
      _uiState.update {
        it.copy(
          canUndo = undoStack.isNotEmpty(),
          canRedo = false,
        )
      }
      autoSave()
    }
  }

  // Contextual Properties Bar actions
  fun updateSelectedWallThickness(thicknessMm: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedWall ?: return
    val safe = thicknessMm.coerceIn(50f, 600f)
    val nextPlan = _uiState.value.plan.copy(
      walls = _uiState.value.plan.walls.map { if (it.id == sel.id) it.copy(thicknessMm = safe) else it },
    )
    commitPlanMutation(nextPlan)
  }

  fun updateSelectedDoorWidth(widthMm: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedDoor ?: return
    val safe = widthMm.coerceIn(500f, 2400f)
    val nextPlan = _uiState.value.plan.copy(
      doors = _uiState.value.plan.doors.map { d ->
        if (d.id != sel.id) d
        else {
          val wall = _uiState.value.plan.walls.find { it.id == d.wallId }
          val clampedT = if (wall != null) GeometryMath.clampOpeningT(wall, d.t, safe) else d.t
          d.copy(widthMm = safe, t = clampedT)
        }
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun updateSelectedDoorType(type: DoorType) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedDoor ?: return
    val nextPlan = _uiState.value.plan.copy(
      doors = _uiState.value.plan.doors.map { if (it.id == sel.id) it.copy(doorType = type) else it },
    )
    commitPlanMutation(nextPlan)
  }

  fun flipSelectedDoorSwing() {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedDoor ?: return
    val nextPlan = _uiState.value.plan.copy(
      doors = _uiState.value.plan.doors.map { if (it.id == sel.id) it.copy(swingLeft = !it.swingLeft) else it },
    )
    commitPlanMutation(nextPlan)
  }

  fun flipSelectedDoorHinge() {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedDoor ?: return
    val nextPlan = _uiState.value.plan.copy(
      doors = _uiState.value.plan.doors.map { if (it.id == sel.id) it.copy(flipHinge = !it.flipHinge) else it },
    )
    commitPlanMutation(nextPlan)
  }

  fun updateSelectedWindowWidth(widthMm: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedWindow ?: return
    val safe = widthMm.coerceIn(300f, 3600f)
    val nextPlan = _uiState.value.plan.copy(
      windows = _uiState.value.plan.windows.map { w ->
        if (w.id != sel.id) w
        else {
          val wall = _uiState.value.plan.walls.find { it.id == w.wallId }
          val clampedT = if (wall != null) GeometryMath.clampOpeningT(wall, w.t, safe) else w.t
          w.copy(widthMm = safe, t = clampedT)
        }
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun updateSelectedWindowSill(sillMm: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedWindow ?: return
    val nextPlan = _uiState.value.plan.copy(
      windows = _uiState.value.plan.windows.map {
        if (it.id == sel.id) it.copy(sillHeightMm = sillMm.coerceIn(0f, 2400f)) else it
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun rotateSelectedSymbol(deltaDeg: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedSymbol ?: return
    val nextPlan = _uiState.value.plan.copy(
      symbols = _uiState.value.plan.symbols.map { sym ->
        if (sym.id != sel.id) sym
        else sym.copy(rotationDeg = ((sym.rotationDeg + deltaDeg) % 360f + 360f) % 360f)
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun duplicateSelected() {
    val state = _uiState.value
    val sel = state.selection ?: return
    when (sel) {
      is SelectionTarget.SelectedWall -> {
        val w = state.plan.walls.find { it.id == sel.id } ?: return
        val copy = w.copy(
          id = UUID.randomUUID().toString(),
          start = Vec2(w.start.x + 400f, w.start.y + 400f),
          end = Vec2(w.end.x + 400f, w.end.y + 400f),
        )
        commitPlanMutation(state.plan.copy(walls = state.plan.walls + copy), SelectionTarget.SelectedWall(copy.id))
      }
      is SelectionTarget.SelectedSymbol -> {
        val s = state.plan.symbols.find { it.id == sel.id } ?: return
        val copy = s.copy(
          id = UUID.randomUUID().toString(),
          center = Vec2(s.center.x + 450f, s.center.y + 450f),
        )
        commitPlanMutation(state.plan.copy(symbols = state.plan.symbols + copy), SelectionTarget.SelectedSymbol(copy.id))
      }
      is SelectionTarget.SelectedDoor -> {
        val d = state.plan.doors.find { it.id == sel.id } ?: return
        val wall = state.plan.walls.find { it.id == d.wallId } ?: return
        val newT = GeometryMath.clampOpeningT(wall, (d.t + 0.15f).coerceAtMost(0.9f), d.widthMm)
        val copy = d.copy(id = UUID.randomUUID().toString(), t = newT)
        commitPlanMutation(state.plan.copy(doors = state.plan.doors + copy), SelectionTarget.SelectedDoor(copy.id))
      }
      is SelectionTarget.SelectedWindow -> {
        val w = state.plan.windows.find { it.id == sel.id } ?: return
        val wall = state.plan.walls.find { it.id == w.wallId } ?: return
        val newT = GeometryMath.clampOpeningT(wall, (w.t + 0.15f).coerceAtMost(0.9f), w.widthMm)
        val copy = w.copy(id = UUID.randomUUID().toString(), t = newT)
        commitPlanMutation(state.plan.copy(windows = state.plan.windows + copy), SelectionTarget.SelectedWindow(copy.id))
      }
      is SelectionTarget.SelectedDimension -> {
        val d = state.plan.dimensions.find { it.id == sel.id } ?: return
        val copy = d.copy(id = UUID.randomUUID().toString(), offsetMm = d.offsetMm + 350f)
        commitPlanMutation(state.plan.copy(dimensions = state.plan.dimensions + copy), SelectionTarget.SelectedDimension(copy.id))
      }
      is SelectionTarget.SelectedText -> {
        val t = state.plan.texts.find { it.id == sel.id } ?: return
        val copy = t.copy(
          id = UUID.randomUUID().toString(),
          position = Vec2(t.position.x + 350f, t.position.y + 350f),
        )
        commitPlanMutation(state.plan.copy(texts = state.plan.texts + copy), SelectionTarget.SelectedText(copy.id))
      }
    }
  }

  fun updateSelectedTextContent(
    newText: String,
    newFontSizeMm: Float? = null,
    hasOpaqueBackground: Boolean? = null,
  ) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedText ?: return
    val clean = newText.trim()
    if (clean.isEmpty()) return
    val nextPlan = _uiState.value.plan.copy(
      texts = _uiState.value.plan.texts.map { t ->
        if (t.id == sel.id) {
          t.copy(
            text = clean,
            fontSizeMm = newFontSizeMm ?: t.fontSizeMm,
            hasOpaqueBackground = hasOpaqueBackground ?: t.hasOpaqueBackground,
          )
        } else t
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun toggleSelectedTextOpaqueBg() {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedText ?: return
    val nextPlan = _uiState.value.plan.copy(
      texts = _uiState.value.plan.texts.map { t ->
        if (t.id == sel.id) t.copy(hasOpaqueBackground = !t.hasOpaqueBackground) else t
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun updateSelectedTextSize(fontSizeMm: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedText ?: return
    val safe = fontSizeMm.coerceIn(100f, 600f)
    val nextPlan = _uiState.value.plan.copy(
      texts = _uiState.value.plan.texts.map { t ->
        if (t.id == sel.id) t.copy(fontSizeMm = safe) else t
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun rotateSelectedText(deltaDeg: Float) {
    val sel = _uiState.value.selection as? SelectionTarget.SelectedText ?: return
    val nextPlan = _uiState.value.plan.copy(
      texts = _uiState.value.plan.texts.map { t ->
        if (t.id == sel.id) t.copy(rotationDeg = ((t.rotationDeg + deltaDeg) % 360f + 360f) % 360f) else t
      },
    )
    commitPlanMutation(nextPlan)
  }

  fun deleteElement(target: SelectionTarget) {
    val plan = _uiState.value.plan
    val nextPlan = when (target) {
      is SelectionTarget.SelectedWall -> plan.copy(
        walls = plan.walls.filterNot { it.id == target.id },
        doors = plan.doors.filterNot { it.wallId == target.id },
        windows = plan.windows.filterNot { it.wallId == target.id },
      )
      is SelectionTarget.SelectedDoor -> plan.copy(doors = plan.doors.filterNot { it.id == target.id })
      is SelectionTarget.SelectedWindow -> plan.copy(windows = plan.windows.filterNot { it.id == target.id })
      is SelectionTarget.SelectedSymbol -> plan.copy(symbols = plan.symbols.filterNot { it.id == target.id })
      is SelectionTarget.SelectedDimension -> plan.copy(dimensions = plan.dimensions.filterNot { it.id == target.id })
      is SelectionTarget.SelectedText -> plan.copy(texts = plan.texts.filterNot { it.id == target.id })
    }
    val nextSel = if (_uiState.value.selection == target) null else _uiState.value.selection
    commitPlanMutation(nextPlan, nextSel)
  }

  fun deleteSelected() {
    val sel = _uiState.value.selection ?: return
    deleteElement(sel)
  }

  fun applyTempDimensionEdit(tempDim: TempDimension, newDistanceMm: Float) {
    val nextPlan = TempDimensionEngine.applyTempDimensionEdit(_uiState.value.plan, tempDim, newDistanceMm)
    commitPlanMutation(nextPlan)
  }

  fun undo() {
    if (undoStack.isEmpty()) return
    val current = _uiState.value.plan
    val prev = undoStack.removeLast()
    redoStack.addLast(current)
    val sel = validateSelection(prev, _uiState.value.selection)
    val rooms = RoomAreaEngine.detectRooms(prev)
    _uiState.update {
      it.copy(
        plan = prev,
        detectedRooms = rooms,
        totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
        selection = sel,
        tempDimensions = TempDimensionEngine.computeForSelection(prev, sel),
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
      )
    }
    autoSave()
  }

  fun redo() {
    if (redoStack.isEmpty()) return
    val current = _uiState.value.plan
    val next = redoStack.removeLast()
    undoStack.addLast(current)
    val sel = validateSelection(next, _uiState.value.selection)
    val rooms = RoomAreaEngine.detectRooms(next)
    _uiState.update {
      it.copy(
        plan = next,
        detectedRooms = rooms,
        totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
        selection = sel,
        tempDimensions = TempDimensionEngine.computeForSelection(next, sel),
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
      )
    }
    autoSave()
  }

  fun requestFitToPlan() {
    _uiState.update { it.copy(fitRequestCounter = it.fitRequestCounter + 1) }
  }

  fun requestResetView() {
    _uiState.update { it.copy(resetRequestCounter = it.resetRequestCounter + 1) }
  }

  fun requestZoomStep(factor: Float) {
    _uiState.update { it.copy(zoomStepDelta = (it.zoomStepDelta.first + 1) to factor) }
  }

  fun clearCanvas() {
    commitPlanMutation(FloorPlanData(), null)
    setStatus("Canvas cleared")
  }

  fun createNewPlan(name: String, loadStarter: Boolean = false) {
    val id = UUID.randomUUID().toString()
    val data = if (loadStarter) SamplePlans.starterApartment() else FloorPlanData()
    val rooms = RoomAreaEngine.detectRooms(data)
    undoStack.clear()
    redoStack.clear()
    _uiState.update {
      it.copy(
        planId = id,
        planName = name.ifBlank { "Untitled Plan" },
        plan = data,
        detectedRooms = rooms,
        totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
        selection = null,
        tempDimensions = emptyList(),
        canUndo = false,
        canRedo = false,
        fitRequestCounter = it.fitRequestCounter + 1,
      )
    }
    persistCurrentPlan(id, _uiState.value.planName, data)
    setStatus("Created \"${_uiState.value.planName}\"")
  }

  fun savePlanAs(name: String) {
    val cleanName = name.ifBlank { "Untitled Plan" }
    val newId = UUID.randomUUID().toString()
    val data = _uiState.value.plan
    _uiState.update { it.copy(planId = newId, planName = cleanName) }
    persistCurrentPlan(newId, cleanName, data)
    setStatus("Saved \"$cleanName\"")
  }

  fun loadSavedPlan(entity: PlanEntity) {
    val parsed = PlanJsonCodec.fromJson(entity.jsonContent) ?: return
    val rooms = RoomAreaEngine.detectRooms(parsed)
    undoStack.clear()
    redoStack.clear()
    _uiState.update {
      it.copy(
        planId = entity.id,
        planName = entity.name,
        plan = parsed,
        detectedRooms = rooms,
        totalAreaSqMm = rooms.sumOf { r -> r.areaSqMm.toDouble() }.toFloat(),
        selection = null,
        tempDimensions = emptyList(),
        canUndo = false,
        canRedo = false,
        fitRequestCounter = it.fitRequestCounter + 1,
      )
    }
    viewModelScope.launch { prefsRepository.setActivePlanId(entity.id) }
    setStatus("Loaded \"${entity.name}\"")
  }

  fun deleteSavedPlan(id: String) {
    viewModelScope.launch { planRepository.deletePlan(id) }
  }

  fun setStatus(message: String?) {
    _uiState.update { it.copy(statusMessage = message) }
  }

  private fun autoSave() {
    val s = _uiState.value
    persistCurrentPlan(s.planId, s.planName, s.plan)
  }

  private fun persistCurrentPlan(id: String, name: String, plan: FloorPlanData) {
    viewModelScope.launch {
      planRepository.savePlan(
        PlanEntity(
          id = id,
          name = name,
          updatedAt = System.currentTimeMillis(),
          jsonContent = PlanJsonCodec.toJson(plan),
        ),
      )
      prefsRepository.setActivePlanId(id)
    }
  }
}
