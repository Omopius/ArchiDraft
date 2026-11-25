package com.example.geometry

import com.example.model.FloorPlanData
import com.example.model.Vec2
import com.example.model.Wall
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DetectedRoom(
  val id: String,
  val polygon: List<Vec2>,
  val innerPolygon: List<Vec2>,
  val areaSqMm: Float,
  val center: Vec2,
) {
  val areaSqMeters: Float get() = areaSqMm / 1_000_000f
  val areaSqFeet: Float get() = areaSqMm / 92_903.04f
}

private data class GraphEdge(
  val u: Int,
  val v: Int,
  val thicknessMm: Float,
)

object RoomAreaEngine {

  fun detectRooms(plan: FloorPlanData): List<DetectedRoom> {
    if (plan.walls.size < 3) return emptyList()

    // 1. Collect raw segments & weld nearby endpoints
    val rawPoints = mutableListOf<Vec2>()
    val weldTol = 65f

    fun internVertex(p: Vec2): Int {
      for (i in rawPoints.indices) {
        if (GeometryMath.distance(rawPoints[i], p) <= weldTol) return i
      }
      rawPoints += p
      return rawPoints.lastIndex
    }

    data class RawSeg(val a: Int, val b: Int, val thickness: Float)
    val initialSegs = mutableListOf<RawSeg>()
    for (w in plan.walls) {
      if (GeometryMath.wallLength(w) < 80f) continue
      val ia = internVertex(w.start)
      val ib = internVertex(w.end)
      if (ia != ib) initialSegs += RawSeg(ia, ib, w.thicknessMm)
    }

    // 2. Also detect segment-segment intersections so crossing walls form a valid planar vertex
    for (i in initialSegs.indices) {
      for (j in (i + 1) until initialSegs.size) {
        val s1 = initialSegs[i]
        val s2 = initialSegs[j]
        val inter = segmentIntersection(
          rawPoints[s1.a], rawPoints[s1.b],
          rawPoints[s2.a], rawPoints[s2.b],
        )
        if (inter != null) {
          internVertex(inter)
        }
      }
    }

    // 3. Split segments at any interior vertex lying on the segment (T-junctions & crossings)
    val splitEdges = mutableListOf<GraphEdge>()
    val edgeKeys = HashSet<Long>()
    val junctionTol = 45f

    for (seg in initialSegs) {
      val pA = rawPoints[seg.a]
      val pB = rawPoints[seg.b]
      val len = GeometryMath.distance(pA, pB)
      if (len < 60f) continue

      val splits = mutableListOf(0f to seg.a, 1f to seg.b)
      for (vIdx in rawPoints.indices) {
        if (vIdx == seg.a || vIdx == seg.b) continue
        val p = rawPoints[vIdx]
        val (t, proj) = GeometryMath.projectPointOnSegment(p, pA, pB)
        if (t > 0.01f && t < 0.99f && GeometryMath.distance(p, proj) <= junctionTol) {
          splits += t to vIdx
        }
      }
      val ordered = splits.sortedBy { it.first }
      for (k in 0 until ordered.lastIndex) {
        val u = ordered[k].second
        val v = ordered[k + 1].second
        if (u == v) continue
        val key = undirectedKey(u, v)
        if (edgeKeys.add(key)) {
          splitEdges += GraphEdge(u, v, seg.thickness)
        }
      }
    }

    // 4. Iteratively prune degree-1 spurs so open stub walls don't break closed rooms
    val activeEdges = splitEdges.toMutableList()
    while (true) {
      val deg = IntArray(rawPoints.size)
      for (e in activeEdges) {
        deg[e.u]++
        deg[e.v]++
      }
      val before = activeEdges.size
      activeEdges.removeAll { deg[it.u] <= 1 || deg[it.v] <= 1 }
      if (activeEdges.size == before) break
    }
    if (activeEdges.size < 3) return emptyList()

    // 5. Build half-edge adjacency sorted by outgoing angle at each vertex
    data class HalfEdge(
      val id: Int,
      val from: Int,
      val to: Int,
      val angle: Float,
      val thickness: Float,
      val twinId: Int,
    )

    val halfEdges = mutableListOf<HalfEdge>()
    val outgoing = Array(rawPoints.size) { mutableListOf<Int>() }
    for (e in activeEdges) {
      val id1 = halfEdges.size
      val id2 = id1 + 1
      val pU = rawPoints[e.u]
      val pV = rawPoints[e.v]
      val a1 = atan2(pV.y - pU.y, pV.x - pU.x)
      val a2 = atan2(pU.y - pV.y, pU.x - pV.x)
      halfEdges += HalfEdge(id1, e.u, e.v, a1, e.thicknessMm, id2)
      halfEdges += HalfEdge(id2, e.v, e.u, a2, e.thicknessMm, id1)
      outgoing[e.u] += id1
      outgoing[e.v] += id2
    }

    for (v in outgoing.indices) {
      outgoing[v].sortBy { halfEdges[it].angle }
    }

    // 6. Walk minimal planar faces
    val visited = BooleanArray(halfEdges.size)
    val rooms = mutableListOf<DetectedRoom>()

    for (startId in halfEdges.indices) {
      if (visited[startId]) continue
      val cycle = mutableListOf<HalfEdge>()
      var curr = startId
      var guard = 0
      while (!visited[curr] && guard++ < 256) {
        visited[curr] = true
        val he = halfEdges[curr]
        cycle += he
        val adj = outgoing[he.to]
        if (adj.isEmpty()) break
        val twinIdx = adj.indexOf(he.twinId)
        if (twinIdx < 0) break
        curr = adj[(twinIdx - 1 + adj.size) % adj.size]
      }
      if (curr != startId || cycle.size < 3) continue

      val poly = cycle.map { rawPoints[it.from] }
      val thicknesses = cycle.map { it.thickness }
      val signedArea = polygonSignedArea(poly)
      // Positive signedArea in screen coords (+Y down) corresponds to bounded interior faces
      if (signedArea > 150_000f) {
        val innerPoly = offsetPolygonInward(poly, thicknesses)
        val innerArea = abs(polygonSignedArea(innerPoly))
        val finalArea = if (innerArea > 50_000f && innerArea <= signedArea) innerArea else signedArea
        val center = polygonCentroid(if (innerPoly.size >= 3) innerPoly else poly)
        rooms += DetectedRoom(
          id = "room_${rooms.size}",
          polygon = poly,
          innerPolygon = innerPoly,
          areaSqMm = finalArea,
          center = center,
        )
      }
    }

    return rooms
  }

