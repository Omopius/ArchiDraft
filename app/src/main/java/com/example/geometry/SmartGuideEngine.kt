package com.example.geometry

import com.example.model.FloorPlanData
import com.example.model.PlacedSymbol
import com.example.model.Vec2
import com.example.model.Wall
import kotlin.math.abs

enum class GuideAxis {
  HORIZONTAL,
  VERTICAL,
}

data class SmartGuide(
  val axis: GuideAxis,
  val start: Vec2,
  val end: Vec2,
  val targetPoint: Vec2,
  val referencePoint: Vec2,
  val label: String = "",
)

data class SmartGuideResult(
  val snappedPoint: Vec2,
  val guides: List<SmartGuide>,
)

private data class AxisTarget(
  val coord: Float,
  val anchorPoint: Vec2,
  val label: String,
)

object SmartGuideEngine {

  /**
   * Aligns a moving furniture/fixture symbol's center and bounding edges to existing
   * symbols, wall endpoints/centerlines, room centers, and text labels.
   */
  fun alignSymbolMove(
    rawCenter: Vec2,
    movingSymbol: PlacedSymbol,
    plan: FloorPlanData,
    rooms: List<DetectedRoom>,
    snapToleranceMm: Float,
  ): SmartGuideResult {
    val simSymbol = movingSymbol.copy(center = rawCenter)
    val movingBox = GeometryMath.symbolAABB(simSymbol)
    val halfW = (movingBox.maxX - movingBox.minX) * 0.5f
    val halfH = (movingBox.maxY - movingBox.minY) * 0.5f

    val xTargets = ArrayList<AxisTarget>()
    val yTargets = ArrayList<AxisTarget>()
    collectCommonTargets(plan, rooms, excludeSymbolId = movingSymbol.id, excludeWallId = null, excludeTextId = null, xTargets, yTargets)

    // Also include bounding-box edges of other symbols so furniture aligns edge-to-edge cleanly
    for (other in plan.symbols) {
      if (other.id == movingSymbol.id) continue
      val b = GeometryMath.symbolAABB(other)
      xTargets += AxisTarget(b.minX, Vec2(b.minX, other.center.y), "Edge")
      xTargets += AxisTarget(b.maxX, Vec2(b.maxX, other.center.y), "Edge")
      yTargets += AxisTarget(b.minY, Vec2(other.center.x, b.minY), "Edge")
      yTargets += AxisTarget(b.maxY, Vec2(other.center.x, b.maxY), "Edge")
    }

    // Candidate X offsets on the moving symbol: center, left edge, right edge
    val xCandidates = listOf(
      Triple(0f, "Center", rawCenter.y),
      Triple(-halfW, "Edge", rawCenter.y),
      Triple(halfW, "Edge", rawCenter.y),
    )
    var bestDx = Float.MAX_VALUE
    var bestXTarget: AxisTarget? = null
    var bestXOffset = 0f

    for ((offset, _, _) in xCandidates) {
      val testX = rawCenter.x + offset
      for (t in xTargets) {
        val diff = t.coord - testX
        if (abs(diff) <= snapToleranceMm && abs(diff) < abs(bestDx)) {
          bestDx = diff
          bestXTarget = t
          bestXOffset = offset
        }
      }
    }

    // Candidate Y offsets on the moving symbol: center, top edge, bottom edge
    val yCandidates = listOf(
      Triple(0f, "Center", rawCenter.x),
      Triple(-halfH, "Edge", rawCenter.x),
      Triple(halfH, "Edge", rawCenter.x),
    )
    var bestDy = Float.MAX_VALUE
    var bestYTarget: AxisTarget? = null
    var bestYOffset = 0f

    for ((offset, _, _) in yCandidates) {
      val testY = rawCenter.y + offset
      for (t in yTargets) {
        val diff = t.coord - testY
        if (abs(diff) <= snapToleranceMm && abs(diff) < abs(bestDy)) {
          bestDy = diff
          bestYTarget = t
          bestYOffset = offset
        }
      }
    }

    val finalX = if (bestXTarget != null) rawCenter.x + bestDx else rawCenter.x
    val finalY = if (bestYTarget != null) rawCenter.y + bestDy else rawCenter.y
    val snappedCenter = Vec2(finalX, finalY)

    val guides = mutableListOf<SmartGuide>()
    bestXTarget?.let { t ->
      val targetPt = Vec2(t.coord, finalY)
      guides += SmartGuide(
        axis = GuideAxis.VERTICAL,
        start = t.anchorPoint,
        end = targetPt,
        targetPoint = targetPt,
        referencePoint = t.anchorPoint,
        label = t.label,
      )
    }
    bestYTarget?.let { t ->
      val targetPt = Vec2(finalX, t.coord)
      guides += SmartGuide(
        axis = GuideAxis.HORIZONTAL,
        start = t.anchorPoint,
        end = targetPt,
        targetPoint = targetPt,
        referencePoint = t.anchorPoint,
        label = t.label,
      )
    }

    return SmartGuideResult(snappedCenter, guides)
  }

