package com.example.ui.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.geometry.DetectedRoom
import com.example.geometry.GeometryMath
import com.example.geometry.GuideAxis
import com.example.geometry.SmartGuide
import com.example.geometry.SnapKind
import com.example.geometry.SnapResult
import com.example.model.DoorOpening
import com.example.model.DoorType
import com.example.model.FloorPlanData
import com.example.model.LinearDimension
import com.example.model.PlacedSymbol
import com.example.model.SelectionTarget
import com.example.model.SymbolType
import com.example.model.TempDimension
import com.example.model.TextLabel
import com.example.model.UnitFormatter
import com.example.model.UnitSystem
import com.example.model.Vec2
import com.example.model.Wall
import com.example.model.WindowOpening
import com.example.ui.theme.BlueprintCobalt
import com.example.ui.theme.DraftGridMajor
import com.example.ui.theme.DraftGridMinor
import com.example.ui.theme.InkCharcoal
import com.example.ui.theme.InkSlate
import com.example.ui.theme.SnapAmber
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class RenderedPill(
  val tempDim: TempDimension,
  val screenRect: Rect,
)

class DraftingPaints(monoTypeface: Typeface) {
  val dimText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = InkCharcoal.toArgb()
    textSize = 28f
    typeface = monoTypeface
    textAlign = Paint.Align.CENTER
  }
  val tempDimText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color(0xFF1E3A8A).toArgb()
    textSize = 30f
    typeface = Typeface.create(monoTypeface, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
  }
  val liveReadoutText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.White.toArgb()
    textSize = 30f
    typeface = Typeface.create(monoTypeface, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
  }
  val gridLabelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color(0xFF94A3B8).toArgb()
    textSize = 22f
    typeface = monoTypeface
    textAlign = Paint.Align.LEFT
  }
  val planTextLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = InkCharcoal.toArgb()
    textSize = 28f
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
  }
  val areaBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color(0xFF1D4ED8).toArgb()
    textSize = 26f
    typeface = Typeface.create(monoTypeface, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
  }
}

object ArchitecturalCanvasRenderer {

  fun worldToScreen(world: Vec2, panPx: Offset, scalePxPerMm: Float): Offset =
    Offset(world.x * scalePxPerMm + panPx.x, world.y * scalePxPerMm + panPx.y)

  fun screenToWorld(screen: Offset, panPx: Offset, scalePxPerMm: Float): Vec2 =
    Vec2((screen.x - panPx.x) / scalePxPerMm, (screen.y - panPx.y) / scalePxPerMm)