  private fun undirectedKey(a: Int, b: Int): Long {
    val lo = min(a, b).toLong()
    val hi = max(a, b).toLong()
    return (lo shl 32) or (hi and 0xFFFFFFFFL)
  }

  private fun segmentIntersection(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Vec2? {
    val d1x = p2.x - p1.x
    val d1y = p2.y - p1.y
    val d2x = p4.x - p3.x
    val d2y = p4.y - p3.y
    val denom = d1x * d2y - d1y * d2x
    if (abs(denom) < 1e-3f) return null
    val t = ((p3.x - p1.x) * d2y - (p3.y - p1.y) * d2x) / denom
    val u = ((p3.x - p1.x) * d1y - (p3.y - p1.y) * d1x) / denom
    if (t in 0.01f..0.99f && u in 0.01f..0.99f) {
      return Vec2(p1.x + t * d1x, p1.y + t * d1y)
    }
    return null
  }

  fun polygonSignedArea(pts: List<Vec2>): Float {
    if (pts.size < 3) return 0f
    var sum = 0f
    for (i in pts.indices) {
      val a = pts[i]
      val b = pts[(i + 1) % pts.size]
      sum += a.x * b.y - b.x * a.y
    }
    return sum * 0.5f
  }

  private fun offsetPolygonInward(poly: List<Vec2>, thicknesses: List<Float>): List<Vec2> {
    val n = poly.size
    if (n < 3) return poly
    data class Line2D(val p: Vec2, val d: Vec2)
    val shiftedLines = ArrayList<Line2D>(n)

    for (i in 0 until n) {
      val a = poly[i]
      val b = poly[(i + 1) % n]
      val dx = b.x - a.x
      val dy = b.y - a.y
      val len = hypot(dx, dy).coerceAtLeast(1f)
      val dir = Vec2(dx / len, dy / len)
      // Inward normal for CW (+signedArea in +Y-down coords)
      val inward = Vec2(-dir.y, dir.x)
      val shift = thicknesses[i] * 0.5f
      shiftedLines += Line2D(Vec2(a.x + inward.x * shift, a.y + inward.y * shift), dir)
    }

    val result = ArrayList<Vec2>(n)
    for (i in 0 until n) {
      val l1 = shiftedLines[(i - 1 + n) % n]
      val l2 = shiftedLines[i]
      val denom = l1.d.x * l2.d.y - l1.d.y * l2.d.x
      if (abs(denom) < 1e-3f) {
        result += l2.p
      } else {
        val t = ((l2.p.x - l1.p.x) * l2.d.y - (l2.p.y - l1.p.y) * l2.d.x) / denom
        result += Vec2(l1.p.x + t * l1.d.x, l1.p.y + t * l1.d.y)
      }
    }
    return result
  }

  private fun polygonCentroid(pts: List<Vec2>): Vec2 {
    if (pts.isEmpty()) return Vec2(0f, 0f)
    var signedArea = 0f
    var cx = 0f
    var cy = 0f
    for (i in pts.indices) {
      val p0 = pts[i]
      val p1 = pts[(i + 1) % pts.size]
      val a = p0.x * p1.y - p1.x * p0.y
      signedArea += a
      cx += (p0.x + p1.x) * a
      cy += (p0.y + p1.y) * a
    }
    signedArea *= 0.5f
    if (abs(signedArea) < 1e-2f) {
      return Vec2(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
    }
    return Vec2(cx / (6f * signedArea), cy / (6f * signedArea))
  }

  fun pointInPolygon(point: Vec2, poly: List<Vec2>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
      val xi = poly[i].x
      val yi = poly[i].y
      val xj = poly[j].x
      val yj = poly[j].y
      val intersects = ((yi > point.y) != (yj > point.y)) &&
        (point.x < (xj - xi) * (point.y - yi) / ((yj - yi).let { if (abs(it) < 1e-5f) 1e-5f else it }) + xi)
      if (intersects) inside = !inside
      j = i
    }
    return inside
  }

  /**
   * Finds the closed-loop room containing [worldPoint], prioritizing inner wall-bounded polygons
   * and smallest area if nested.
   */
  fun findRoomAt(worldPoint: Vec2, rooms: List<DetectedRoom>): DetectedRoom? {
    val matches = rooms.filter { room ->
      val poly = if (room.innerPolygon.size >= 3) room.innerPolygon else room.polygon
      pointInPolygon(worldPoint, poly) || pointInPolygon(worldPoint, room.polygon)
    }
    return matches.minByOrNull { it.areaSqMm }
  }
}
