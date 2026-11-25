package com.example.geometry

import com.example.model.DoorOpening
import com.example.model.FloorPlanData
import com.example.model.LinearDimension
import com.example.model.PlacedSymbol
import com.example.model.SelectionTarget
import com.example.model.TextLabel
import com.example.model.UnitSystem
import com.example.model.Vec2
import com.example.model.Wall
import com.example.model.WindowOpening
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SnapKind {
  NONE,
  ENDPOINT,
  MIDPOINT,
  T_JUNCTION,
  ALIGNMENT,
  GRID,
}

data class SnapResult(
  val point: Vec2,
  val snappedToEndpoint: Boolean = false,
  val snapKind: SnapKind = SnapKind.NONE,
  val angleGuideDeg: Float? = null,
  val alignmentRef: Vec2? = null,
)

data class WallProjection(
  val wall: Wall,
  val t: Float,
  val closestPoint: Vec2,
  val distance: Float,
)

data class OpeningCut(
  val tStart: Float,
  val tEnd: Float,
)

data class Bounds2D(
  val minX: Float,
  val minY: Float,
  val maxX: Float,
  val maxY: Float,
) {
  val width: Float get() = maxX - minX
  val height: Float get() = maxY - minY
  val center: Vec2 get() = Vec2((minX + maxX) * 0.5f, (minY + maxY) * 0.5f)
}

object GeometryMath {

  fun distance(a: Vec2, b: Vec2): Float = hypot(b.x - a.x, b.y - a.y)

  fun dot(a: Vec2, b: Vec2): Float = a.x * b.x + a.y * b.y

  fun add(a: Vec2, b: Vec2): Vec2 = Vec2(a.x + b.x, a.y + b.y)

  fun sub(a: Vec2, b: Vec2): Vec2 = Vec2(a.x - b.x, a.y - b.y)

  fun mul(v: Vec2, s: Float): Vec2 = Vec2(v.x * s, v.y * s)

  fun lerp(a: Vec2, b: Vec2, t: Float): Vec2 =
    Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

  fun normalize(v: Vec2): Vec2 {
    val len = hypot(v.x, v.y)
    return if (len < 1e-4f) Vec2(1f, 0f) else Vec2(v.x / len, v.y / len)
  }

  fun wallLength(wall: Wall): Float = distance(wall.start, wall.end)

  fun wallDir(wall: Wall): Vec2 = normalize(sub(wall.end, wall.start))

  fun wallNormal(wall: Wall): Vec2 {
    val d = wallDir(wall)
    return Vec2(-d.y, d.x)
  }