  fun renderPlan(
    drawScope: DrawScope,
    plan: FloorPlanData,
    panPx: Offset,
    scalePxPerMm: Float,
    unitSystem: UnitSystem,
    selection: SelectionTarget?,
    tempDimensions: List<TempDimension>,
    paints: DraftingPaints,
    density: Float,
    detectedRooms: List<DetectedRoom> = emptyList(),
    showRoomAreas: Boolean = true,
    highlightRoomDropZones: Boolean = false,
    wallDraftPreview: Pair<Vec2, SnapResult>? = null,
    dimDraftPreview: Pair<Vec2, Vec2>? = null,
    activeSnapPoint: SnapResult? = null,
    activeSmartGuides: List<SmartGuide> = emptyList(),
  ): List<RenderedPill> = with(drawScope) {
    // 1. Architectural grid
    drawGrid(panPx, scalePxPerMm, unitSystem, paints, density)

    // 1b. Subtle closed-room interior fills when room area calculator or room-label tool is active
    if (showRoomAreas || highlightRoomDropZones) {
      for (room in detectedRooms) {
        drawRoomFill(room, panPx, scalePxPerMm, highlightBoundary = highlightRoomDropZones, density = density)
      }
    }

    // 2. Furniture & fixture symbols (under walls for crisp architectural layering)
    for (sym in plan.symbols) {
      val isSelected = selection is SelectionTarget.SelectedSymbol && selection.id == sym.id
      drawPlacedSymbol(sym, panPx, scalePxPerMm, isSelected, density)
    }

    // 3. Walls (cut cleanly around doors and windows)
    for (wall in plan.walls) {
      val isSelected = selection is SelectionTarget.SelectedWall && selection.id == wall.id
      drawWall(wall, plan.doors, plan.windows, panPx, scalePxPerMm, isSelected, density)
    }

    // 4. Doors
    for (door in plan.doors) {
      val wall = plan.walls.find { it.id == door.wallId } ?: continue
      val isSelected = selection is SelectionTarget.SelectedDoor && selection.id == door.id
      drawDoor(wall, door, panPx, scalePxPerMm, isSelected, density)
    }

    // 5. Windows
    for (win in plan.windows) {
      val wall = plan.walls.find { it.id == win.wallId } ?: continue
      val isSelected = selection is SelectionTarget.SelectedWindow && selection.id == win.id
      drawWindow(wall, win, panPx, scalePxPerMm, isSelected, density)
    }

    // 6. Manual linear dimensions
    for (dim in plan.dimensions) {
      val isSelected = selection is SelectionTarget.SelectedDimension && selection.id == dim.id
      drawLinearDimension(dim, panPx, scalePxPerMm, unitSystem, isSelected, paints, density)
    }

    // 6b. Text labels
    for (txt in plan.texts) {
      val isSelected = selection is SelectionTarget.SelectedText && selection.id == txt.id
      drawTextLabel(txt, panPx, scalePxPerMm, isSelected, paints, density)
    }

    // 6c. Automatic Room Area badges
    if (showRoomAreas) {
      for (room in detectedRooms) {
        drawRoomAreaBadge(room, plan.texts, panPx, scalePxPerMm, unitSystem, paints, density)
      }
    }

    // 7. Active drafting preview (wall or dimension being drawn)
    wallDraftPreview?.let { (start, endSnap) ->
      drawWallPreview(start, endSnap, panPx, scalePxPerMm, unitSystem, paints, density)
    }
    dimDraftPreview?.let { (start, end) ->
      val previewDim = LinearDimension(id = "preview", start = start, end = end, offsetMm = 420f)
      drawLinearDimension(previewDim, panPx, scalePxPerMm, unitSystem, isSelected = true, paints, density)
    }

    // 8. Selection handles (wall endpoints / symbol rotate handle / dim endpoints)
    drawSelectionHandles(plan, selection, panPx, scalePxPerMm, density)

    // 9. Active snap highlight (wall endpoints, midpoints, T-junctions, alignment guides)
    activeSnapPoint?.let { snap ->
      val p = worldToScreen(snap.point, panPx, scalePxPerMm)
      snap.alignmentRef?.let { ref ->
        val rScreen = worldToScreen(ref, panPx, scalePxPerMm)
        drawLine(
          color = SnapAmber.copy(alpha = 0.7f),
          start = rScreen,
          end = p,
          strokeWidth = 1.4f * density,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 5f * density)),
        )
      }
      when (snap.snapKind) {
        SnapKind.ENDPOINT -> {
          val sq = 8f * density
          drawRect(
            color = SnapAmber.copy(alpha = 0.28f),
            topLeft = Offset(p.x - sq, p.y - sq),
            size = Size(sq * 2f, sq * 2f),
          )
          drawRect(
            color = SnapAmber,
            topLeft = Offset(p.x - sq, p.y - sq),
            size = Size(sq * 2f, sq * 2f),
            style = Stroke(width = 2.4f * density),
          )
        }
        SnapKind.MIDPOINT -> {
          val r = 9f * density
          val diamond = Path().apply {
            moveTo(p.x, p.y - r)
            lineTo(p.x + r, p.y)
            lineTo(p.x, p.y + r)
            lineTo(p.x - r, p.y)
            close()
          }
          drawPath(diamond, color = SnapAmber.copy(alpha = 0.25f))
          drawPath(diamond, color = SnapAmber, style = Stroke(width = 2.2f * density))
        }
        SnapKind.T_JUNCTION -> {
          val r = 9f * density
          drawCircle(color = SnapAmber.copy(alpha = 0.22f), radius = r, center = p)
          drawCircle(color = SnapAmber, radius = r, center = p, style = Stroke(width = 2.2f * density))
          drawLine(SnapAmber, Offset(p.x - r, p.y), Offset(p.x + r, p.y), strokeWidth = 1.8f * density)
          drawLine(SnapAmber, Offset(p.x, p.y - r), Offset(p.x, p.y + r), strokeWidth = 1.8f * density)
        }
        SnapKind.ALIGNMENT -> {
          drawCircle(
            color = SnapAmber,
            radius = 7f * density,
            center = p,
            style = Stroke(width = 2f * density),
          )
        }
        else -> if (snap.snappedToEndpoint) {
          drawCircle(
            color = SnapAmber,
            radius = 10f * density,
            center = p,
            style = Stroke(width = 2.5f * density),
          )
        }
      }
    }

    // 9b. Smart Alignment Guides (temporary dashed extension lines while moving elements)
    for (guide in activeSmartGuides) {
      drawSmartGuide(guide, panPx, scalePxPerMm, paints, density)
    }

    // 10. Temporary blue accent dimensions (rendered on top with tappable pills)
    val renderedPills = mutableListOf<RenderedPill>()
    for (td in tempDimensions) {
      renderedPills += drawTempDimension(td, panPx, scalePxPerMm, unitSystem, paints, density)
    }
    renderedPills
  }

  private fun DrawScope.drawGrid(
    panPx: Offset,
    scale: Float,
    unitSystem: UnitSystem,
    paints: DraftingPaints,
    density: Float,
  ) {
    val minorMm = GeometryMath.minorGridMm(unitSystem)
    val majorMm = GeometryMath.majorGridMm(unitSystem)
    val minorPx = minorMm * scale
    val showMinor = minorPx >= 12f * density

    val topLeftWorld = screenToWorld(Offset.Zero, panPx, scale)
    val bottomRightWorld = screenToWorld(Offset(size.width, size.height), panPx, scale)

    val minX = min(topLeftWorld.x, bottomRightWorld.x)
    val maxX = max(topLeftWorld.x, bottomRightWorld.x)
    val minY = min(topLeftWorld.y, bottomRightWorld.y)
    val maxY = max(topLeftWorld.y, bottomRightWorld.y)

    if (showMinor) {
      val startX = floor(minX / minorMm) * minorMm
      val endX = ceil(maxX / minorMm) * minorMm
      var x = startX
      while (x <= endX) {
        val sx = x * scale + panPx.x
        drawLine(DraftGridMinor, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1f)
        x += minorMm
      }
      val startY = floor(minY / minorMm) * minorMm
      val endY = ceil(maxY / minorMm) * minorMm
      var y = startY
      while (y <= endY) {
        val sy = y * scale + panPx.y
        drawLine(DraftGridMinor, Offset(0f, sy), Offset(size.width, sy), strokeWidth = 1f)
        y += minorMm
      }
    }

    val startMajorX = floor(minX / majorMm) * majorMm
    val endMajorX = ceil(maxX / majorMm) * majorMm
    var mx = startMajorX
    while (mx <= endMajorX) {
      val sx = mx * scale + panPx.x
      drawLine(DraftGridMajor, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1.3f)
      mx += majorMm
    }
    val startMajorY = floor(minY / majorMm) * majorMm
    val endMajorY = ceil(maxY / majorMm) * majorMm
    var my = startMajorY
    while (my <= endMajorY) {
      val sy = my * scale + panPx.y
      drawLine(DraftGridMajor, Offset(0f, sy), Offset(size.width, sy), strokeWidth = 1.3f)
      my += majorMm
    }

    // Subtle origin crosshair
    val origin = worldToScreen(Vec2(0f, 0f), panPx, scale)
    if (origin.x in -100f..(size.width + 100f) && origin.y in -100f..(size.height + 100f)) {
      drawLine(
        color = BlueprintCobalt.copy(alpha = 0.28f),
        start = Offset(origin.x - 16f * density, origin.y),
        end = Offset(origin.x + 16f * density, origin.y),
        strokeWidth = 1.5f * density,
      )
      drawLine(
        color = BlueprintCobalt.copy(alpha = 0.28f),
        start = Offset(origin.x, origin.y - 16f * density),
        end = Offset(origin.x, origin.y + 16f * density),
        strokeWidth = 1.5f * density,
      )
      drawContext.canvas.nativeCanvas.drawText(
        "0,0",
        origin.x + 8f * density,
        origin.y - 8f * density,
        paints.gridLabelText,
      )
    }
  }

  private fun DrawScope.drawWall(
    wall: Wall,
    doors: List<DoorOpening>,
    windows: List<WindowOpening>,
    panPx: Offset,
    scale: Float,
    isSelected: Boolean,
    density: Float,
  ) {
    val segs = GeometryMath.computeWallSolidSegments(wall, doors, windows)
    val len = GeometryMath.wallLength(wall)
    if (len < 1f) return
    val dir = GeometryMath.wallDir(wall)
    val normal = GeometryMath.wallNormal(wall)
    val halfT = wall.thicknessMm * 0.5f

    // Subtle corner extension only on full ends (0f or 1f)
    var startExt = 0f
    var endExt = 0f
    // Use clean half-thickness extension at connected corners when segment reaches 0 or 1
    for (s in segs) {
      val t0 = s.first
      val t1 = s.second
      var a = GeometryMath.lerp(wall.start, wall.end, t0)
      var b = GeometryMath.lerp(wall.start, wall.end, t1)
      if (t0 < 0.002f) {
        a = GeometryMath.sub(a, GeometryMath.mul(dir, startExt))
      }
      if (t1 > 0.998f) {
        b = GeometryMath.add(b, GeometryMath.mul(dir, endExt))
      }
      val nOff = GeometryMath.mul(normal, halfT)
      val p1 = worldToScreen(GeometryMath.add(a, nOff), panPx, scale)
      val p2 = worldToScreen(GeometryMath.add(b, nOff), panPx, scale)
      val p3 = worldToScreen(GeometryMath.sub(b, nOff), panPx, scale)
      val p4 = worldToScreen(GeometryMath.sub(a, nOff), panPx, scale)
      val path = Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        lineTo(p4.x, p4.y)
        close()
      }
      drawPath(path, color = if (isSelected) Color(0xFF1E3A8A) else InkCharcoal)
      drawPath(
        path,
        color = if (isSelected) BlueprintCobalt else Color(0xFF0F172A),
        style = Stroke(width = if (isSelected) 2.5f * density else 1.2f * density),
      )
    }

    // End caps on corners for crisp T-junctions & L-corners
    val sScreen = worldToScreen(wall.start, panPx, scale)
    val eScreen = worldToScreen(wall.end, panPx, scale)
    val capRadius = halfT * scale
    if (segs.any { it.first < 0.002f }) {
      drawCircle(
        color = if (isSelected) Color(0xFF1E3A8A) else InkCharcoal,
        radius = capRadius,
        center = sScreen,
      )
    }
    if (segs.any { it.second > 0.998f }) {
      drawCircle(
        color = if (isSelected) Color(0xFF1E3A8A) else InkCharcoal,
        radius = capRadius,
        center = eScreen,
      )
    }

    if (isSelected) {
      drawLine(
        color = BlueprintCobalt.copy(alpha = 0.65f),
        start = sScreen,
        end = eScreen,
        strokeWidth = 1.5f * density,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density)),
      )
    }
  }

  private fun DrawScope.drawDoor(
    wall: Wall,
    door: DoorOpening,
    panPx: Offset,
    scale: Float,
    isSelected: Boolean,
    density: Float,
  ) {
    val len = GeometryMath.wallLength(wall)
    if (len < 1f) return
    val half = (door.widthMm * 0.5f) / len
    val a = GeometryMath.lerp(wall.start, wall.end, (door.t - half).coerceIn(0f, 1f))
    val b = GeometryMath.lerp(wall.start, wall.end, (door.t + half).coerceIn(0f, 1f))
    val pA = worldToScreen(a, panPx, scale)
    val pB = worldToScreen(b, panPx, scale)

    val dir = GeometryMath.wallDir(wall)
    val baseAngleDeg = (atan2(dir.y, dir.x) * 180.0 / PI).toFloat()
    val wPx = door.widthMm * scale

    val leafColor = if (isSelected) BlueprintCobalt else InkCharcoal
    val arcColor = if (isSelected) BlueprintCobalt.copy(alpha = 0.75f) else InkSlate.copy(alpha = 0.75f)
    val strokeW = (if (isSelected) 3.2f else 2.2f) * density
    val arcDash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 5f * density))

    // Subtle opening threshold line
    drawLine(
      color = Color(0xFFCBD5E1),
      start = pA,
      end = pB,
      strokeWidth = 1f * density,
    )

    when (door.doorType) {
      DoorType.SLIDING -> {
        val nx = -dir.y * (wall.thicknessMm * 0.16f * scale)
        val ny = dir.x * (wall.thicknessMm * 0.16f * scale)
        val mid = Offset((pA.x + pB.x) * 0.5f, (pA.y + pB.y) * 0.5f)
        val overlap = Offset((pB.x - pA.x) * 0.1f, (pB.y - pA.y) * 0.1f)
        drawLine(
          color = leafColor,
          start = Offset(pA.x + nx, pA.y + ny),
          end = Offset(mid.x + nx + overlap.x, mid.y + ny + overlap.y),
          strokeWidth = strokeW,
          cap = StrokeCap.Square,
        )
        drawLine(
          color = leafColor,
          start = Offset(mid.x - nx - overlap.x, mid.y - ny - overlap.y),
          end = Offset(pB.x - nx, pB.y - ny),
          strokeWidth = strokeW,
          cap = StrokeCap.Square,
        )
      }

      DoorType.DOUBLE_SWING -> {
        val r = wPx * 0.5f
        val swingSign = if (door.swingLeft) -1f else 1f
        val nX = -dir.y * swingSign
        val nY = dir.x * swingSign
        val tipA = Offset(pA.x + nX * r, pA.y + nY * r)
        val tipB = Offset(pB.x + nX * r, pB.y + nY * r)
        drawLine(leafColor, pA, tipA, strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(leafColor, pB, tipB, strokeWidth = strokeW, cap = StrokeCap.Round)
        drawArc(
          color = arcColor,
          startAngle = baseAngleDeg,
          sweepAngle = swingSign * 90f,
          useCenter = false,
          topLeft = Offset(pA.x - r, pA.y - r),
          size = Size(r * 2f, r * 2f),
          style = Stroke(width = 1.4f * density, pathEffect = arcDash),
        )
        drawArc(
          color = arcColor,
          startAngle = baseAngleDeg + 180f,
          sweepAngle = -swingSign * 90f,
          useCenter = false,
          topLeft = Offset(pB.x - r, pB.y - r),
          size = Size(r * 2f, r * 2f),
          style = Stroke(width = 1.4f * density, pathEffect = arcDash),
        )
      }

      DoorType.SINGLE_SWING -> {
        val hinge = if (door.flipHinge) pB else pA
        val swingSign = if (door.swingLeft) -1f else 1f
        val nX = -dir.y * swingSign
        val nY = dir.x * swingSign
        val tip = Offset(hinge.x + nX * wPx, hinge.y + nY * wPx)
        drawLine(leafColor, hinge, tip, strokeWidth = strokeW, cap = StrokeCap.Round)
        val startAngle = if (door.flipHinge) baseAngleDeg + 180f else baseAngleDeg
        val sweep = if (door.flipHinge) -swingSign * 90f else swingSign * 90f
        drawArc(
          color = arcColor,
          startAngle = startAngle,
          sweepAngle = sweep,
          useCenter = false,
          topLeft = Offset(hinge.x - wPx, hinge.y - wPx),
          size = Size(wPx * 2f, wPx * 2f),
          style = Stroke(width = 1.4f * density, pathEffect = arcDash),
        )
      }
    }
  }

  private fun DrawScope.drawWindow(
    wall: Wall,
    win: WindowOpening,
    panPx: Offset,
    scale: Float,
    isSelected: Boolean,
    density: Float,
  ) {
    val len = GeometryMath.wallLength(wall)
    if (len < 1f) return
    val half = (win.widthMm * 0.5f) / len
    val a = GeometryMath.lerp(wall.start, wall.end, (win.t - half).coerceIn(0f, 1f))
    val b = GeometryMath.lerp(wall.start, wall.end, (win.t + half).coerceIn(0f, 1f))
    val normal = GeometryMath.wallNormal(wall)
    val nOff = GeometryMath.mul(normal, wall.thicknessMm * 0.48f)

    val p1 = worldToScreen(GeometryMath.add(a, nOff), panPx, scale)
    val p2 = worldToScreen(GeometryMath.add(b, nOff), panPx, scale)
    val p3 = worldToScreen(GeometryMath.sub(b, nOff), panPx, scale)
    val p4 = worldToScreen(GeometryMath.sub(a, nOff), panPx, scale)

    val framePath = Path().apply {
      moveTo(p1.x, p1.y)
      lineTo(p2.x, p2.y)
      lineTo(p3.x, p3.y)
      lineTo(p4.x, p4.y)
      close()
    }
    drawPath(framePath, color = Color(0xFFE0F2FE))
    drawPath(
      framePath,
      color = if (isSelected) BlueprintCobalt else Color(0xFF0284C7),
      style = Stroke(width = (if (isSelected) 2.5f else 1.5f) * density),
    )

    // Double glazing center lines
    val gOff = GeometryMath.mul(normal, wall.thicknessMm * 0.14f)
    drawLine(
      color = if (isSelected) BlueprintCobalt else Color(0xFF0369A1),
      start = worldToScreen(GeometryMath.add(a, gOff), panPx, scale),
      end = worldToScreen(GeometryMath.add(b, gOff), panPx, scale),
      strokeWidth = 1.2f * density,
    )
    drawLine(
      color = if (isSelected) BlueprintCobalt else Color(0xFF0369A1),
      start = worldToScreen(GeometryMath.sub(a, gOff), panPx, scale),
      end = worldToScreen(GeometryMath.sub(b, gOff), panPx, scale),
      strokeWidth = 1.2f * density,
    )
  }

  fun DrawScope.drawSymbolGeometry(
    type: SymbolType,
    w: Float,
    d: Float,
    strokeColor: Color,
    fillColor: Color,
    strokeWidth: Float,
  ) {
    val left = -w * 0.5f
    val top = -d * 0.5f

    when (type) {
      SymbolType.WC_TOILET -> {
        val tankH = d * 0.28f
        drawRoundRect(
          color = fillColor,
          topLeft = Offset(left, top),
          size = Size(w, tankH),
          cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
        )
        drawRoundRect(
          color = strokeColor,
          topLeft = Offset(left, top),
          size = Size(w, tankH),
          cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
          style = Stroke(strokeWidth),
        )
        drawOval(
          color = fillColor,
          topLeft = Offset(left + w * 0.08f, top + tankH * 0.9f),
          size = Size(w * 0.84f, d - tankH * 0.9f),
        )
        drawOval(
          color = strokeColor,
          topLeft = Offset(left + w * 0.08f, top + tankH * 0.9f),
          size = Size(w * 0.84f, d - tankH * 0.9f),
          style = Stroke(strokeWidth),
        )
        drawOval(
          color = strokeColor,
          topLeft = Offset(left + w * 0.22f, top + tankH * 1.15f),
          size = Size(w * 0.56f, (d - tankH) * 0.68f),
          style = Stroke(strokeWidth * 0.75f),
        )
      }

      SymbolType.WASHBASIN -> {
        drawRoundRect(fillColor, Offset(left, top), Size(w, d), CornerRadius(w * 0.12f, w * 0.12f))
        drawRoundRect(strokeColor, Offset(left, top), Size(w, d), CornerRadius(w * 0.12f, w * 0.12f), Stroke(strokeWidth))
        drawOval(
          color = strokeColor,
          topLeft = Offset(left + w * 0.14f, top + d * 0.2f),
          size = Size(w * 0.72f, d * 0.64f),
          style = Stroke(strokeWidth * 0.8f),
        )
        drawCircle(strokeColor, radius = min(w, d) * 0.06f, center = Offset(0f, top + d * 0.52f))
      }

      SymbolType.SHOWER_TRAY -> {
        drawRect(fillColor, Offset(left, top), Size(w, d))
        drawRect(strokeColor, Offset(left, top), Size(w, d), style = Stroke(strokeWidth))
        val inset = min(w, d) * 0.1f
        drawRect(strokeColor, Offset(left + inset, top + inset), Size(w - inset * 2, d - inset * 2), style = Stroke(strokeWidth * 0.75f))
        drawLine(strokeColor, Offset(left + inset, top + inset), Offset(left + w - inset, top + d - inset), strokeWidth * 0.55f)
        drawLine(strokeColor, Offset(left + w - inset, top + inset), Offset(left + inset, top + d - inset), strokeWidth * 0.55f)
        drawCircle(fillColor, radius = min(w, d) * 0.07f, center = Offset(0f, 0f))
        drawCircle(strokeColor, radius = min(w, d) * 0.07f, center = Offset(0f, 0f), style = Stroke(strokeWidth * 0.8f))
      }

      SymbolType.BATHTUB -> {
        drawRoundRect(fillColor, Offset(left, top), Size(w, d), CornerRadius(d * 0.12f, d * 0.12f))
        drawRoundRect(strokeColor, Offset(left, top), Size(w, d), CornerRadius(d * 0.12f, d * 0.12f), Stroke(strokeWidth))
        val pad = d * 0.14f
        drawRoundRect(
          color = strokeColor,
          topLeft = Offset(left + pad, top + pad),
          size = Size(w - pad * 2f, d - pad * 2f),
          cornerRadius = CornerRadius(d * 0.35f, d * 0.35f),
          style = Stroke(strokeWidth * 0.8f),
        )
        drawCircle(strokeColor, radius = d * 0.055f, center = Offset(left + pad * 2f, 0f), style = Stroke(strokeWidth * 0.8f))
      }

      SymbolType.KITCHEN_SINK -> {
        drawRect(fillColor, Offset(left, top), Size(w, d))
        drawRect(strokeColor, Offset(left, top), Size(w, d), style = Stroke(strokeWidth))
        val bowlW = w * 0.38f
        val bowlH = d * 0.68f
        drawRoundRect(strokeColor, Offset(left + w * 0.08f, top + d * 0.18f), Size(bowlW, bowlH), CornerRadius(6f, 6f), Stroke(strokeWidth * 0.8f))
        // Drainer lines on right half
        for (i in 0..3) {
          val x = left + w * 0.56f + i * (w * 0.09f)
          drawLine(strokeColor, Offset(x, top + d * 0.2f), Offset(x, top + d * 0.8f), strokeWidth * 0.6f)
        }
        drawCircle(strokeColor, radius = d * 0.05f, center = Offset(left + w * 0.27f, top + d * 0.12f))
      }

      SymbolType.STOVE_HOB -> {
        drawRect(fillColor, Offset(left, top), Size(w, d))
        drawRect(strokeColor, Offset(left, top), Size(w, d), style = Stroke(strokeWidth))
        val offsets = listOf(
          Offset(-w * 0.23f, -d * 0.23f) to 0.16f,
          Offset(w * 0.23f, -d * 0.23f) to 0.13f,
          Offset(-w * 0.23f, d * 0.23f) to 0.13f,
          Offset(w * 0.23f, d * 0.23f) to 0.16f,
        )
        for ((pos, rFrac) in offsets) {
          val r = min(w, d) * rFrac
          drawCircle(strokeColor, radius = r, center = pos, style = Stroke(strokeWidth * 0.8f))
          drawCircle(strokeColor, radius = r * 0.45f, center = pos, style = Stroke(strokeWidth * 0.6f))
        }
      }

      SymbolType.BED_SINGLE, SymbolType.BED_DOUBLE, SymbolType.BED_QUEEN -> {
        drawRect(fillColor, Offset(left, top), Size(w, d))
        drawRect(strokeColor, Offset(left, top), Size(w, d), style = Stroke(strokeWidth))
        // Headboard
        drawRect(strokeColor.copy(alpha = 0.2f), Offset(left, top), Size(w, d * 0.08f))
        drawLine(strokeColor, Offset(left, top + d * 0.08f), Offset(left + w, top + d * 0.08f), strokeWidth)
        // Pillows
        val pillowH = d * 0.18f
        val pillowY = top + d * 0.12f
        if (type == SymbolType.BED_SINGLE) {
          drawRoundRect(strokeColor, Offset(left + w * 0.14f, pillowY), Size(w * 0.72f, pillowH), CornerRadius(6f, 6f), Stroke(strokeWidth * 0.75f))
        } else {
          drawRoundRect(strokeColor, Offset(left + w * 0.07f, pillowY), Size(w * 0.4f, pillowH), CornerRadius(6f, 6f), Stroke(strokeWidth * 0.75f))
          drawRoundRect(strokeColor, Offset(left + w * 0.53f, pillowY), Size(w * 0.4f, pillowH), CornerRadius(6f, 6f), Stroke(strokeWidth * 0.75f))
        }
        // Duvet fold line
        drawLine(strokeColor, Offset(left, top + d * 0.38f), Offset(left + w, top + d * 0.38f), strokeWidth * 0.75f)
      }

      SymbolType.SOFA_2SEAT, SymbolType.SOFA_3SEAT -> {
        val seats = if (type == SymbolType.SOFA_2SEAT) 2 else 3
        drawRoundRect(fillColor, Offset(left, top), Size(w, d), CornerRadius(8f, 8f))
        drawRoundRect(strokeColor, Offset(left, top), Size(w, d), CornerRadius(8f, 8f), Stroke(strokeWidth))
        val backH = d * 0.22f
        val armW = w * 0.08f
        drawLine(strokeColor, Offset(left + armW, top + backH), Offset(left + w - armW, top + backH), strokeWidth * 0.8f)
        drawLine(strokeColor, Offset(left + armW, top), Offset(left + armW, top + d), strokeWidth * 0.8f)
        drawLine(strokeColor, Offset(left + w - armW, top), Offset(left + w - armW, top + d), strokeWidth * 0.8f)
        val innerW = w - armW * 2f
        for (i in 1 until seats) {
          val x = left + armW + innerW * i / seats
          drawLine(strokeColor, Offset(x, top + backH), Offset(x, top + d), strokeWidth * 0.65f)
        }
      }

      SymbolType.SOFA_LSHAPE -> {
        val armW = w * 0.07f
        val backH = d * 0.14f
        val seatD = d * 0.52f
        val chaiseW = w * 0.38f
        val path = Path().apply {
          moveTo(left, top)
          lineTo(left + w, top)
          lineTo(left + w, top + d)
          lineTo(left + w - chaiseW, top + d)
          lineTo(left + w - chaiseW, top + seatD)
          lineTo(left, top + seatD)
          close()
        }
        drawPath(path, fillColor)
        drawPath(path, strokeColor, style = Stroke(strokeWidth))
        drawLine(strokeColor, Offset(left + armW, top + backH), Offset(left + w - armW, top + backH), strokeWidth * 0.75f)
        drawLine(strokeColor, Offset(left + w - chaiseW, top + backH), Offset(left + w - chaiseW, top + seatD), strokeWidth * 0.65f)
      }

      SymbolType.DINING_TABLE -> {
        val tblInsetX = w * 0.1f
        val tblInsetY = d * 0.16f
        val tblW = w - tblInsetX * 2f
        val tblH = d - tblInsetY * 2f
        // Chairs top & bottom
        val chairW = tblW * 0.22f
        val chairD = tblInsetY * 0.85f
        for (i in 0..2) {
          val cx = left + tblInsetX + tblW * (0.17f + i * 0.33f) - chairW * 0.5f
          drawRect(fillColor, Offset(cx, top), Size(chairW, chairD))
          drawRect(strokeColor, Offset(cx, top), Size(chairW, chairD), style = Stroke(strokeWidth * 0.7f))
          drawRect(fillColor, Offset(cx, top + d - chairD), Size(chairW, chairD))
          drawRect(strokeColor, Offset(cx, top + d - chairD), Size(chairW, chairD), style = Stroke(strokeWidth * 0.7f))
        }
        drawRect(fillColor, Offset(left + tblInsetX, top + tblInsetY), Size(tblW, tblH))
        drawRect(strokeColor, Offset(left + tblInsetX, top + tblInsetY), Size(tblW, tblH), style = Stroke(strokeWidth))
      }

      SymbolType.COFFEE_TABLE -> {
        drawRoundRect(fillColor, Offset(left, top), Size(w, d), CornerRadius(8f, 8f))
        drawRoundRect(strokeColor, Offset(left, top), Size(w, d), CornerRadius(8f, 8f), Stroke(strokeWidth))
        val pad = min(w, d) * 0.12f
        drawRoundRect(strokeColor, Offset(left + pad, top + pad), Size(w - pad * 2, d - pad * 2), CornerRadius(4f, 4f), Stroke(strokeWidth * 0.55f))
      }

      SymbolType.DESK_CHAIR -> {
        val deskD = d * 0.65f
        drawRect(fillColor, Offset(left, top), Size(w, deskD))
        drawRect(strokeColor, Offset(left, top), Size(w, deskD), style = Stroke(strokeWidth))
        val chairR = min(w, d) * 0.22f
        val chairCenter = Offset(0f, top + deskD + (d - deskD) * 0.55f)
        drawCircle(fillColor, radius = chairR, center = chairCenter)
        drawCircle(strokeColor, radius = chairR, center = chairCenter, style = Stroke(strokeWidth * 0.8f))
      }

      SymbolType.WARDROBE -> {
        drawRect(fillColor, Offset(left, top), Size(w, d))
        drawRect(strokeColor, Offset(left, top), Size(w, d), style = Stroke(strokeWidth))
        drawLine(strokeColor, Offset(left, top), Offset(left + w, top + d), strokeWidth * 0.6f)
        drawLine(strokeColor, Offset(left + w, top), Offset(left, top + d), strokeWidth * 0.6f)
        drawLine(strokeColor, Offset(0f, top), Offset(0f, top + d), strokeWidth * 0.7f)
      }
    }
  }

  private fun DrawScope.drawPlacedSymbol(
    sym: PlacedSymbol,
    panPx: Offset,
    scale: Float,
    isSelected: Boolean,
    density: Float,
  ) {
    val c = worldToScreen(sym.center, panPx, scale)
    val wPx = sym.widthMm * scale
    val dPx = sym.depthMm * scale
    val strokeColor = if (isSelected) BlueprintCobalt else InkSlate
    val fillColor = if (isSelected) Color(0xFFEFF6FF) else Color(0xFFFFFFFF)
    val strokeW = (if (isSelected) 2.2f else 1.4f) * density

    withTransform({
      translate(c.x, c.y)
      rotate(sym.rotationDeg, pivot = Offset.Zero)
    }) {
      drawSymbolGeometry(sym.type, wPx, dPx, strokeColor, fillColor, strokeW)
      if (isSelected) {
        val pad = 6f * density
        drawRect(
          color = BlueprintCobalt.copy(alpha = 0.7f),
          topLeft = Offset(-wPx * 0.5f - pad, -dPx * 0.5f - pad),
          size = Size(wPx + pad * 2f, dPx + pad * 2f),
          style = Stroke(
            width = 1.5f * density,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 5f * density)),
          ),
        )
      }
    }
  }

  private fun DrawScope.drawRoomFill(
    room: DetectedRoom,
    panPx: Offset,
    scale: Float,
    highlightBoundary: Boolean,
    density: Float,
  ) {
    val poly = if (room.innerPolygon.size >= 3) room.innerPolygon else room.polygon
    if (poly.size < 3) return
    val path = Path().apply {
      val first = worldToScreen(poly.first(), panPx, scale)
      moveTo(first.x, first.y)
      for (i in 1 until poly.size) {
        val p = worldToScreen(poly[i], panPx, scale)
        lineTo(p.x, p.y)
      }
      close()
    }
    drawPath(path, color = BlueprintCobalt.copy(alpha = if (highlightBoundary) 0.08f else 0.035f))
    if (highlightBoundary) {
      drawPath(
        path = path,
        color = BlueprintCobalt.copy(alpha = 0.55f),
        style = Stroke(
          width = 1.6f * density,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 5f * density)),
        ),
      )
      val c = worldToScreen(room.center, panPx, scale)
      val r = 6f * density
      drawLine(BlueprintCobalt.copy(alpha = 0.7f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1.5f * density)
      drawLine(BlueprintCobalt.copy(alpha = 0.7f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1.5f * density)
    }
  }

  private fun DrawScope.drawSmartGuide(
    guide: SmartGuide,
    panPx: Offset,
    scale: Float,
    paints: DraftingPaints,
    density: Float,
  ) {
    val pStart = worldToScreen(guide.start, panPx, scale)
    val pEnd = worldToScreen(guide.end, panPx, scale)
    val refScreen = worldToScreen(guide.referencePoint, panPx, scale)
    val tgtScreen = worldToScreen(guide.targetPoint, panPx, scale)
    val guideColor = Color(0xFFDB2777)

    // Extend dashed line slightly beyond both reference and target for crisp drafting feel
    val extPx = 18f * density
    val lineStart: Offset
    val lineEnd: Offset
    if (guide.axis == GuideAxis.HORIZONTAL) {
      val minX = min(pStart.x, pEnd.x) - extPx
      val maxX = max(pStart.x, pEnd.x) + extPx
      lineStart = Offset(minX, pEnd.y)
      lineEnd = Offset(maxX, pEnd.y)
    } else {
      val minY = min(pStart.y, pEnd.y) - extPx
      val maxY = max(pStart.y, pEnd.y) + extPx
      lineStart = Offset(pEnd.x, minY)
      lineEnd = Offset(pEnd.x, maxY)
    }

    drawLine(
      color = guideColor,
      start = lineStart,
      end = lineEnd,
      strokeWidth = 1.6f * density,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density)),
    )

    // Crosshair markers on both aligned points
    val mark = 4.5f * density
    for (pt in listOf(refScreen, tgtScreen)) {
      drawLine(guideColor, Offset(pt.x - mark, pt.y - mark), Offset(pt.x + mark, pt.y + mark), strokeWidth = 1.6f * density)
      drawLine(guideColor, Offset(pt.x - mark, pt.y + mark), Offset(pt.x + mark, pt.y - mark), strokeWidth = 1.6f * density)
    }

    if (guide.label.isNotEmpty()) {
      val mid = Offset((refScreen.x + tgtScreen.x) * 0.5f, (refScreen.y + tgtScreen.y) * 0.5f)
      val badgeY = if (guide.axis == GuideAxis.HORIZONTAL) mid.y - 12f * density else mid.y
      val badgeX = if (guide.axis == GuideAxis.VERTICAL) mid.x + 24f * density else mid.x
      paints.gridLabelText.color = guideColor.toArgb()
      paints.gridLabelText.textAlign = Paint.Align.CENTER
      drawContext.canvas.nativeCanvas.drawText(guide.label, badgeX, badgeY, paints.gridLabelText)
      paints.gridLabelText.color = Color(0xFF94A3B8).toArgb()
      paints.gridLabelText.textAlign = Paint.Align.LEFT
    }
  }

  private fun DrawScope.drawRoomAreaBadge(
    room: DetectedRoom,
    texts: List<TextLabel>,
    panPx: Offset,
    scale: Float,
    unitSystem: UnitSystem,
    paints: DraftingPaints,
    density: Float,
  ) {
    val areaLabel = UnitFormatter.formatArea(room.areaSqMm, unitSystem)
    val basePos = worldToScreen(room.center, panPx, scale)
    val nearText = texts.any { GeometryMath.distance(it.position, room.center) < 420f }
    val pos = if (nearText) Offset(basePos.x, basePos.y + 22f * density) else basePos

    paints.areaBadgeText.textSize = 11f * density
    val w = paints.areaBadgeText.measureText(areaLabel) + 16f * density
    val h = 21f * density
    drawRoundRect(
      color = Color(0xFFEFF6FF).copy(alpha = 0.9f),
      topLeft = Offset(pos.x - w * 0.5f, pos.y - h * 0.5f),
      size = Size(w, h),
      cornerRadius = CornerRadius(h * 0.5f, h * 0.5f),
    )
    drawRoundRect(
      color = BlueprintCobalt.copy(alpha = 0.35f),
      topLeft = Offset(pos.x - w * 0.5f, pos.y - h * 0.5f),
      size = Size(w, h),
      cornerRadius = CornerRadius(h * 0.5f, h * 0.5f),
      style = Stroke(width = 1f * density),
    )
    drawContext.canvas.nativeCanvas.drawText(
      areaLabel,
      pos.x,
      pos.y + 4f * density,
      paints.areaBadgeText,
    )
  }

  private fun DrawScope.drawTextLabel(
    txt: TextLabel,
    panPx: Offset,
    scale: Float,
    isSelected: Boolean,
    paints: DraftingPaints,
    density: Float,
  ) {
    val sp = worldToScreen(txt.position, panPx, scale)
    val pxSize = (txt.fontSizeMm * scale).coerceIn(10f * density, 56f * density)
    paints.planTextLabel.textSize = pxSize
    paints.planTextLabel.color = if (isSelected) BlueprintCobalt.toArgb() else InkCharcoal.toArgb()

    val textW = paints.planTextLabel.measureText(txt.text)
    val padX = 8f * density
    val padY = 5f * density

    rotate(txt.rotationDeg, pivot = sp) {
      if (txt.hasOpaqueBackground) {
        drawRoundRect(
          color = Color(0xFFF7F8FA).copy(alpha = 0.94f),
          topLeft = Offset(sp.x - textW * 0.5f - padX, sp.y - pxSize * 0.55f - padY),
          size = Size(textW + padX * 2f, pxSize * 1.1f + padY * 2f),
          cornerRadius = CornerRadius(6f * density, 6f * density),
        )
      }
      if (isSelected) {
        drawRoundRect(
          color = BlueprintCobalt,
          topLeft = Offset(sp.x - textW * 0.5f - padX, sp.y - pxSize * 0.55f - padY),
          size = Size(textW + padX * 2f, pxSize * 1.1f + padY * 2f),
          cornerRadius = CornerRadius(6f * density, 6f * density),
          style = Stroke(
            width = 1.6f * density,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density)),
          ),
        )
      }
      drawContext.canvas.nativeCanvas.drawText(
        txt.text,
        sp.x,
        sp.y + pxSize * 0.35f,
        paints.planTextLabel,
      )
    }
  }

  private fun DrawScope.drawLinearDimension(
    dim: LinearDimension,
    panPx: Offset,
    scale: Float,
    unitSystem: UnitSystem,
    isSelected: Boolean,
    paints: DraftingPaints,
    density: Float,
  ) {
    val (w1, w2) = GeometryMath.dimensionLineEndpoints(dim)
    val s1 = worldToScreen(dim.start, panPx, scale)
    val s2 = worldToScreen(dim.end, panPx, scale)
    val p1 = worldToScreen(w1, panPx, scale)
    val p2 = worldToScreen(w2, panPx, scale)

    val lineColor = if (isSelected) BlueprintCobalt else Color(0xFF475569)
    val strokeW = (if (isSelected) 2.0f else 1.3f) * density

    drawLine(lineColor.copy(alpha = 0.65f), s1, p1, strokeWidth = 1f * density)
    drawLine(lineColor.copy(alpha = 0.65f), s2, p2, strokeWidth = 1f * density)
    drawLine(lineColor, p1, p2, strokeWidth = strokeW)

    // Architectural 45° tick slashes
    drawTickSlash(p1, p2, lineColor, strokeW, density)
    drawTickSlash(p2, p1, lineColor, strokeW, density)

    val distMm = GeometryMath.distance(dim.start, dim.end)
    val label = UnitFormatter.formatLength(distMm, unitSystem)
    val mid = Offset((p1.x + p2.x) * 0.5f, (p1.y + p2.y) * 0.5f)
    val angle = (atan2(p2.y - p1.y, p2.x - p1.x) * 180.0 / PI).toFloat()
    val normAngle = if (angle > 90f || angle < -90f) angle + 180f else angle

    val textW = paints.dimText.measureText(label) + 14f * density
    val textH = 22f * density
    rotate(normAngle, pivot = mid) {
      drawRoundRect(
        color = Color(0xFFF7F8FA).copy(alpha = 0.94f),
        topLeft = Offset(mid.x - textW * 0.5f, mid.y - textH * 0.5f - 8f * density),
        size = Size(textW, textH),
        cornerRadius = CornerRadius(4f * density, 4f * density),
      )
      drawContext.canvas.nativeCanvas.drawText(
        label,
        mid.x,
        mid.y - 3f * density,
        paints.dimText,
      )
    }
  }

  private fun DrawScope.drawTickSlash(
    at: Offset,
    toward: Offset,
    color: Color,
    strokeW: Float,
    density: Float,
  ) {
    val dx = toward.x - at.x
    val dy = toward.y - at.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val ux = dx / len
    val uy = dy / len
    val r = 5.5f * density
    // 45 deg rotated vector from unit dir
    val tx = (ux - uy) * 0.707f * r
    val ty = (ux + uy) * 0.707f * r
    drawLine(color, Offset(at.x - tx, at.y - ty), Offset(at.x + tx, at.y + ty), strokeWidth = strokeW * 1.15f)
  }

  private fun DrawScope.drawTempDimension(
    td: TempDimension,
    panPx: Offset,
    scale: Float,
    unitSystem: UnitSystem,
    paints: DraftingPaints,
    density: Float,
  ): RenderedPill {
    val p1 = worldToScreen(td.start, panPx, scale)
    val p2 = worldToScreen(td.end, panPx, scale)
    val labelCenter = worldToScreen(td.labelCenter, panPx, scale)

    val accent = BlueprintCobalt
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density))
    drawLine(accent, p1, p2, strokeWidth = 1.8f * density, pathEffect = dash)
    drawCircle(accent, radius = 3.5f * density, center = p1)
    drawCircle(accent, radius = 3.5f * density, center = p2)

    val label = UnitFormatter.formatLength(td.distanceMm, unitSystem)
    val textW = paints.tempDimText.measureText(label)
    val pillW = max(62f * density, textW + 22f * density)
    val pillH = 28f * density
    val rect = Rect(
      left = labelCenter.x - pillW * 0.5f,
      top = labelCenter.y - pillH * 0.5f,
      right = labelCenter.x + pillW * 0.5f,
      bottom = labelCenter.y + pillH * 0.5f,
    )

    drawRoundRect(
      color = Color(0xFFEFF6FF),
      topLeft = rect.topLeft,
      size = rect.size,
      cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f),
    )
    drawRoundRect(
      color = accent,
      topLeft = rect.topLeft,
      size = rect.size,
      cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f),
      style = Stroke(width = 1.6f * density),
    )
    drawContext.canvas.nativeCanvas.drawText(
      label,
      labelCenter.x,
      labelCenter.y + 4.5f * density,
      paints.tempDimText,
    )

    // Generous touch target rect for tapping
    val touchPad = 10f * density
    val hitRect = Rect(
      left = rect.left - touchPad,
      top = rect.top - touchPad,
      right = rect.right + touchPad,
      bottom = rect.bottom + touchPad,
    )
    return RenderedPill(td, hitRect)
  }

  private fun DrawScope.drawWallPreview(
    start: Vec2,
    endSnap: SnapResult,
    panPx: Offset,
    scale: Float,
    unitSystem: UnitSystem,
    paints: DraftingPaints,
    density: Float,
  ) {
    val s = worldToScreen(start, panPx, scale)
    val e = worldToScreen(endSnap.point, panPx, scale)
    drawLine(
      color = BlueprintCobalt,
      start = s,
      end = e,
      strokeWidth = 6f * density,
      cap = StrokeCap.Round,
    )
    endSnap.angleGuideDeg?.let {
      drawLine(
        color = SnapAmber.copy(alpha = 0.55f),
        start = s,
        end = e,
        strokeWidth = 1.5f * density,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 5f * density)),
      )
    }
    val lenMm = GeometryMath.distance(start, endSnap.point)
    val label = UnitFormatter.formatLength(lenMm, unitSystem)
    val mid = Offset((s.x + e.x) * 0.5f, (s.y + e.y) * 0.5f - 28f * density)
    val w = paints.liveReadoutText.measureText(label) + 22f * density
    val h = 28f * density
    drawRoundRect(
      color = InkCharcoal,
      topLeft = Offset(mid.x - w * 0.5f, mid.y - h * 0.5f),
      size = Size(w, h),
      cornerRadius = CornerRadius(h * 0.5f, h * 0.5f),
    )
    drawContext.canvas.nativeCanvas.drawText(label, mid.x, mid.y + 4.5f * density, paints.liveReadoutText)
  }

  private fun DrawScope.drawSelectionHandles(
    plan: FloorPlanData,
    selection: SelectionTarget?,
    panPx: Offset,
    scale: Float,
    density: Float,
  ) {
    val handleR = 8f * density
    when (selection) {
      is SelectionTarget.SelectedWall -> {
        val w = plan.walls.find { it.id == selection.id } ?: return
        listOf(w.start, w.end).forEach { pt ->
          val sp = worldToScreen(pt, panPx, scale)
          drawCircle(Color.White, radius = handleR, center = sp)
          drawCircle(BlueprintCobalt, radius = handleR, center = sp, style = Stroke(2.5f * density))
        }
      }
      is SelectionTarget.SelectedSymbol -> {
        val sym = plan.symbols.find { it.id == selection.id } ?: return
        val rotPos = GeometryMath.symbolRotateHandlePos(sym, handleOffsetMm = 260f)
        val c = worldToScreen(sym.center, panPx, scale)
        val r = worldToScreen(rotPos, panPx, scale)
        drawLine(BlueprintCobalt, c, r, strokeWidth = 1.5f * density)
        drawCircle(Color.White, radius = handleR, center = r)
        drawCircle(BlueprintCobalt, radius = handleR, center = r, style = Stroke(2.5f * density))
      }
      is SelectionTarget.SelectedDimension -> {
        val dim = plan.dimensions.find { it.id == selection.id } ?: return
        val (p1, p2) = GeometryMath.dimensionLineEndpoints(dim)
        listOf(p1, p2).forEach { pt ->
          val sp = worldToScreen(pt, panPx, scale)
          drawCircle(Color.White, radius = handleR, center = sp)
          drawCircle(BlueprintCobalt, radius = handleR, center = sp, style = Stroke(2.5f * density))
        }
      }
      else -> Unit
    }
  }
}
