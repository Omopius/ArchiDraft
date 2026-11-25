package com.example.ui.canvas

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.geometry.DetectedRoom
import com.example.geometry.GeometryMath
import com.example.geometry.GuideAxis
import com.example.geometry.RoomAreaEngine
import com.example.geometry.SmartGuide
import com.example.geometry.SmartGuideEngine
import com.example.geometry.SnapKind
import com.example.geometry.SnapResult
import com.example.model.DraftingTool
import com.example.model.SelectionTarget
import com.example.model.TempDimension
import com.example.model.Vec2
import com.example.ui.theme.DraftPaper
import com.example.viewmodel.DraftingUiState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private sealed interface ActiveDragMode {
  data object PanCanvas : ActiveDragMode
  data class DrawWall(val startWorld: Vec2, var currentSnap: SnapResult) : ActiveDragMode
  data class DrawDimension(val startWorld: Vec2, var endWorld: Vec2) : ActiveDragMode
  data class MoveWall(val wallId: String, val origStart: Vec2, val origEnd: Vec2, val pressWorld: Vec2) : ActiveDragMode
  data class MoveWallEndpoint(val wallId: String, val isStart: Boolean) : ActiveDragMode
  data class MoveOpening(val id: String, val isDoor: Boolean) : ActiveDragMode
  data class MoveSymbol(val symbolId: String, val offsetFromCenter: Vec2) : ActiveDragMode
  data class RotateSymbol(val symbolId: String, val center: Vec2) : ActiveDragMode
  data class MoveDimensionOffset(val dimId: String, val origStart: Vec2, val origEnd: Vec2) : ActiveDragMode
  data class MoveText(val textId: String, val offsetFromPos: Vec2) : ActiveDragMode
}