  fun projectPointOnSegment(p: Vec2, a: Vec2, b: Vec2): Pair<Float, Vec2> {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq < 1e-4f) return 0f to a
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
    return t to Vec2(a.x + abx * t, a.y + aby * t)
  }

  fun pointToSegmentDistance(p: Vec2, a: Vec2, b: Vec2): Float {
    val (_, closest) = projectPointOnSegment(p, a, b)
    return distance(p, closest)
  }

  fun gridIncrementMm(unitSystem: UnitSystem): Float = when (unitSystem) {
    UnitSystem.METRIC -> 100f
    UnitSystem.IMPERIAL -> 152.4f // 6 inches
  }

  fun majorGridMm(unitSystem: UnitSystem): Float = when (unitSystem) {
    UnitSystem.METRIC -> 1000f
    UnitSystem.IMPERIAL -> 1219.2f // 4 feet
  }

  fun minorGridMm(unitSystem: UnitSystem): Float = when (unitSystem) {
    UnitSystem.METRIC -> 250f
    UnitSystem.IMPERIAL -> 304.8f // 1 foot
  }

  fun snapPointToGrid(point: Vec2, gridStepMm: Float): Vec2 {
    val gx = (point.x / gridStepMm).roundToInt() * gridStepMm
    val gy = (point.y / gridStepMm).roundToInt() * gridStepMm
    return Vec2(gx, gy)
  }

  fun snapDrawPoint(
    rawPoint: Vec2,
    anchor: Vec2?,
    walls: List<Wall>,
    gridSnap: Boolean,
    angleSnap: Boolean,
    unitSystem: UnitSystem,
    endpointThresholdMm: Float,
    excludeWallId: String? = null,
  ): SnapResult {
    // 1. Automatic wall-end, midpoint, and T-junction snapping
    var bestPt: Vec2? = null
    var bestKind = SnapKind.NONE
    var bestDist = endpointThresholdMm

    for (w in walls) {
      if (w.id == excludeWallId) continue
      val dStart = distance(rawPoint, w.start)
      if (dStart < bestDist) {
        bestDist = dStart
        bestPt = w.start
        bestKind = SnapKind.ENDPOINT
      }
      val dEnd = distance(rawPoint, w.end)
      if (dEnd < bestDist) {
        bestDist = dEnd
        bestPt = w.end
        bestKind = SnapKind.ENDPOINT
      }
      val mid = lerp(w.start, w.end, 0.5f)
      val dMid = distance(rawPoint, mid)
      if (dMid < bestDist * 0.72f) {
        bestDist = dMid
        bestPt = mid
        bestKind = SnapKind.MIDPOINT
      }
      val (t, proj) = projectPointOnSegment(rawPoint, w.start, w.end)
      val dProj = distance(rawPoint, proj)
      if (t in 0.02f..0.98f && dProj < bestDist * 0.75f) {
        bestDist = dProj
        bestPt = proj
        bestKind = SnapKind.T_JUNCTION
      }
    }
    if (bestPt != null) {
      return SnapResult(
        point = bestPt,
        snappedToEndpoint = true,
        snapKind = bestKind,
      )
    }

    var working = rawPoint
    var snappedAngleDeg: Float? = null
    var alignRef: Vec2? = null

    // 2. Angle snap (0, 45, 90, 135, 180, -45, -90, -135) relative to anchor
    if (angleSnap && anchor != null) {
      val dx = working.x - anchor.x
      val dy = working.y - anchor.y
      val len = hypot(dx, dy)
      if (len > 40f) {
        val rawAngleRad = atan2(dy, dx)
        val rawDeg = (rawAngleRad * 180.0 / PI).toFloat()
        val snapStep = 45f
        val nearestDeg = (rawDeg / snapStep).roundToInt() * snapStep
        if (abs(rawDeg - nearestDeg) <= 14f) {
          val rad = nearestDeg * PI / 180.0
          val snappedLen = if (gridSnap) {
            val step = gridIncrementMm(unitSystem)
            max(step, (len / step).roundToInt() * step)
          } else {
            len
          }
          working = Vec2(
            anchor.x + (cos(rad) * snappedLen).toFloat(),
            anchor.y + (sin(rad) * snappedLen).toFloat(),
          )
          snappedAngleDeg = ((nearestDeg % 360f) + 360f) % 360f

          // Check orthogonal alignment to existing wall endpoints along the snapped axis
          val alignTol = endpointThresholdMm * 0.55f
          for (w in walls) {
            if (w.id == excludeWallId) continue
            for (ref in listOf(w.start, w.end)) {
              if (abs(sin(rad)) < 0.1f && abs(working.x - ref.x) < alignTol) {
                working = Vec2(ref.x, working.y)
                alignRef = ref
              } else if (abs(cos(rad)) < 0.1f && abs(working.y - ref.y) < alignTol) {
                working = Vec2(working.x, ref.y)
                alignRef = ref
              }
            }
          }

          return SnapResult(
            point = working,
            snappedToEndpoint = false,
            snapKind = if (alignRef != null) SnapKind.ALIGNMENT else SnapKind.NONE,
            angleGuideDeg = snappedAngleDeg,
            alignmentRef = alignRef,
          )
        }
      }
    }

    // 3. Grid snap
    if (gridSnap) {
      working = snapPointToGrid(working, gridIncrementMm(unitSystem))
      return SnapResult(point = working, snappedToEndpoint = false, snapKind = SnapKind.GRID)
    }

    return SnapResult(point = working, snappedToEndpoint = false, snapKind = SnapKind.NONE)
  }

  fun findNearestWall(
    point: Vec2,
    walls: List<Wall>,
    maxDistanceMm: Float,
  ): WallProjection? {
    var best: WallProjection? = null
    var bestDist = maxDistanceMm
    for (wall in walls) {
      val (t, closest) = projectPointOnSegment(point, wall.start, wall.end)
      val d = distance(point, closest)
      if (d <= bestDist + wall.thicknessMm * 0.5f) {
        if (d < bestDist) {
          bestDist = d
          best = WallProjection(wall, t, closest, d)
        }
      }
    }
    return best
  }

  fun clampOpeningT(wall: Wall, rawT: Float, widthMm: Float): Float {
    val len = wallLength(wall)
    if (len <= widthMm + 80f) return 0.5f
    val margin = (widthMm * 0.5f + 60f) / len
    return rawT.coerceIn(margin, 1f - margin)
  }

  fun computeWallSolidSegments(
    wall: Wall,
    doors: List<DoorOpening>,
    windows: List<WindowOpening>,
  ): List<Pair<Float, Float>> {
    val len = wallLength(wall)
    if (len < 1f) return emptyList()
    val cuts = mutableListOf<OpeningCut>()
    doors.filter { it.wallId == wall.id }.forEach { d ->
      val half = (d.widthMm * 0.5f) / len
      cuts += OpeningCut((d.t - half).coerceIn(0f, 1f), (d.t + half).coerceIn(0f, 1f))
    }
    windows.filter { it.wallId == wall.id }.forEach { w ->
      val half = (w.widthMm * 0.5f) / len
      cuts += OpeningCut((w.t - half).coerceIn(0f, 1f), (w.t + half).coerceIn(0f, 1f))
    }
    if (cuts.isEmpty()) return listOf(0f to 1f)

    val sorted = cuts.sortedBy { it.tStart }
    val merged = mutableListOf<OpeningCut>()
    for (c in sorted) {
      if (merged.isEmpty() || c.tStart > merged.last().tEnd) {
        merged += c
      } else {
        val last = merged.removeAt(merged.lastIndex)
        merged += OpeningCut(last.tStart, max(last.tEnd, c.tEnd))
      }
    }

    val segments = mutableListOf<Pair<Float, Float>>()
    var cursor = 0f
    for (c in merged) {
      if (c.tStart > cursor + 0.001f) {
        segments += cursor to c.tStart
      }
      cursor = max(cursor, c.tEnd)
    }
    if (cursor < 0.999f) {
      segments += cursor to 1f
    }
    return segments
  }

  fun symbolAABB(symbol: PlacedSymbol): Bounds2D {
    val rad = symbol.rotationDeg * PI / 180.0
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val halfW = (symbol.widthMm * c + symbol.depthMm * s) * 0.5f
    val halfH = (symbol.widthMm * s + symbol.depthMm * c) * 0.5f
    return Bounds2D(
      minX = symbol.center.x - halfW,
      minY = symbol.center.y - halfH,
      maxX = symbol.center.x + halfW,
      maxY = symbol.center.y + halfH,
    )
  }

  fun isPointInSymbol(point: Vec2, symbol: PlacedSymbol, toleranceMm: Float): Boolean {
    val dx = point.x - symbol.center.x
    val dy = point.y - symbol.center.y
    val rad = -symbol.rotationDeg * PI / 180.0
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val lx = dx * c - dy * s
    val ly = dx * s + dy * c
    return abs(lx) <= symbol.widthMm * 0.5f + toleranceMm &&
      abs(ly) <= symbol.depthMm * 0.5f + toleranceMm
  }

  fun symbolRotateHandlePos(symbol: PlacedSymbol, handleOffsetMm: Float): Vec2 {
    val rad = symbol.rotationDeg * PI / 180.0
    val nx = sin(rad).toFloat()
    val ny = -cos(rad).toFloat()
    val dist = symbol.depthMm * 0.5f + handleOffsetMm
    return Vec2(symbol.center.x + nx * dist, symbol.center.y + ny * dist)
  }

  fun dimensionLineEndpoints(dim: LinearDimension): Pair<Vec2, Vec2> {
    val dir = normalize(sub(dim.end, dim.start))
    val normal = Vec2(-dir.y, dir.x)
    val offsetVec = mul(normal, dim.offsetMm)
    return add(dim.start, offsetVec) to add(dim.end, offsetVec)
  }

  fun hitTest(
    worldPoint: Vec2,
    plan: FloorPlanData,
    hitToleranceMm: Float,
  ): SelectionTarget? {
    // 1. Doors & Windows (highest priority because they sit inside walls)
    for (door in plan.doors.asReversed()) {
      val wall = plan.walls.find { it.id == door.wallId } ?: continue
      val center = lerp(wall.start, wall.end, door.t)
      val hitRadius = max(door.widthMm * 0.55f, hitToleranceMm + wall.thicknessMm * 0.5f)
      if (distance(worldPoint, center) <= hitRadius) {
        return SelectionTarget.SelectedDoor(door.id)
      }
    }
    for (win in plan.windows.asReversed()) {
      val wall = plan.walls.find { it.id == win.wallId } ?: continue
      val center = lerp(wall.start, wall.end, win.t)
      val (_, proj) = projectPointOnSegment(
        worldPoint,
        lerp(wall.start, wall.end, (win.t - (win.widthMm * 0.5f) / max(1f, wallLength(wall))).coerceIn(0f, 1f)),
        lerp(wall.start, wall.end, (win.t + (win.widthMm * 0.5f) / max(1f, wallLength(wall))).coerceIn(0f, 1f)),
      )
      if (distance(worldPoint, proj) <= hitToleranceMm + wall.thicknessMm * 0.55f ||
        distance(worldPoint, center) <= hitToleranceMm
      ) {
        return SelectionTarget.SelectedWindow(win.id)
      }
    }

    // 2. Symbols
    for (sym in plan.symbols.asReversed()) {
      if (isPointInSymbol(worldPoint, sym, hitToleranceMm * 0.5f)) {
        return SelectionTarget.SelectedSymbol(sym.id)
      }
    }

    // 2b. Text labels
    for (txt in plan.texts.asReversed()) {
      if (isPointInTextLabel(worldPoint, txt, hitToleranceMm)) {
        return SelectionTarget.SelectedText(txt.id)
      }
    }

    // 3. Linear dimensions
    for (dim in plan.dimensions.asReversed()) {
      val (p1, p2) = dimensionLineEndpoints(dim)
      if (pointToSegmentDistance(worldPoint, p1, p2) <= hitToleranceMm) {
        return SelectionTarget.SelectedDimension(dim.id)
      }
    }

    // 4. Walls
    for (wall in plan.walls.asReversed()) {
      val dist = pointToSegmentDistance(worldPoint, wall.start, wall.end)
      if (dist <= wall.thicknessMm * 0.5f + hitToleranceMm) {
        return SelectionTarget.SelectedWall(wall.id)
      }
    }

    return null
  }

  fun textLabelSizeMm(txt: TextLabel): Pair<Float, Float> {
    val approxW = max(txt.fontSizeMm * 2.2f, txt.text.length * txt.fontSizeMm * 0.62f)
    val approxH = txt.fontSizeMm * 1.3f
    return approxW to approxH
  }

  fun isPointInTextLabel(point: Vec2, txt: TextLabel, toleranceMm: Float): Boolean {
    val (w, h) = textLabelSizeMm(txt)
    val dx = point.x - txt.position.x
    val dy = point.y - txt.position.y
    val rad = -txt.rotationDeg * PI / 180.0
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val lx = dx * c - dy * s
    val ly = dx * s + dy * c
    return abs(lx) <= w * 0.5f + toleranceMm && abs(ly) <= h * 0.5f + toleranceMm
  }

  fun computePlanBounds(plan: FloorPlanData): Bounds2D? {
    if (plan.walls.isEmpty() && plan.symbols.isEmpty() && plan.dimensions.isEmpty() && plan.texts.isEmpty()) return null
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    fun include(p: Vec2, pad: Float = 0f) {
      minX = min(minX, p.x - pad)
      minY = min(minY, p.y - pad)
      maxX = max(maxX, p.x + pad)
      maxY = max(maxY, p.y + pad)
    }

    for (w in plan.walls) {
      val pad = w.thicknessMm * 0.5f
      include(w.start, pad)
      include(w.end, pad)
    }
    for (s in plan.symbols) {
      val b = symbolAABB(s)
      minX = min(minX, b.minX)
      minY = min(minY, b.minY)
      maxX = max(maxX, b.maxX)
      maxY = max(maxY, b.maxY)
    }
    for (t in plan.texts) {
      val (tw, th) = textLabelSizeMm(t)
      include(t.position, max(tw, th) * 0.5f)
    }
    for (d in plan.dimensions) {
      val (p1, p2) = dimensionLineEndpoints(d)
      include(d.start)
      include(d.end)
      include(p1)
      include(p2)
    }
    if (!minX.isFinite()) return null
    return Bounds2D(minX, minY, maxX, maxY)
  }

  /**
   * Generates clean exterior dimension chains along the perimeter showing
   * inside-to-inside ("in-to-in") clear widths of adjacent wall-bounded spaces.
   */
  fun generateExteriorInToInDimensions(walls: List<Wall>): List<LinearDimension> {
    if (walls.size < 2) return emptyList()
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (w in walls) {
      minX = min(minX, min(w.start.x, w.end.x))
      maxX = max(maxX, max(w.start.x, w.end.x))
      minY = min(minY, min(w.start.y, w.end.y))
      maxY = max(maxY, max(w.start.y, w.end.y))
    }
    if (maxX - minX < 200f || maxY - minY < 200f) return emptyList()

    val vertWalls = walls.filter { abs(wallDir(it).x) < 0.2f }
      .map { Triple((it.start.x + it.end.x) * 0.5f, it, min(it.start.y, it.end.y) to max(it.start.y, it.end.y)) }
      .sortedBy { it.first }

    val horizWalls = walls.filter { abs(wallDir(it).y) < 0.2f }
      .map { Triple((it.start.y + it.end.y) * 0.5f, it, min(it.start.x, it.end.x) to max(it.start.x, it.end.x)) }
      .sortedBy { it.first }

    val topWallThickness = horizWalls.firstOrNull { abs(it.first - minY) < 250f }?.second?.thicknessMm ?: 0f
    val bottomWallThickness = horizWalls.lastOrNull { abs(it.first - maxY) < 250f }?.second?.thicknessMm ?: 0f
    val leftWallThickness = vertWalls.firstOrNull { abs(it.first - minX) < 250f }?.second?.thicknessMm ?: 0f
    val rightWallThickness = vertWalls.lastOrNull { abs(it.first - maxX) < 250f }?.second?.thicknessMm ?: 0f

    val result = mutableListOf<LinearDimension>()
    val edgeTol = 350f
    val dimOffset = -640f

    // Top exterior chain (left -> right)
    val topVerts = vertWalls.filter { it.third.first <= minY + edgeTol }
    if (topVerts.size >= 2) {
      val anchorY = minY + topWallThickness * 0.5f
      for (i in 0 until topVerts.lastIndex) {
        val (x1, w1, _) = topVerts[i]
        val (x2, w2, _) = topVerts[i + 1]
        val inStart = x1 + w1.thicknessMm * 0.5f
        val inEnd = x2 - w2.thicknessMm * 0.5f
        if (inEnd - inStart > 150f) {
          result += LinearDimension(start = Vec2(inStart, anchorY), end = Vec2(inEnd, anchorY), offsetMm = dimOffset)
        }
      }
    }

    // Right exterior chain (top -> bottom)
    val rightHoriz = horizWalls.filter { it.third.second >= maxX - edgeTol }
    if (rightHoriz.size >= 2) {
      val anchorX = maxX - rightWallThickness * 0.5f
      for (i in 0 until rightHoriz.lastIndex) {
        val (y1, w1, _) = rightHoriz[i]
        val (y2, w2, _) = rightHoriz[i + 1]
        val inStart = y1 + w1.thicknessMm * 0.5f
        val inEnd = y2 - w2.thicknessMm * 0.5f
        if (inEnd - inStart > 150f) {
          result += LinearDimension(start = Vec2(anchorX, inStart), end = Vec2(anchorX, inEnd), offsetMm = dimOffset)
        }
      }
    }

    // Bottom exterior chain (right -> left)
    val bottomVerts = vertWalls.filter { it.third.second >= maxY - edgeTol }.reversed()
    if (bottomVerts.size >= 2) {
      val anchorY = maxY - bottomWallThickness * 0.5f
      for (i in 0 until bottomVerts.lastIndex) {
        val (x1, w1, _) = bottomVerts[i]
        val (x2, w2, _) = bottomVerts[i + 1]
        val inStart = x1 - w1.thicknessMm * 0.5f
        val inEnd = x2 + w2.thicknessMm * 0.5f
        if (inStart - inEnd > 150f) {
          result += LinearDimension(start = Vec2(inStart, anchorY), end = Vec2(inEnd, anchorY), offsetMm = dimOffset)
        }
      }
    }

    // Left exterior chain (bottom -> top)
    val leftHoriz = horizWalls.filter { it.third.first <= minX + edgeTol }.reversed()
    if (leftHoriz.size >= 2) {
      val anchorX = minX + leftWallThickness * 0.5f
      for (i in 0 until leftHoriz.lastIndex) {
        val (y1, w1, _) = leftHoriz[i]
        val (y2, w2, _) = leftHoriz[i + 1]
        val inStart = y1 - w1.thicknessMm * 0.5f
        val inEnd = y2 + w2.thicknessMm * 0.5f
        if (inStart - inEnd > 150f) {
          result += LinearDimension(start = Vec2(anchorX, inStart), end = Vec2(anchorX, inEnd), offsetMm = dimOffset)
        }
      }
    }

    return result
  }

  fun snapWallEndpointJoin(wall: Wall, otherWalls: List<Wall>, toleranceMm: Float = 110f): Wall {
    fun snapPt(pt: Vec2): Vec2 {
      var best = pt
      var bestDist = toleranceMm
      for (other in otherWalls) {
        if (other.id == wall.id) continue
        val ds = distance(pt, other.start)
        if (ds < bestDist) {
          bestDist = ds
          best = other.start
        }
        val de = distance(pt, other.end)
        if (de < bestDist) {
          bestDist = de
          best = other.end
        }
        val (_, proj) = projectPointOnSegment(pt, other.start, other.end)
        val dp = distance(pt, proj)
        if (dp < bestDist * 0.75f) {
          bestDist = dp
          best = proj
        }
      }
      return best
    }
    return wall.copy(start = snapPt(wall.start), end = snapPt(wall.end))
  }

  /**
   * Computes crisp mitered / squared polygon corner points for each wall, extending
   * endpoints cleanly to meet connected walls at corners and T-junctions.
   */
  fun computeWallPolygon(wall: Wall, allWalls: List<Wall>): List<Vec2> {
    val len = wallLength(wall)
    if (len < 1f) return emptyList()
    val dir = wallDir(wall)
    val normal = wallNormal(wall)
    val halfT = wall.thicknessMm * 0.5f

    var startExt = 0f
    var endExt = 0f

    for (other in allWalls) {
      if (other.id == wall.id) continue
      val otherHalf = other.thicknessMm * 0.5f
      val otherDir = wallDir(other)
      val cross = abs(dir.x * otherDir.y - dir.y * otherDir.x)
      if (cross > 0.2f) {
        if (distance(wall.start, other.start) < 25f || distance(wall.start, other.end) < 25f) {
          startExt = max(startExt, otherHalf)
        }
        if (distance(wall.end, other.start) < 25f || distance(wall.end, other.end) < 25f) {
          endExt = max(endExt, otherHalf)
        }
      }
    }

    val pStart = sub(wall.start, mul(dir, startExt))
    val pEnd = add(wall.end, mul(dir, endExt))
    val nVec = mul(normal, halfT)

    return listOf(
      add(pStart, nVec),
      add(pEnd, nVec),
      sub(pEnd, nVec),
      sub(pStart, nVec),
    )
  }

  fun pointInTriangleOrQuad(p: Vec2, quad: List<Vec2>): Boolean {
    if (quad.size < 3) return false
    var pos = 0
    var neg = 0
    for (i in quad.indices) {
      val a = quad[i]
      val b = quad[(i + 1) % quad.size]
      val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
      if (cross > 0f) pos++ else if (cross < 0f) neg++
      if (pos > 0 && neg > 0) return false
    }
    return true
  }

  fun clampAngleSnap90(deg: Float): Float {
    val norm = ((deg % 360f) + 360f) % 360f
    val nearest = (norm / 90f).roundToInt() * 90f
    return if (abs(norm - nearest) <= 6f) nearest % 360f else norm
  }

  fun signedAngleDiffDeg(a: Vec2, b: Vec2): Float {
    val cross = a.x * b.y - a.y * b.x
    val dot = a.x * b.x + a.y * b.y
    return (atan2(cross, dot) * 180.0 / PI).toFloat()
  }

  fun clampPositive(v: Float, minVal: Float = 50f): Float = if (v.isNaN()) minVal else max(minVal, v)

  fun sqrtSafe(v: Float): Float = sqrt(max(0f, v))
}