  /**
   * Aligns a moving text label to room centers, other text labels, symbols, and wall references.
   */
  fun alignTextMove(
    rawPos: Vec2,
    movingTextId: String,
    plan: FloorPlanData,
    rooms: List<DetectedRoom>,
    snapToleranceMm: Float,
  ): SmartGuideResult {
    val xTargets = ArrayList<AxisTarget>()
    val yTargets = ArrayList<AxisTarget>()
    collectCommonTargets(plan, rooms, excludeSymbolId = null, excludeWallId = null, excludeTextId = movingTextId, xTargets, yTargets)
    return alignPointAgainstTargets(rawPos, xTargets, yTargets, snapToleranceMm)
  }

  /**
   * Aligns a whole moving wall segment (by its start, end, or midpoint) against existing walls,
   * symbols, and room centers. Returns the snapped translation delta and dashed guides.
   */
  fun alignWallMoveDelta(
    origWall: Wall,
    rawDelta: Vec2,
    plan: FloorPlanData,
    rooms: List<DetectedRoom>,
    snapToleranceMm: Float,
  ): SmartGuideResult {
    val xTargets = ArrayList<AxisTarget>()
    val yTargets = ArrayList<AxisTarget>()
    collectCommonTargets(plan, rooms, excludeSymbolId = null, excludeWallId = origWall.id, excludeTextId = null, xTargets, yTargets)

    val movedStart = GeometryMath.add(origWall.start, rawDelta)
    val movedEnd = GeometryMath.add(origWall.end, rawDelta)
    val movedMid = GeometryMath.lerp(movedStart, movedEnd, 0.5f)
    val anchors = listOf(movedStart, movedEnd, movedMid)

    var bestDx = Float.MAX_VALUE
    var bestXTarget: AxisTarget? = null
    var bestXAnchor = movedMid

    var bestDy = Float.MAX_VALUE
    var bestYTarget: AxisTarget? = null
    var bestYAnchor = movedMid

    for (a in anchors) {
      for (t in xTargets) {
        val diff = t.coord - a.x
        if (abs(diff) <= snapToleranceMm && abs(diff) < abs(bestDx)) {
          bestDx = diff
          bestXTarget = t
          bestXAnchor = a
        }
      }
      for (t in yTargets) {
        val diff = t.coord - a.y
        if (abs(diff) <= snapToleranceMm && abs(diff) < abs(bestDy)) {
          bestDy = diff
          bestYTarget = t
          bestYAnchor = a
        }
      }
    }

    val snappedDx = if (bestXTarget != null) rawDelta.x + bestDx else rawDelta.x
    val snappedDy = if (bestYTarget != null) rawDelta.y + bestDy else rawDelta.y

    val guides = mutableListOf<SmartGuide>()
    bestXTarget?.let { t ->
      val targetPt = Vec2(t.coord, bestXAnchor.y + (snappedDy - rawDelta.y))
      guides += SmartGuide(
        axis = GuideAxis.VERTICAL,
        start = t.anchorPoint,
        end = targetPt,
        targetPoint = targetPt,
        referencePoint = t.anchorPoint,
        label = t.label,
      )
    }
    bestYTarget?.let { t ->
      val targetPt = Vec2(bestYAnchor.x + (snappedDx - rawDelta.x), t.coord)
      guides += SmartGuide(
        axis = GuideAxis.HORIZONTAL,
        start = t.anchorPoint,
        end = targetPt,
        targetPoint = targetPt,
        referencePoint = t.anchorPoint,
        label = t.label,
      )
    }

    return SmartGuideResult(Vec2(snappedDx, snappedDy), guides)
  }