@Composable
fun DraftingCanvas(
  state: DraftingUiState,
  onSelect: (SelectionTarget?) -> Unit,
  onTempDimensionTap: (TempDimension) -> Unit,
  onAddWall: (Vec2, Vec2) -> Unit,
  onPlaceDoor: (Vec2) -> Unit,
  onPlaceWindow: (Vec2) -> Unit,
  onPlaceSymbol: (Vec2) -> Unit,
  onAddDimension: (Vec2, Vec2, Float) -> Unit,
  onRoomLabelTap: (DetectedRoom) -> Unit,
  onRoomLabelOutside: () -> Unit,
  onRequestAddText: (Vec2) -> Unit,
  onEraseHit: (SelectionTarget) -> Unit,
  onBeginDrag: () -> Unit,
  onMoveWallLive: (String, Vec2, Vec2) -> Unit,
  onMoveOpeningLive: (String, Boolean, Vec2) -> Unit,
  onMoveSymbolLive: (String, Vec2?, Float?) -> Unit,
  onMoveDimensionLive: (String, Vec2?, Vec2?, Float?) -> Unit,
  onMoveTextLive: (String, Vec2) -> Unit,
  onEndDrag: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val density = LocalDensity.current.density
  val monoTypeface = remember(context) {
    ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
  }
  val paints = remember(monoTypeface, density) {
    DraftingPaints(monoTypeface).apply {
      dimText.textSize = 11.5f * density
      tempDimText.textSize = 12f * density
      liveReadoutText.textSize = 12.5f * density
      gridLabelText.textSize = 9.5f * density
    }
  }

  var panPx by remember { mutableStateOf(Offset(60f * density, 160f * density)) }
  var scalePxPerMm by remember { mutableFloatStateOf(0.085f * density) }
  var renderedPills by remember { mutableStateOf<List<RenderedPill>>(emptyList()) }
  var wallPreview by remember { mutableStateOf<Pair<Vec2, SnapResult>?>(null) }
  var dimPreview by remember { mutableStateOf<Pair<Vec2, Vec2>?>(null) }
  var activeSnap by remember { mutableStateOf<SnapResult?>(null) }
  var activeSmartGuides by remember { mutableStateOf<List<SmartGuide>>(emptyList()) }
  var tapStartWallPoint by remember { mutableStateOf<Vec2?>(null) }
  var tapStartDimPoint by remember { mutableStateOf<Vec2?>(null) }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(DraftPaper)
      .testTag("drafting_canvas"),
  ) {
    val viewportW = constraints.maxWidth.toFloat()
    val viewportH = constraints.maxHeight.toFloat()

    // Fit to plan when requested
    LaunchedEffect(state.fitRequestCounter, viewportW, viewportH) {
      if (viewportW > 0f && viewportH > 0f) {
        val bounds = GeometryMath.computePlanBounds(state.plan)
        if (bounds != null && bounds.width > 10f && bounds.height > 10f) {
          val padX = 110f * density
          val padY = 180f * density
          val availW = max(120f, viewportW - padX * 2f)
          val availH = max(120f, viewportH - padY * 2f)
          val fitScale = min(availW / bounds.width, availH / bounds.height)
            .coerceIn(0.015f * density, 0.35f * density)
          scalePxPerMm = fitScale
          val center = bounds.center
          panPx = Offset(
            viewportW * 0.5f - center.x * fitScale,
            viewportH * 0.5f - center.y * fitScale,
          )
        } else {
          scalePxPerMm = 0.08f * density
          panPx = Offset(viewportW * 0.15f, viewportH * 0.25f)
        }
      }
    }

    LaunchedEffect(state.resetRequestCounter) {
      if (state.resetRequestCounter > 0 && viewportW > 0f) {
        scalePxPerMm = 0.08f * density
        panPx = Offset(viewportW * 0.12f, viewportH * 0.22f)
      }
    }

    LaunchedEffect(state.zoomStepDelta) {
      if (state.zoomStepDelta.first > 0 && viewportW > 0f) {
        val factor = state.zoomStepDelta.second
        val center = Offset(viewportW * 0.5f, viewportH * 0.5f)
        val newScale = (scalePxPerMm * factor).coerceIn(0.012f * density, 0.8f * density)
        val ratio = newScale / scalePxPerMm
        panPx = center - (center - panPx) * ratio
        scalePxPerMm = newScale
      }
    }

    Canvas(
      modifier = Modifier
        .fillMaxSize()
        // Desktop / trackpad scroll-wheel zoom
        .pointerInput(density) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (event.type == PointerEventType.Scroll) {
                val change = event.changes.firstOrNull() ?: continue
                val scrollY = change.scrollDelta.y
                if (scrollY != 0f) {
                  val factor = if (scrollY < 0f) 1.12f else 0.89f
                  val newScale = (scalePxPerMm * factor).coerceIn(0.012f * density, 0.8f * density)
                  val ratio = newScale / scalePxPerMm
                  panPx = change.position - (change.position - panPx) * ratio
                  scalePxPerMm = newScale
                }
              }
            }
          }
        }
        // Touch & mouse gestures
        .pointerInput(
          state.activeTool,
          state.plan,
          state.detectedRooms,
          state.selection,
          state.gridSnap,
          state.angleSnap,
          state.unitSystem,
        ) {
          awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val downScreen = firstDown.position
            val downWorld = ArchitecturalCanvasRenderer.screenToWorld(downScreen, panPx, scalePxPerMm)
            val hitTolMm = (22f * density) / scalePxPerMm
            val endpointTolMm = (24f * density) / scalePxPerMm
            val smartAlignTolMm = max(90f, (14f * density) / scalePxPerMm)

            // Check if user tapped a temporary dimension pill first
            val hitPill = renderedPills.firstOrNull { it.screenRect.contains(downScreen) }
            if (hitPill != null) {
              var moved = false
              while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull() ?: break
                if (hypot(ch.position.x - downScreen.x, ch.position.y - downScreen.y) > 10f * density) {
                  moved = true
                }
                if (!ch.pressed) {
                  if (!moved) onTempDimensionTap(hitPill.tempDim)
                  break
                }
              }
              return@awaitEachGesture
            }

            // Determine single-finger drag mode
            var dragMode: ActiveDragMode? = when (state.activeTool) {
              DraftingTool.SELECT -> {
                // 1. Check handles of currently selected item
                var mode: ActiveDragMode? = null
                when (val sel = state.selection) {
                  is SelectionTarget.SelectedWall -> {
                    val w = state.plan.walls.find { it.id == sel.id }
                    if (w != null) {
                      if (GeometryMath.distance(downWorld, w.start) <= hitTolMm) {
                        mode = ActiveDragMode.MoveWallEndpoint(w.id, isStart = true)
                      } else if (GeometryMath.distance(downWorld, w.end) <= hitTolMm) {
                        mode = ActiveDragMode.MoveWallEndpoint(w.id, isStart = false)
                      }
                    }
                  }
                  is SelectionTarget.SelectedSymbol -> {
                    val sym = state.plan.symbols.find { it.id == sel.id }
                    if (sym != null) {
                      val rotPos = GeometryMath.symbolRotateHandlePos(sym, 260f)
                      if (GeometryMath.distance(downWorld, rotPos) <= hitTolMm) {
                        mode = ActiveDragMode.RotateSymbol(sym.id, sym.center)
                      }
                    }
                  }
                  else -> Unit
                }

                // 2. Hit-test element under finger
                if (mode == null) {
                  val hit = GeometryMath.hitTest(downWorld, state.plan, hitTolMm)
                  mode = when (hit) {
                    is SelectionTarget.SelectedWall -> {
                      onSelect(hit)
                      val w = state.plan.walls.find { it.id == hit.id }
                      if (w != null) ActiveDragMode.MoveWall(w.id, w.start, w.end, downWorld)
                      else ActiveDragMode.PanCanvas
                    }
                    is SelectionTarget.SelectedDoor -> {
                      onSelect(hit)
                      ActiveDragMode.MoveOpening(hit.id, isDoor = true)
                    }
                    is SelectionTarget.SelectedWindow -> {
                      onSelect(hit)
                      ActiveDragMode.MoveOpening(hit.id, isDoor = false)
                    }
                    is SelectionTarget.SelectedSymbol -> {
                      onSelect(hit)
                      val sym = state.plan.symbols.find { it.id == hit.id }
                      if (sym != null) {
                        ActiveDragMode.MoveSymbol(sym.id, GeometryMath.sub(sym.center, downWorld))
                      } else ActiveDragMode.PanCanvas
                    }
                    is SelectionTarget.SelectedDimension -> {
                      onSelect(hit)
                      val dim = state.plan.dimensions.find { it.id == hit.id }
                      if (dim != null) {
                        ActiveDragMode.MoveDimensionOffset(dim.id, dim.start, dim.end)
                      } else ActiveDragMode.PanCanvas
                    }
                    is SelectionTarget.SelectedText -> {
                      onSelect(hit)
                      val txt = state.plan.texts.find { it.id == hit.id }
                      if (txt != null) {
                        ActiveDragMode.MoveText(txt.id, GeometryMath.sub(txt.position, downWorld))
                      } else ActiveDragMode.PanCanvas
                    }
                    null -> ActiveDragMode.PanCanvas
                  }
                }
                mode
              }

              DraftingTool.WALL -> {
                val snappedStart = GeometryMath.snapDrawPoint(
                  rawPoint = downWorld,
                  anchor = tapStartWallPoint,
                  walls = state.plan.walls,
                  gridSnap = state.gridSnap,
                  angleSnap = state.angleSnap,
                  unitSystem = state.unitSystem,
                  endpointThresholdMm = endpointTolMm,
                )
                ActiveDragMode.DrawWall(snappedStart.point, snappedStart)
              }

              DraftingTool.DIMENSION -> {
                val snappedStart = GeometryMath.snapDrawPoint(
                  rawPoint = downWorld,
                  anchor = tapStartDimPoint,
                  walls = state.plan.walls,
                  gridSnap = state.gridSnap,
                  angleSnap = state.angleSnap,
                  unitSystem = state.unitSystem,
                  endpointThresholdMm = endpointTolMm,
                )
                ActiveDragMode.DrawDimension(snappedStart.point, snappedStart.point)
              }

              else -> null
            }

            var hasStartedDrag = false
            var dragStartedMutation = false
            var multiTouchUsed = false

            while (true) {
              val event = awaitPointerEvent()
              val pressedCount = event.changes.count { it.pressed }

              // Multi-touch pinch/pan always overrides
              if (pressedCount >= 2) {
                multiTouchUsed = true
                wallPreview = null
                dimPreview = null
                activeSnap = null
                activeSmartGuides = emptyList()
                if (dragStartedMutation) {
                  onEndDrag(null)
                  dragStartedMutation = false
                }
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                val newScale = (scalePxPerMm * zoomChange).coerceIn(0.012f * density, 0.8f * density)
                val ratio = newScale / scalePxPerMm
                panPx = centroid - (centroid - panPx) * ratio + panChange
                scalePxPerMm = newScale
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                continue
              }

              val change = event.changes.firstOrNull() ?: break
              val currentScreen = change.position
              val currentWorld = ArchitecturalCanvasRenderer.screenToWorld(currentScreen, panPx, scalePxPerMm)
              val distMovedPx = hypot(currentScreen.x - downScreen.x, currentScreen.y - downScreen.y)

              if (distMovedPx > 8f * density && !hasStartedDrag) {
                hasStartedDrag = true
                if (dragMode is ActiveDragMode.MoveWall ||
                  dragMode is ActiveDragMode.MoveWallEndpoint ||
                  dragMode is ActiveDragMode.MoveOpening ||
                  dragMode is ActiveDragMode.MoveSymbol ||
                  dragMode is ActiveDragMode.RotateSymbol ||
                  dragMode is ActiveDragMode.MoveDimensionOffset ||
                  dragMode is ActiveDragMode.MoveText
                ) {
                  onBeginDrag()
                  dragStartedMutation = true
                }
              }

              if (!change.pressed) {
                // Pointer released
                if (!multiTouchUsed) {
                  if (!hasStartedDrag) {
                    // Pure Tap!
                    when (state.activeTool) {
                      DraftingTool.SELECT -> {
                        val hit = GeometryMath.hitTest(downWorld, state.plan, hitTolMm)
                        onSelect(hit)
                      }
                      DraftingTool.WALL -> {
                        val snap = GeometryMath.snapDrawPoint(
                          rawPoint = downWorld,
                          anchor = tapStartWallPoint,
                          walls = state.plan.walls,
                          gridSnap = state.gridSnap,
                          angleSnap = state.angleSnap,
                          unitSystem = state.unitSystem,
                          endpointThresholdMm = endpointTolMm,
                        )
                        val prevStart = tapStartWallPoint
                        if (prevStart == null) {
                          tapStartWallPoint = snap.point
                          activeSnap = snap
                        } else {
                          onAddWall(prevStart, snap.point)
                          tapStartWallPoint = null
                          activeSnap = null
                        }
                      }
                      DraftingTool.DOOR -> onPlaceDoor(downWorld)
                      DraftingTool.WINDOW -> onPlaceWindow(downWorld)
                      DraftingTool.FIXTURE -> onPlaceSymbol(downWorld)
                      DraftingTool.DIMENSION -> {
                        val snap = GeometryMath.snapDrawPoint(
                          rawPoint = downWorld,
                          anchor = tapStartDimPoint,
                          walls = state.plan.walls,
                          gridSnap = state.gridSnap,
                          angleSnap = state.angleSnap,
                          unitSystem = state.unitSystem,
                          endpointThresholdMm = endpointTolMm,
                        )
                        val prevStart = tapStartDimPoint
                        if (prevStart == null) {
                          tapStartDimPoint = snap.point
                          activeSnap = snap
                        } else {
                          onAddDimension(prevStart, snap.point, 450f)
                          tapStartDimPoint = null
                          activeSnap = null
                        }
                      }
                      DraftingTool.ROOM_LABEL -> {
                        val room = RoomAreaEngine.findRoomAt(downWorld, state.detectedRooms)
                        if (room != null) onRoomLabelTap(room) else onRoomLabelOutside()
                      }
                      DraftingTool.TEXT -> {
                        onRequestAddText(downWorld)
                      }
                      DraftingTool.ERASER -> {
                        val hit = GeometryMath.hitTest(downWorld, state.plan, hitTolMm)
                        if (hit != null) onEraseHit(hit)
                      }
                    }
                  } else {
                    // Finish drag
                    when (val m = dragMode) {
                      is ActiveDragMode.DrawWall -> {
                        onAddWall(m.startWorld, m.currentSnap.point)
                        tapStartWallPoint = null
                      }
                      is ActiveDragMode.DrawDimension -> {
                        onAddDimension(m.startWorld, m.endWorld, 450f)
                        tapStartDimPoint = null
                      }
                      else -> Unit
                    }
                    if (dragStartedMutation) {
                      val movedWallId = when (val m = dragMode) {
                        is ActiveDragMode.MoveWall -> m.wallId
                        is ActiveDragMode.MoveWallEndpoint -> m.wallId
                        else -> null
                      }
                      onEndDrag(movedWallId)
                      dragStartedMutation = false
                    }
                  }
                }
                wallPreview = null
                dimPreview = null
                activeSmartGuides = emptyList()
                if (!hasStartedDrag && tapStartWallPoint == null && tapStartDimPoint == null) {
                  activeSnap = null
                }
                break
              }

              // Pointer moving while pressed
              if (hasStartedDrag && !multiTouchUsed) {
                when (val m = dragMode) {
                  is ActiveDragMode.PanCanvas -> {
                    val delta = currentScreen - change.previousPosition
                    panPx += delta
                  }
                  is ActiveDragMode.DrawWall -> {
                    val snap = GeometryMath.snapDrawPoint(
                      rawPoint = currentWorld,
                      anchor = m.startWorld,
                      walls = state.plan.walls,
                      gridSnap = state.gridSnap,
                      angleSnap = state.angleSnap,
                      unitSystem = state.unitSystem,
                      endpointThresholdMm = endpointTolMm,
                    )
                    m.currentSnap = snap
                    wallPreview = m.startWorld to snap
                    activeSnap = snap
                  }
                  is ActiveDragMode.DrawDimension -> {
                    val snap = GeometryMath.snapDrawPoint(
                      rawPoint = currentWorld,
                      anchor = m.startWorld,
                      walls = state.plan.walls,
                      gridSnap = state.gridSnap,
                      angleSnap = state.angleSnap,
                      unitSystem = state.unitSystem,
                      endpointThresholdMm = endpointTolMm,
                    )
                    m.endWorld = snap.point
                    dimPreview = m.startWorld to snap.point
                    activeSnap = snap
                  }
                  is ActiveDragMode.MoveWall -> {
                    val rawDelta = GeometryMath.sub(currentWorld, m.pressWorld)
                    val origWall = state.plan.walls.find { it.id == m.wallId }
                    var delta = if (origWall != null) {
                      val guideRes = SmartGuideEngine.alignWallMoveDelta(
                        origWall = origWall,
                        rawDelta = rawDelta,
                        plan = state.plan,
                        rooms = state.detectedRooms,
                        snapToleranceMm = smartAlignTolMm,
                      )
                      activeSmartGuides = guideRes.guides
                      val hasXGuide = guideRes.guides.any { it.axis == GuideAxis.VERTICAL }
                      val hasYGuide = guideRes.guides.any { it.axis == GuideAxis.HORIZONTAL }
                      if (state.gridSnap) {
                        val step = GeometryMath.gridIncrementMm(state.unitSystem)
                        val gridStart = GeometryMath.snapPointToGrid(GeometryMath.add(m.origStart, guideRes.snappedPoint), step)
                        val gridDelta = GeometryMath.sub(gridStart, m.origStart)
                        Vec2(
                          if (hasXGuide) guideRes.snappedPoint.x else gridDelta.x,
                          if (hasYGuide) guideRes.snappedPoint.y else gridDelta.y,
                        )
                      } else guideRes.snappedPoint
                    } else rawDelta
                    onMoveWallLive(
                      m.wallId,
                      GeometryMath.add(m.origStart, delta),
                      GeometryMath.add(m.origEnd, delta),
                    )
                  }
                  is ActiveDragMode.MoveWallEndpoint -> {
                    val wall = state.plan.walls.find { it.id == m.wallId }
                    if (wall != null) {
                      val anchor = if (m.isStart) wall.end else wall.start
                      val snap = GeometryMath.snapDrawPoint(
                        rawPoint = currentWorld,
                        anchor = anchor,
                        walls = state.plan.walls,
                        gridSnap = state.gridSnap,
                        angleSnap = state.angleSnap,
                        unitSystem = state.unitSystem,
                        endpointThresholdMm = endpointTolMm,
                        excludeWallId = m.wallId,
                      )
                      val finalPoint = if (
                        snap.snapKind == SnapKind.ENDPOINT ||
                        snap.snapKind == SnapKind.MIDPOINT ||
                        snap.snapKind == SnapKind.T_JUNCTION
                      ) {
                        activeSmartGuides = emptyList()
                        snap.point
                      } else {
                        val guideRes = SmartGuideEngine.alignWallEndpoint(
                          rawPoint = snap.point,
                          fixedOtherEndpoint = anchor,
                          movingWallId = m.wallId,
                          plan = state.plan,
                          rooms = state.detectedRooms,
                          snapToleranceMm = smartAlignTolMm,
                        )
                        activeSmartGuides = guideRes.guides
                        guideRes.snappedPoint
                      }
                      activeSnap = snap.copy(point = finalPoint)
                      if (m.isStart) onMoveWallLive(m.wallId, finalPoint, wall.end)
                      else onMoveWallLive(m.wallId, wall.start, finalPoint)
                    }
                  }
                  is ActiveDragMode.MoveOpening -> {
                    activeSmartGuides = emptyList()
                    onMoveOpeningLive(m.id, m.isDoor, currentWorld)
                  }
                  is ActiveDragMode.MoveSymbol -> {
                    val rawCenter = GeometryMath.add(currentWorld, m.offsetFromCenter)
                    val sym = state.plan.symbols.find { it.id == m.symbolId }
                    val finalCenter = if (sym != null) {
                      val guideRes = SmartGuideEngine.alignSymbolMove(
                        rawCenter = rawCenter,
                        movingSymbol = sym,
                        plan = state.plan,
                        rooms = state.detectedRooms,
                        snapToleranceMm = smartAlignTolMm,
                      )
                      activeSmartGuides = guideRes.guides
                      val hasXGuide = guideRes.guides.any { it.axis == GuideAxis.VERTICAL }
                      val hasYGuide = guideRes.guides.any { it.axis == GuideAxis.HORIZONTAL }
                      if (state.gridSnap) {
                        val gridPt = GeometryMath.snapPointToGrid(
                          guideRes.snappedPoint,
                          GeometryMath.gridIncrementMm(state.unitSystem) * 0.5f,
                        )
                        Vec2(
                          if (hasXGuide) guideRes.snappedPoint.x else gridPt.x,
                          if (hasYGuide) guideRes.snappedPoint.y else gridPt.y,
                        )
                      } else guideRes.snappedPoint
                    } else rawCenter
                    onMoveSymbolLive(m.symbolId, finalCenter, null)
                  }
                  is ActiveDragMode.RotateSymbol -> {
                    activeSmartGuides = emptyList()
                    val dx = currentWorld.x - m.center.x
                    val dy = currentWorld.y - m.center.y
                    val rawDeg = (atan2(dx, -dy) * 180.0 / PI).toFloat()
                    val finalDeg = if (state.angleSnap) GeometryMath.clampAngleSnap90(rawDeg) else ((rawDeg % 360f) + 360f) % 360f
                    onMoveSymbolLive(m.symbolId, null, finalDeg)
                  }
                  is ActiveDragMode.MoveDimensionOffset -> {
                    activeSmartGuides = emptyList()
                    val dir = GeometryMath.normalize(GeometryMath.sub(m.origEnd, m.origStart))
                    val normal = Vec2(-dir.y, dir.x)
                    val offsetMm = GeometryMath.dot(GeometryMath.sub(currentWorld, m.origStart), normal)
                    onMoveDimensionLive(m.dimId, null, null, offsetMm)
                  }
                  is ActiveDragMode.MoveText -> {
                    val rawPos = GeometryMath.add(currentWorld, m.offsetFromPos)
                    val guideRes = SmartGuideEngine.alignTextMove(
                      rawPos = rawPos,
                      movingTextId = m.textId,
                      plan = state.plan,
                      rooms = state.detectedRooms,
                      snapToleranceMm = smartAlignTolMm,
                    )
                    activeSmartGuides = guideRes.guides
                    val hasXGuide = guideRes.guides.any { it.axis == GuideAxis.VERTICAL }
                    val hasYGuide = guideRes.guides.any { it.axis == GuideAxis.HORIZONTAL }
                    val finalPos = if (state.gridSnap) {
                      val gridPt = GeometryMath.snapPointToGrid(
                        guideRes.snappedPoint,
                        GeometryMath.gridIncrementMm(state.unitSystem) * 0.5f,
                      )
                      Vec2(
                        if (hasXGuide) guideRes.snappedPoint.x else gridPt.x,
                        if (hasYGuide) guideRes.snappedPoint.y else gridPt.y,
                      )
                    } else guideRes.snappedPoint
                    onMoveTextLive(m.textId, finalPos)
                  }
                  null -> Unit
                }
                change.consume()
              }
            }
          }
        },
    ) {
      renderedPills = ArchitecturalCanvasRenderer.renderPlan(
        drawScope = this,
        plan = state.plan,
        panPx = panPx,
        scalePxPerMm = scalePxPerMm,
        unitSystem = state.unitSystem,
        selection = state.selection,
        tempDimensions = state.tempDimensions,
        paints = paints,
        density = density,
        detectedRooms = state.detectedRooms,
        showRoomAreas = state.showRoomAreas,
        highlightRoomDropZones = (state.activeTool == DraftingTool.ROOM_LABEL),
        wallDraftPreview = wallPreview,
        dimDraftPreview = dimPreview,
        activeSnapPoint = activeSnap,
        activeSmartGuides = activeSmartGuides,
      )
    }
  }
}
