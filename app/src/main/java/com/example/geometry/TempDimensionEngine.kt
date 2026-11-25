package com.example.geometry

import com.example.model.FloorPlanData
import com.example.model.PlacedSymbol
import com.example.model.SelectionTarget
import com.example.model.TempDimAction
import com.example.model.TempDimension
import com.example.model.Vec2
import com.example.model.Wall
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TempDimensionEngine {

  fun computeForSelection(
    plan: FloorPlanData,
    selection: SelectionTarget?,
  ): List<TempDimension> {
    if (selection == null) return emptyList()
    return when (selection) {
      is SelectionTarget.SelectedWall -> computeForWall(plan, selection.id)
      is SelectionTarget.SelectedDoor -> computeForOpening(plan, selection.id, isDoor = true)
      is SelectionTarget.SelectedWindow -> computeForOpening(plan, selection.id, isDoor = false)
      is SelectionTarget.SelectedSymbol -> computeForSymbol(plan, selection.id)
      is SelectionTarget.SelectedDimension, is SelectionTarget.SelectedText -> emptyList()
    }
  }

  private fun computeForWall(plan: FloorPlanData, wallId: String): List<TempDimension> {
    val wall = plan.walls.find { it.id == wallId } ?: return emptyList()
    val len = GeometryMath.wallLength(wall)
    if (len < 10f) return emptyList()

    val dir = GeometryMath.wallDir(wall)
    val normal = GeometryMath.wallNormal(wall)
    val result = mutableListOf<TempDimension>()

    // 1. Wall's own length dimension offset beside the wall
    val offset = GeometryMath.mul(normal, -(wall.thicknessMm * 0.5f + 420f))
    val lenStart = GeometryMath.add(wall.start, offset)
    val lenEnd = GeometryMath.add(wall.end, offset)
    result += TempDimension(
      id = "td_wall_len_${wall.id}",
      start = lenStart,
      end = lenEnd,
      labelCenter = GeometryMath.lerp(lenStart, lenEnd, 0.5f),
      distanceMm = len,
      action = TempDimAction.ResizeWallLength(wallId = wall.id, anchorStart = true),
    )

    // 2. Nearest parallel walls on +normal and -normal sides
    val wMid = GeometryMath.lerp(wall.start, wall.end, 0.5f)
    val wProjStart = GeometryMath.dot(wall.start, dir)
    val wProjEnd = GeometryMath.dot(wall.end, dir)
    val wProjMin = min(wProjStart, wProjEnd)
    val wProjMax = max(wProjStart, wProjEnd)

    var bestPosWall: Pair<Wall, Float>? = null
    var bestNegWall: Pair<Wall, Float>? = null

    for (other in plan.walls) {
      if (other.id == wall.id) continue
      val oDir = GeometryMath.wallDir(other)
      val parallelDot = abs(GeometryMath.dot(dir, oDir))
      if (parallelDot < 0.985f) continue

      val oProjA = GeometryMath.dot(other.start, dir)
      val oProjB = GeometryMath.dot(other.end, dir)
      val oProjMin = min(oProjA, oProjB)
      val oProjMax = max(oProjA, oProjB)

      // Check projection overlap (allow slight gap tolerance for room spans)
      val overlap = min(wProjMax, oProjMax) - max(wProjMin, oProjMin)
      if (overlap < 200f) continue

      val signedDistCenter = GeometryMath.dot(GeometryMath.sub(other.start, wall.start), normal)
      val centerDist = abs(signedDistCenter)
      val clearDist = centerDist - (wall.thicknessMm + other.thicknessMm) * 0.5f
      if (clearDist < 120f) continue

      if (signedDistCenter > 0f) {
        if (bestPosWall == null || clearDist < bestPosWall.second) {
          bestPosWall = other to clearDist
        }
      } else {
        if (bestNegWall == null || clearDist < bestNegWall.second) {
          bestNegWall = other to clearDist
        }
      }
    }

    fun addParallelDim(other: Wall, clearDist: Float, sign: Float, suffix: String) {
      val oProjA = GeometryMath.dot(other.start, dir)
      val oProjB = GeometryMath.dot(other.end, dir)
      val overlapMin = max(wProjMin, min(oProjA, oProjB))
      val overlapMax = min(wProjMax, max(oProjA, oProjB))
      val midProj = (overlapMin + overlapMax) * 0.5f
      val anchorOnWall = GeometryMath.add(
        wMid,
        GeometryMath.mul(dir, midProj - GeometryMath.dot(wMid, dir)),
      )
      val p1 = GeometryMath.add(anchorOnWall, GeometryMath.mul(normal, sign * wall.thicknessMm * 0.5f))
      val p2 = GeometryMath.add(p1, GeometryMath.mul(normal, sign * clearDist))
      result += TempDimension(
        id = "td_wall_par_${wall.id}_$suffix",
        start = p1,
        end = p2,
        labelCenter = GeometryMath.lerp(p1, p2, 0.5f),
        distanceMm = clearDist,
        action = TempDimAction.OffsetWallPerpendicular(
          wallId = wall.id,
          referenceWallId = other.id,
          moveNormal = GeometryMath.mul(normal, -sign),
        ),
      )
    }

    bestPosWall?.let { (other, dist) -> addParallelDim(other, dist, 1f, "pos") }
    bestNegWall?.let { (other, dist) -> addParallelDim(other, dist, -1f, "neg") }

    return result
  }

  private fun computeForOpening(
    plan: FloorPlanData,
    openingId: String,
    isDoor: Boolean,
  ): List<TempDimension> {
    val wallId: String
    val t: Float
    val widthMm: Float
    if (isDoor) {
      val d = plan.doors.find { it.id == openingId } ?: return emptyList()
      wallId = d.wallId
      t = d.t
      widthMm = d.widthMm
    } else {
      val w = plan.windows.find { it.id == openingId } ?: return emptyList()
      wallId = w.wallId
      t = w.t
      widthMm = w.widthMm
    }

    val wall = plan.walls.find { it.id == wallId } ?: return emptyList()
    val len = GeometryMath.wallLength(wall)
    if (len < 50f) return emptyList()

    val dir = GeometryMath.wallDir(wall)
    val normal = GeometryMath.wallNormal(wall)
    val offset = GeometryMath.mul(normal, wall.thicknessMm * 0.5f + 360f)

    val centerDist = t * len
    val halfW = widthMm * 0.5f
    val leftEdgeDist = max(0f, centerDist - halfW)
    val rightEdgeDist = max(0f, len - (centerDist + halfW))

    val startOuter = GeometryMath.add(wall.start, offset)
    val nearLeft = GeometryMath.add(
      GeometryMath.add(wall.start, GeometryMath.mul(dir, leftEdgeDist)),
      offset,
    )
    val nearRight = GeometryMath.add(
      GeometryMath.add(wall.start, GeometryMath.mul(dir, centerDist + halfW)),
      offset,
    )
    val endOuter = GeometryMath.add(wall.end, offset)

    return listOf(
      TempDimension(
        id = "td_open_start_$openingId",
        start = startOuter,
        end = nearLeft,
        labelCenter = GeometryMath.lerp(startOuter, nearLeft, 0.5f),
        distanceMm = leftEdgeDist,
        action = TempDimAction.OffsetOpeningAlongWall(
          openingId = openingId,
          isDoor = isDoor,
          fromStart = true,
        ),
      ),
      TempDimension(
        id = "td_open_end_$openingId",
        start = nearRight,
        end = endOuter,
        labelCenter = GeometryMath.lerp(nearRight, endOuter, 0.5f),
        distanceMm = rightEdgeDist,
        action = TempDimAction.OffsetOpeningAlongWall(
          openingId = openingId,
          isDoor = isDoor,
          fromStart = false,
        ),
      ),
    )
  }

  private fun computeForSymbol(plan: FloorPlanData, symbolId: String): List<TempDimension> {
    val sym = plan.symbols.find { it.id == symbolId } ?: return emptyList()
    val bounds = GeometryMath.symbolAABB(sym)
    val cx = sym.center.x
    val cy = sym.center.y

    // Find nearest wall left/right (vertical-ish walls spanning cy) and top/bottom (horizontal-ish walls spanning cx)
    var bestLeft: Pair<Float, Float>? = null // (gapMm, wallFaceX)
    var bestRight: Pair<Float, Float>? = null
    var bestTop: Pair<Float, Float>? = null
    var bestBottom: Pair<Float, Float>? = null

    for (w in plan.walls) {
      val dir = GeometryMath.wallDir(w)
      // Near-vertical wall
      if (abs(dir.x) < 0.25f) {
        val minY = min(w.start.y, w.end.y)
        val maxY = max(w.start.y, w.end.y)
        if (cy in (minY - 400f)..(maxY + 400f)) {
          val wx = (w.start.x + w.end.x) * 0.5f
          val halfT = w.thicknessMm * 0.5f
          if (wx < cx) {
            val faceX = wx + halfT
            val gap = bounds.minX - faceX
            if (gap >= -5f && (bestLeft == null || gap < bestLeft.first)) {
              bestLeft = max(0f, gap) to faceX
            }
          } else {
            val faceX = wx - halfT
            val gap = faceX - bounds.maxX
            if (gap >= -5f && (bestRight == null || gap < bestRight.first)) {
              bestRight = max(0f, gap) to faceX
            }
          }
        }
      }
      // Near-horizontal wall
      if (abs(dir.y) < 0.25f) {
        val minX = min(w.start.x, w.end.x)
        val maxX = max(w.start.x, w.end.x)
        if (cx in (minX - 400f)..(maxX + 400f)) {
          val wy = (w.start.y + w.end.y) * 0.5f
          val halfT = w.thicknessMm * 0.5f
          if (wy < cy) {
            val faceY = wy + halfT
            val gap = bounds.minY - faceY
            if (gap >= -5f && (bestTop == null || gap < bestTop.first)) {
              bestTop = max(0f, gap) to faceY
            }
          } else {
            val faceY = wy - halfT
            val gap = faceY - bounds.maxY
            if (gap >= -5f && (bestBottom == null || gap < bestBottom.first)) {
              bestBottom = max(0f, gap) to faceY
            }
          }
        }
      }
    }

    val result = mutableListOf<TempDimension>()

    // Pick closest horizontal wall reference (prefer left if tied/valid)
    val horiz = when {
      bestLeft != null && bestRight != null ->
        if (bestLeft.first <= bestRight.first) true to bestLeft else false to bestRight
      bestLeft != null -> true to bestLeft
      bestRight != null -> false to bestRight
      else -> null
    }
    if (horiz != null) {
      val (isLeft, pair) = horiz
      val (gap, faceX) = pair
      val start = if (isLeft) Vec2(faceX, cy) else Vec2(bounds.maxX, cy)
      val end = if (isLeft) Vec2(bounds.minX, cy) else Vec2(faceX, cy)
      val moveDir = if (isLeft) Vec2(1f, 0f) else Vec2(-1f, 0f)
      result += TempDimension(
        id = "td_sym_x_$symbolId",
        start = start,
        end = end,
        labelCenter = GeometryMath.lerp(start, end, 0.5f),
        distanceMm = gap,
        action = TempDimAction.MoveSymbolToWallGap(
          symbolId = symbolId,
          moveDirection = moveDir,
          anchorEdgeWorld = Vec2(faceX, cy),
        ),
      )
    }

    // Pick closest vertical wall reference
    val vert = when {
      bestTop != null && bestBottom != null ->
        if (bestTop.first <= bestBottom.first) true to bestTop else false to bestBottom
      bestTop != null -> true to bestTop
      bestBottom != null -> false to bestBottom
      else -> null
    }
    if (vert != null) {
      val (isTop, pair) = vert
      val (gap, faceY) = pair
      val start = if (isTop) Vec2(cx, faceY) else Vec2(cx, bounds.maxY)
      val end = if (isTop) Vec2(cx, bounds.minY) else Vec2(cx, faceY)
      val moveDir = if (isTop) Vec2(0f, 1f) else Vec2(0f, -1f)
      result += TempDimension(
        id = "td_sym_y_$symbolId",
        start = start,
        end = end,
        labelCenter = GeometryMath.lerp(start, end, 0.5f),
        distanceMm = gap,
        action = TempDimAction.MoveSymbolToWallGap(
          symbolId = symbolId,
          moveDirection = moveDir,
          anchorEdgeWorld = Vec2(cx, faceY),
        ),
      )
    }

    return result
  }

  fun applyTempDimensionEdit(
    plan: FloorPlanData,
    dim: TempDimension,
    newDistanceMm: Float,
  ): FloorPlanData {
    val targetMm = max(0f, newDistanceMm)
    return when (val action = dim.action) {
      is TempDimAction.ResizeWallLength -> {
        val safeLen = max(100f, targetMm)
        val updatedWalls = plan.walls.map { w ->
          if (w.id != action.wallId) w
          else {
            val dir = GeometryMath.wallDir(w)
            if (action.anchorStart) {
              w.copy(end = GeometryMath.add(w.start, GeometryMath.mul(dir, safeLen)))
            } else {
              w.copy(start = GeometryMath.sub(w.end, GeometryMath.mul(dir, safeLen)))
            }
          }
        }
        plan.copy(walls = updatedWalls)
      }

      is TempDimAction.OffsetWallPerpendicular -> {
        val delta = targetMm - dim.distanceMm
        val shift = GeometryMath.mul(action.moveNormal, delta)
        val updatedWalls = plan.walls.map { w ->
          if (w.id != action.wallId) w
          else w.copy(
            start = GeometryMath.add(w.start, shift),
            end = GeometryMath.add(w.end, shift),
          )
        }
        plan.copy(walls = updatedWalls)
      }

      is TempDimAction.OffsetOpeningAlongWall -> {
        if (action.isDoor) {
          val door = plan.doors.find { it.id == action.openingId } ?: return plan
          val wall = plan.walls.find { it.id == door.wallId } ?: return plan
          val len = GeometryMath.wallLength(wall)
          if (len <= door.widthMm) return plan
          val centerDist = if (action.fromStart) {
            targetMm + door.widthMm * 0.5f
          } else {
            len - targetMm - door.widthMm * 0.5f
          }
          val newT = GeometryMath.clampOpeningT(wall, centerDist / len, door.widthMm)
          plan.copy(doors = plan.doors.map { if (it.id == door.id) it.copy(t = newT) else it })
        } else {
          val win = plan.windows.find { it.id == action.openingId } ?: return plan
          val wall = plan.walls.find { it.id == win.wallId } ?: return plan
          val len = GeometryMath.wallLength(wall)
          if (len <= win.widthMm) return plan
          val centerDist = if (action.fromStart) {
            targetMm + win.widthMm * 0.5f
          } else {
            len - targetMm - win.widthMm * 0.5f
          }
          val newT = GeometryMath.clampOpeningT(wall, centerDist / len, win.widthMm)
          plan.copy(windows = plan.windows.map { if (it.id == win.id) it.copy(t = newT) else it })
        }
      }

      is TempDimAction.MoveSymbolToWallGap -> {
        val sym = plan.symbols.find { it.id == action.symbolId } ?: return plan
        val bounds = GeometryMath.symbolAABB(sym)
        val newCenter = if (abs(action.moveDirection.x) > 0.5f) {
          val halfW = bounds.width * 0.5f
          val cx = action.anchorEdgeWorld.x + action.moveDirection.x * (targetMm + halfW)
          Vec2(cx, sym.center.y)
        } else {
          val halfH = bounds.height * 0.5f
          val cy = action.anchorEdgeWorld.y + action.moveDirection.y * (targetMm + halfH)
          Vec2(sym.center.x, cy)
        }
        plan.copy(symbols = plan.symbols.map { if (it.id == sym.id) it.copy(center = newCenter) else it })
      }
    }
  }
}