  /**
   * Aligns a dragged wall endpoint against the wall's opposite endpoint and all other canvas
   * objects/walls so orthogonal alignment & remote object alignment show dashed extension lines.
   */
  fun alignWallEndpoint(
    rawPoint: Vec2,
    fixedOtherEndpoint: Vec2,
    movingWallId: String,
    plan: FloorPlanData,
    rooms: List<DetectedRoom>,
    snapToleranceMm: Float,
  ): SmartGuideResult {
    val xTargets = ArrayList<AxisTarget>()
    val yTargets = ArrayList<AxisTarget>()
    // Own opposite endpoint for crisp horizontal/vertical guide feedback
    xTargets += AxisTarget(fixedOtherEndpoint.x, fixedOtherEndpoint, "90°")
    yTargets += AxisTarget(fixedOtherEndpoint.y, fixedOtherEndpoint, "0°")
    collectCommonTargets(plan, rooms, excludeSymbolId = null, excludeWallId = movingWallId, excludeTextId = null, xTargets, yTargets)
    return alignPointAgainstTargets(rawPoint, xTargets, yTargets, snapToleranceMm)
  }

  private fun alignPointAgainstTargets(
    rawPoint: Vec2,
    xTargets: List<AxisTarget>,
    yTargets: List<AxisTarget>,
    snapToleranceMm: Float,
  ): SmartGuideResult {
    var bestDx = Float.MAX_VALUE
    var bestXTarget: AxisTarget? = null
    for (t in xTargets) {
      val diff = t.coord - rawPoint.x
      if (abs(diff) <= snapToleranceMm && abs(diff) < abs(bestDx)) {
        bestDx = diff
        bestXTarget = t
      }
    }

    var bestDy = Float.MAX_VALUE
    var bestYTarget: AxisTarget? = null
    for (t in yTargets) {
      val diff = t.coord - rawPoint.y
      if (abs(diff) <= snapToleranceMm && abs(diff) < abs(bestDy)) {
        bestDy = diff
        bestYTarget = t
      }
    }

    val finalX = if (bestXTarget != null) rawPoint.x + bestDx else rawPoint.x
    val finalY = if (bestYTarget != null) rawPoint.y + bestDy else rawPoint.y
    val snapped = Vec2(finalX, finalY)

    val guides = mutableListOf<SmartGuide>()
    bestXTarget?.let { t ->
      guides += SmartGuide(
        axis = GuideAxis.VERTICAL,
        start = t.anchorPoint,
        end = snapped,
        targetPoint = snapped,
        referencePoint = t.anchorPoint,
        label = t.label,
      )
    }
    bestYTarget?.let { t ->
      guides += SmartGuide(
        axis = GuideAxis.HORIZONTAL,
        start = t.anchorPoint,
        end = snapped,
        targetPoint = snapped,
        referencePoint = t.anchorPoint,
        label = t.label,
      )
    }
    return SmartGuideResult(snapped, guides)
  }

  private fun collectCommonTargets(
    plan: FloorPlanData,
    rooms: List<DetectedRoom>,
    excludeSymbolId: String?,
    excludeWallId: String?,
    excludeTextId: String?,
    xTargets: MutableList<AxisTarget>,
    yTargets: MutableList<AxisTarget>,
  ) {
    for (w in plan.walls) {
      if (w.id == excludeWallId) continue
      val mid = GeometryMath.lerp(w.start, w.end, 0.5f)
      xTargets += AxisTarget(w.start.x, w.start, "Wall")
      xTargets += AxisTarget(w.end.x, w.end, "Wall")
      xTargets += AxisTarget(mid.x, mid, "Mid")
      yTargets += AxisTarget(w.start.y, w.start, "Wall")
      yTargets += AxisTarget(w.end.y, w.end, "Wall")
      yTargets += AxisTarget(mid.y, mid, "Mid")
    }
    for (s in plan.symbols) {
      if (s.id == excludeSymbolId) continue
      xTargets += AxisTarget(s.center.x, s.center, "Center")
      yTargets += AxisTarget(s.center.y, s.center, "Center")
    }
    for (t in plan.texts) {
      if (t.id == excludeTextId) continue
      xTargets += AxisTarget(t.position.x, t.position, "Label")
      yTargets += AxisTarget(t.position.y, t.position, "Label")
    }
    for (r in rooms) {
      xTargets += AxisTarget(r.center.x, r.center, "Room")
      yTargets += AxisTarget(r.center.y, r.center, "Room")
    }
  }
}
