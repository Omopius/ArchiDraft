package com.example.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.example.geometry.DetectedRoom
import com.example.geometry.GeometryMath
import com.example.geometry.RoomAreaEngine
import com.example.model.DoorOpening
import com.example.model.DoorType
import com.example.model.FloorPlanData
import com.example.model.PlacedSymbol
import com.example.model.SymbolType
import com.example.model.UnitFormatter
import com.example.model.UnitSystem
import com.example.model.Vec2
import com.example.model.Wall
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object PlanExporter {

  fun renderToBitmap(
    plan: FloorPlanData,
    unitSystem: UnitSystem,
    planName: String,
    widthPx: Int = 2400,
    heightPx: Int = 1800,
  ): Bitmap {
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)

    val bounds = GeometryMath.computePlanBounds(plan)
    val padMm = 900f
    val minX = (bounds?.minX ?: 0f) - padMm
    val minY = (bounds?.minY ?: 0f) - padMm
    val worldW = max(2000f, ((bounds?.width ?: 6000f) + padMm * 2f))
    val worldH = max(1500f, ((bounds?.height ?: 4500f) + padMm * 2f))

    val marginPx = 110f
    val scale = min(
      (widthPx - marginPx * 2f) / worldW,
      (heightPx - marginPx * 2f - 80f) / worldH,
    )
    val offsetX = (widthPx - worldW * scale) * 0.5f - minX * scale
    val offsetY = (heightPx - 60f - worldH * scale) * 0.5f - minY * scale

    fun toPx(v: Vec2): Pair<Float, Float> = (v.x * scale + offsetX) to (v.y * scale + offsetY)

    // Subtle grid
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#ECEFF3")
      strokeWidth = 1.5f
    }
    val step = GeometryMath.majorGridMm(unitSystem)
    var gx = kotlin.math.floor(minX / step) * step
    while (gx <= minX + worldW) {
      val sx = gx * scale + offsetX
      canvas.drawLine(sx, marginPx, sx, heightPx - marginPx, gridPaint)
      gx += step
    }
    var gy = kotlin.math.floor(minY / step) * step
    while (gy <= minY + worldH) {
      val sy = gy * scale + offsetY
      canvas.drawLine(marginPx, sy, widthPx - marginPx, sy, gridPaint)
      gy += step
    }

    // Symbols
    val symStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#334155")
      style = Paint.Style.STROKE
      strokeWidth = 2.5f
    }
    val symFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#F8FAFC")
      style = Paint.Style.FILL
    }
    for (sym in plan.symbols) {
      val (cx, cy) = toPx(sym.center)
      canvas.save()
      canvas.translate(cx, cy)
      canvas.rotate(sym.rotationDeg)
      val w = sym.widthMm * scale
      val d = sym.depthMm * scale
      val rect = RectF(-w * 0.5f, -d * 0.5f, w * 0.5f, d * 0.5f)
      canvas.drawRect(rect, symFill)
      canvas.drawRect(rect, symStroke)
      canvas.restore()
    }

    // Walls (solid segments excluding doors/windows)
    val wallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#1E293B")
      style = Paint.Style.FILL
    }
    for (wall in plan.walls) {
      val segs = GeometryMath.computeWallSolidSegments(wall, plan.doors, plan.windows)
      val normal = GeometryMath.wallNormal(wall)
      val halfT = wall.thicknessMm * 0.5f
      for ((t0, t1) in segs) {
        val a = GeometryMath.lerp(wall.start, wall.end, t0)
        val b = GeometryMath.lerp(wall.start, wall.end, t1)
        val nOff = GeometryMath.mul(normal, halfT)
        val p1 = toPx(GeometryMath.add(a, nOff))
        val p2 = toPx(GeometryMath.add(b, nOff))
        val p3 = toPx(GeometryMath.sub(b, nOff))
        val p4 = toPx(GeometryMath.sub(a, nOff))
        val path = Path().apply {
          moveTo(p1.first, p1.second)
          lineTo(p2.first, p2.second)
          lineTo(p3.first, p3.second)
          lineTo(p4.first, p4.second)
          close()
        }
        canvas.drawPath(path, wallFill)
      }
    }

    // Doors
    val doorArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#475569")
      style = Paint.Style.STROKE
      strokeWidth = 2.2f
      pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    val doorLeafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#0F172A")
      style = Paint.Style.STROKE
      strokeWidth = 4f
    }
    for (door in plan.doors) {
      val wall = plan.walls.find { it.id == door.wallId } ?: continue
      drawDoorPdfOrPng(canvas, wall, door, scale, offsetX, offsetY, doorLeafPaint, doorArcPaint)
    }

    // Windows
    val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#0284C7")
      style = Paint.Style.STROKE
      strokeWidth = 2.5f
    }
    val winFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#E0F2FE")
      style = Paint.Style.FILL
    }
    for (win in plan.windows) {
      val wall = plan.walls.find { it.id == win.wallId } ?: continue
      val len = GeometryMath.wallLength(wall)
      if (len < 1f) continue
      val half = (win.widthMm * 0.5f) / len
      val a = GeometryMath.lerp(wall.start, wall.end, (win.t - half).coerceIn(0f, 1f))
      val b = GeometryMath.lerp(wall.start, wall.end, (win.t + half).coerceIn(0f, 1f))
      val normal = GeometryMath.wallNormal(wall)
      val nOff = GeometryMath.mul(normal, wall.thicknessMm * 0.45f)
      val p1 = toPx(GeometryMath.add(a, nOff))
      val p2 = toPx(GeometryMath.add(b, nOff))
      val p3 = toPx(GeometryMath.sub(b, nOff))
      val p4 = toPx(GeometryMath.sub(a, nOff))
      val path = Path().apply {
        moveTo(p1.first, p1.second)
        lineTo(p2.first, p2.second)
        lineTo(p3.first, p3.second)
        lineTo(p4.first, p4.second)
        close()
      }
      canvas.drawPath(path, winFill)
      canvas.drawPath(path, winPaint)
      val m1 = toPx(a)
      val m2 = toPx(b)
      canvas.drawLine(m1.first, m1.second, m2.first, m2.second, winPaint)
    }

    // Linear dimensions
    val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#1E293B")
      strokeWidth = 2f
    }
    val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#0F172A")
      textSize = 26f
      typeface = Typeface.MONOSPACE
      textAlign = Paint.Align.CENTER
    }
    for (dim in plan.dimensions) {
      val (d1, d2) = GeometryMath.dimensionLineEndpoints(dim)
      val s1 = toPx(dim.start)
      val s2 = toPx(dim.end)
      val p1 = toPx(d1)
      val p2 = toPx(d2)
      canvas.drawLine(s1.first, s1.second, p1.first, p1.second, dimPaint)
      canvas.drawLine(s2.first, s2.second, p2.first, p2.second, dimPaint)
      canvas.drawLine(p1.first, p1.second, p2.first, p2.second, dimPaint)
      val label = UnitFormatter.formatLength(GeometryMath.distance(dim.start, dim.end), unitSystem)
      val midX = (p1.first + p2.first) * 0.5f
      val midY = (p1.second + p2.second) * 0.5f - 10f
      canvas.drawText(label, midX, midY, dimTextPaint)
    }

    // Room / annotation text labels
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#1E293B")
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      style = Paint.Style.FILL
    }
    for (txt in plan.texts) {
      val (tx, ty) = toPx(txt.position)
      labelPaint.textSize = (txt.fontSizeMm * scale).coerceAtLeast(14f)
      val wText = labelPaint.measureText(txt.text)
      canvas.save()
      canvas.translate(tx, ty)
      canvas.rotate(txt.rotationDeg)
      if (txt.hasOpaqueBackground) {
        val padX = 12f
        val padY = 8f
        canvas.drawRoundRect(
          RectF(-wText * 0.5f - padX, -labelPaint.textSize * 0.65f - padY, wText * 0.5f + padX, labelPaint.textSize * 0.45f + padY),
          8f,
          8f,
          labelBgPaint,
        )
      }
      canvas.drawText(txt.text, 0f, labelPaint.textSize * 0.35f, labelPaint)
      canvas.restore()
    }

    // Detected closed-loop room areas
    val rooms = RoomAreaEngine.detectRooms(plan)
    val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#1D4ED8")
      textSize = 24f
      typeface = Typeface.MONOSPACE
      textAlign = Paint.Align.CENTER
    }
    for (room in rooms) {
      val (rx, ry) = toPx(room.center)
      canvas.drawText(UnitFormatter.formatArea(room.areaSqMm, unitSystem), rx, ry + 30f, areaPaint)
    }

    // Footer title block
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#0F172A")
      textSize = 34f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#64748B")
      textSize = 24f
    }
    canvas.drawText(planName, marginPx, heightPx - 46f, titlePaint)
    canvas.drawText(
      "2D Architectural Floor Plan  •  Units: ${if (unitSystem == UnitSystem.METRIC) "Metric (mm/m)" else "Imperial (ft-in)"}",
      widthPx - marginPx - 680f,
      heightPx - 46f,
      metaPaint,
    )

    return bmp
  }

  private fun drawDoorPdfOrPng(
    canvas: Canvas,
    wall: Wall,
    door: DoorOpening,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    leafPaint: Paint,
    arcPaint: Paint,
  ) {
    val len = GeometryMath.wallLength(wall)
    if (len < 1f) return
    val half = (door.widthMm * 0.5f) / len
    val a = GeometryMath.lerp(wall.start, wall.end, (door.t - half).coerceIn(0f, 1f))
    val b = GeometryMath.lerp(wall.start, wall.end, (door.t + half).coerceIn(0f, 1f))
    val ax = a.x * scale + offsetX
    val ay = a.y * scale + offsetY
    val bx = b.x * scale + offsetX
    val by = b.y * scale + offsetY

    val dir = GeometryMath.wallDir(wall)
    val baseAngleDeg = (atan2(dir.y, dir.x) * 180.0 / PI).toFloat()
    val wPx = door.widthMm * scale

    when (door.doorType) {
      DoorType.SLIDING -> {
        val nx = -dir.y * (wall.thicknessMm * 0.16f * scale)
        val ny = dir.x * (wall.thicknessMm * 0.16f * scale)
        val mx = (ax + bx) * 0.5f
        val my = (ay + by) * 0.5f
        canvas.drawLine(ax + nx, ay + ny, mx + nx + (bx - ax) * 0.12f, my + ny + (by - ay) * 0.12f, leafPaint)
        canvas.drawLine(mx - nx - (bx - ax) * 0.12f, my - ny - (by - ay) * 0.12f, bx - nx, by - ny, leafPaint)
      }
      DoorType.DOUBLE_SWING -> {
        val r = wPx * 0.5f
        val swingSign = if (door.swingLeft) -1f else 1f
        val nX = -dir.y * swingSign
        val nY = dir.x * swingSign
        canvas.drawLine(ax, ay, ax + nX * r, ay + nY * r, leafPaint)
        canvas.drawLine(bx, by, bx + nX * r, by + nY * r, leafPaint)
        val rectL = RectF(ax - r, ay - r, ax + r, ay + r)
        val rectR = RectF(bx - r, by - r, bx + r, by + r)
        canvas.drawArc(rectL, baseAngleDeg, swingSign * 90f, false, arcPaint)
        canvas.drawArc(rectR, baseAngleDeg + 180f, -swingSign * 90f, false, arcPaint)
      }
      DoorType.SINGLE_SWING -> {
        val hingeX = if (door.flipHinge) bx else ax
        val hingeY = if (door.flipHinge) by else ay
        val swingSign = if (door.swingLeft) -1f else 1f
        val nX = -dir.y * swingSign
        val nY = dir.x * swingSign
        canvas.drawLine(hingeX, hingeY, hingeX + nX * wPx, hingeY + nY * wPx, leafPaint)
        val rect = RectF(hingeX - wPx, hingeY - wPx, hingeX + wPx, hingeY + wPx)
        val startAngle = if (door.flipHinge) baseAngleDeg + 180f else baseAngleDeg
        val sweep = if (door.flipHinge) -swingSign * 90f else swingSign * 90f
        canvas.drawArc(rect, startAngle, sweep, false, arcPaint)
      }
    }
  }

  fun renderToSvg(
    plan: FloorPlanData,
    unitSystem: UnitSystem,
    planName: String,
  ): String {
    val bounds = GeometryMath.computePlanBounds(plan)
    val pad = 800f
    val minX = (bounds?.minX ?: 0f) - pad
    val minY = (bounds?.minY ?: 0f) - pad
    val w = max(2000f, (bounds?.width ?: 6000f) + pad * 2f)
    val h = max(1500f, (bounds?.height ?: 4500f) + pad * 2f)

    val sb = StringBuilder()
    sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.appendLine(
      """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$minX $minY $w $h" width="1600" height="${(1600f * h / w).toInt()}">""",
    )
    sb.appendLine("""  <title>${escapeXml(planName)}</title>""")
    sb.appendLine("""  <rect x="$minX" y="$minY" width="$w" height="$h" fill="#FFFFFF" />""")

    // Symbols
    sb.appendLine("""  <g id="symbols" stroke="#334155" stroke-width="16" fill="#F8FAFC">""")
    for (sym in plan.symbols) {
      val hw = sym.widthMm * 0.5f
      val hd = sym.depthMm * 0.5f
      sb.appendLine(
        """    <rect x="${-hw}" y="${-hd}" width="${sym.widthMm}" height="${sym.depthMm}" transform="translate(${sym.center.x},${sym.center.y}) rotate(${sym.rotationDeg})" />""",
      )
    }
    sb.appendLine("""  </g>""")

    // Walls
    sb.appendLine("""  <g id="walls" fill="#1E293B">""")
    for (wall in plan.walls) {
      val segs = GeometryMath.computeWallSolidSegments(wall, plan.doors, plan.windows)
      val normal = GeometryMath.wallNormal(wall)
      val nOff = GeometryMath.mul(normal, wall.thicknessMm * 0.5f)
      for ((t0, t1) in segs) {
        val a = GeometryMath.lerp(wall.start, wall.end, t0)
        val b = GeometryMath.lerp(wall.start, wall.end, t1)
        val p1 = GeometryMath.add(a, nOff)
        val p2 = GeometryMath.add(b, nOff)
        val p3 = GeometryMath.sub(b, nOff)
        val p4 = GeometryMath.sub(a, nOff)
        sb.appendLine(
          """    <polygon points="${p1.x},${p1.y} ${p2.x},${p2.y} ${p3.x},${p3.y} ${p4.x},${p4.y}" />""",
        )
      }
    }
    sb.appendLine("""  </g>""")

    // Windows
    sb.appendLine("""  <g id="windows" stroke="#0284C7" stroke-width="18" fill="#E0F2FE">""")
    for (win in plan.windows) {
      val wall = plan.walls.find { it.id == win.wallId } ?: continue
      val len = GeometryMath.wallLength(wall)
      if (len < 1f) continue
      val half = (win.widthMm * 0.5f) / len
      val a = GeometryMath.lerp(wall.start, wall.end, (win.t - half).coerceIn(0f, 1f))
      val b = GeometryMath.lerp(wall.start, wall.end, (win.t + half).coerceIn(0f, 1f))
      val normal = GeometryMath.wallNormal(wall)
      val nOff = GeometryMath.mul(normal, wall.thicknessMm * 0.45f)
      val p1 = GeometryMath.add(a, nOff)
      val p2 = GeometryMath.add(b, nOff)
      val p3 = GeometryMath.sub(b, nOff)
      val p4 = GeometryMath.sub(a, nOff)
      sb.appendLine(
        """    <polygon points="${p1.x},${p1.y} ${p2.x},${p2.y} ${p3.x},${p3.y} ${p4.x},${p4.y}" />""",
      )
      sb.appendLine("""    <line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" />""")
    }
    sb.appendLine("""  </g>""")

    // Doors
    sb.appendLine("""  <g id="doors" stroke="#0F172A" stroke-width="24" fill="none">""")
    for (door in plan.doors) {
      val wall = plan.walls.find { it.id == door.wallId } ?: continue
      val len = GeometryMath.wallLength(wall)
      if (len < 1f) continue
      val half = (door.widthMm * 0.5f) / len
      val a = GeometryMath.lerp(wall.start, wall.end, (door.t - half).coerceIn(0f, 1f))
      val b = GeometryMath.lerp(wall.start, wall.end, (door.t + half).coerceIn(0f, 1f))
      val dir = GeometryMath.wallDir(wall)
      val swingSign = if (door.swingLeft) -1f else 1f
      val nX = -dir.y * swingSign
      val nY = dir.x * swingSign
      val hinge = if (door.flipHinge) b else a
      val tip = Vec2(hinge.x + nX * door.widthMm, hinge.y + nY * door.widthMm)
      val endLeaf = if (door.flipHinge) a else b
      sb.appendLine("""    <line x1="${hinge.x}" y1="${hinge.y}" x2="${tip.x}" y2="${tip.y}" />""")
      sb.appendLine(
        """    <path d="M ${endLeaf.x} ${endLeaf.y} A ${door.widthMm} ${door.widthMm} 0 0 ${if (door.swingLeft xor door.flipHinge) 0 else 1} ${tip.x} ${tip.y}" stroke="#64748B" stroke-width="14" stroke-dasharray="45 35" />""",
      )
    }
    sb.appendLine("""  </g>""")

    // Dimensions
    sb.appendLine("""  <g id="dimensions" stroke="#1E293B" stroke-width="14" fill="#0F172A" font-family="monospace" font-size="140">""")
    for (dim in plan.dimensions) {
      val (p1, p2) = GeometryMath.dimensionLineEndpoints(dim)
      sb.appendLine("""    <line x1="${dim.start.x}" y1="${dim.start.y}" x2="${p1.x}" y2="${p1.y}" />""")
      sb.appendLine("""    <line x1="${dim.end.x}" y1="${dim.end.y}" x2="${p2.x}" y2="${p2.y}" />""")
      sb.appendLine("""    <line x1="${p1.x}" y1="${p1.y}" x2="${p2.x}" y2="${p2.y}" />""")
      val mx = (p1.x + p2.x) * 0.5f
      val my = (p1.y + p2.y) * 0.5f - 60f
      val label = UnitFormatter.formatLength(GeometryMath.distance(dim.start, dim.end), unitSystem)
      sb.appendLine("""    <text x="$mx" y="$my" text-anchor="middle" stroke="none">${escapeXml(label)}</text>""")
    }
    sb.appendLine("""  </g>""")

    // Text labels
    sb.appendLine("""  <g id="texts" font-family="sans-serif" font-weight="600" text-anchor="middle">""")
    for (txt in plan.texts) {
      if (txt.hasOpaqueBackground) {
        val (tw, th) = GeometryMath.textLabelSizeMm(txt)
        sb.appendLine(
          """    <rect x="${txt.position.x - tw * 0.5f}" y="${txt.position.y - th * 0.55f}" width="$tw" height="$th" rx="40" fill="#FFFFFF" transform="rotate(${txt.rotationDeg} ${txt.position.x} ${txt.position.y})" />""",
        )
      }
      sb.appendLine(
        """    <text x="${txt.position.x}" y="${txt.position.y}" fill="#1E293B" font-size="${txt.fontSizeMm}" transform="rotate(${txt.rotationDeg} ${txt.position.x} ${txt.position.y})">${escapeXml(txt.text)}</text>""",
      )
    }
    sb.appendLine("""  </g>""")

    // Room areas
    val rooms = RoomAreaEngine.detectRooms(plan)
    sb.appendLine("""  <g id="room_areas" fill="#1D4ED8" font-family="monospace" font-size="140" text-anchor="middle">""")
    for (room in rooms) {
      sb.appendLine(
        """    <text x="${room.center.x}" y="${room.center.y + 180f}">${escapeXml(UnitFormatter.formatArea(room.areaSqMm, unitSystem))}</text>""",
      )
    }
    sb.appendLine("""  </g>""")
    sb.appendLine("</svg>")
    return sb.toString()
  }

  /**
   * Exports standard ASCII AutoCAD DXF (AC1009 / R12 compatible) with separate CAD layers
   * for WALLS, DOORS, WINDOWS, SYMBOLS, DIMENSIONS, TEXT, and AREAS.
   * Coordinates use standard architectural Y-up Cartesian orientation (negating screen Y).
   */
  fun renderToDxf(
    plan: FloorPlanData,
    unitSystem: UnitSystem,
    planName: String,
    rooms: List<DetectedRoom> = RoomAreaEngine.detectRooms(plan),
  ): String {
    val sb = StringBuilder()
    fun dxfPair(code: Int, value: String) {
      sb.appendLine(code)
      sb.appendLine(value)
    }

    fun dxfLine(layer: String, x1: Float, y1: Float, x2: Float, y2: Float) {
      dxfPair(0, "LINE")
      dxfPair(8, layer)
      dxfPair(10, x1.toString())
      dxfPair(20, (-y1).toString())
      dxfPair(30, "0.0")
      dxfPair(11, x2.toString())
      dxfPair(21, (-y2).toString())
      dxfPair(31, "0.0")
    }

    fun dxfClosedPoly(layer: String, pts: List<Vec2>) {
      if (pts.size < 2) return
      for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        dxfLine(layer, a.x, a.y, b.x, b.y)
      }
    }

    fun dxfArc(layer: String, cx: Float, cy: Float, radius: Float, startDeg: Float, endDeg: Float) {
      dxfPair(0, "ARC")
      dxfPair(8, layer)
      dxfPair(10, cx.toString())
      dxfPair(20, (-cy).toString())
      dxfPair(30, "0.0")
      dxfPair(40, radius.toString())
      dxfPair(50, (((startDeg % 360f) + 360f) % 360f).toString())
      dxfPair(51, (((endDeg % 360f) + 360f) % 360f).toString())
    }

    fun dxfText(layer: String, x: Float, y: Float, height: Float, text: String, rotDeg: Float = 0f) {
      dxfPair(0, "TEXT")
      dxfPair(8, layer)
      dxfPair(10, x.toString())
      dxfPair(20, (-y).toString())
      dxfPair(30, "0.0")
      dxfPair(40, height.toString())
      dxfPair(1, text)
      if (rotDeg != 0f) dxfPair(50, (-rotDeg).toString())
      dxfPair(72, "1") // center horizontally
      dxfPair(11, x.toString())
      dxfPair(21, (-y).toString())
      dxfPair(31, "0.0")
    }

    // HEADER
    dxfPair(0, "SECTION")
    dxfPair(2, "HEADER")
    dxfPair(9, "\$ACADVER")
    dxfPair(1, "AC1009")
    dxfPair(9, "\$INSUNITS")
    dxfPair(70, if (unitSystem == UnitSystem.METRIC) "4" else "1") // 4=mm, 1=inches
    dxfPair(0, "ENDSEC")

    // TABLES (LAYERS)
    dxfPair(0, "SECTION")
    dxfPair(2, "TABLES")
    dxfPair(0, "TABLE")
    dxfPair(2, "LAYER")
    dxfPair(70, "7")
    val layers = listOf(
      "WALLS" to 7,
      "DOORS" to 3,
      "WINDOWS" to 5,
      "SYMBOLS" to 8,
      "DIMENSIONS" to 4,
      "TEXT" to 7,
      "AREAS" to 5,
    )
    for ((name, colorIdx) in layers) {
      dxfPair(0, "LAYER")
      dxfPair(2, name)
      dxfPair(70, "0")
      dxfPair(62, colorIdx.toString())
      dxfPair(6, "CONTINUOUS")
    }
    dxfPair(0, "ENDTAB")
    dxfPair(0, "ENDSEC")

    // ENTITIES
    dxfPair(0, "SECTION")
    dxfPair(2, "ENTITIES")

    // Walls (solid segment outlines)
    for (wall in plan.walls) {
      val segs = GeometryMath.computeWallSolidSegments(wall, plan.doors, plan.windows)
      val normal = GeometryMath.wallNormal(wall)
      val nOff = GeometryMath.mul(normal, wall.thicknessMm * 0.5f)
      for ((t0, t1) in segs) {
        val a = GeometryMath.lerp(wall.start, wall.end, t0)
        val b = GeometryMath.lerp(wall.start, wall.end, t1)
        dxfClosedPoly(
          "WALLS",
          listOf(
            GeometryMath.add(a, nOff),
            GeometryMath.add(b, nOff),
            GeometryMath.sub(b, nOff),
            GeometryMath.sub(a, nOff),
          ),
        )
      }
    }

    // Doors
    for (door in plan.doors) {
      val wall = plan.walls.find { it.id == door.wallId } ?: continue
      val len = GeometryMath.wallLength(wall)
      if (len < 1f) continue
      val half = (door.widthMm * 0.5f) / len
      val a = GeometryMath.lerp(wall.start, wall.end, (door.t - half).coerceIn(0f, 1f))
      val b = GeometryMath.lerp(wall.start, wall.end, (door.t + half).coerceIn(0f, 1f))
      val dir = GeometryMath.wallDir(wall)
      val swingSign = if (door.swingLeft) -1f else 1f
      val nX = -dir.y * swingSign
      val nY = dir.x * swingSign
      val hinge = if (door.flipHinge) b else a
      val tip = Vec2(hinge.x + nX * door.widthMm, hinge.y + nY * door.widthMm)
      dxfLine("DOORS", hinge.x, hinge.y, tip.x, tip.y)
      val baseDeg = (-atan2(dir.y, dir.x) * 180.0 / PI).toFloat()
      val leafEnd = if (door.flipHinge) a else b
      val ang1 = (-atan2(leafEnd.y - hinge.y, leafEnd.x - hinge.x) * 180.0 / PI).toFloat()
      val ang2 = (-atan2(tip.y - hinge.y, tip.x - hinge.x) * 180.0 / PI).toFloat()
      val diff = (((ang2 - ang1) % 360f) + 360f) % 360f
      if (diff <= 180f) {
        dxfArc("DOORS", hinge.x, hinge.y, door.widthMm, ang1, ang2)
      } else {
        dxfArc("DOORS", hinge.x, hinge.y, door.widthMm, ang2, ang1)
      }
    }

    // Windows
    for (win in plan.windows) {
      val wall = plan.walls.find { it.id == win.wallId } ?: continue
      val len = GeometryMath.wallLength(wall)
      if (len < 1f) continue
      val half = (win.widthMm * 0.5f) / len
      val a = GeometryMath.lerp(wall.start, wall.end, (win.t - half).coerceIn(0f, 1f))
      val b = GeometryMath.lerp(wall.start, wall.end, (win.t + half).coerceIn(0f, 1f))
      val normal = GeometryMath.wallNormal(wall)
      val nOff = GeometryMath.mul(normal, wall.thicknessMm * 0.45f)
      dxfClosedPoly(
        "WINDOWS",
        listOf(
          GeometryMath.add(a, nOff),
          GeometryMath.add(b, nOff),
          GeometryMath.sub(b, nOff),
          GeometryMath.sub(a, nOff),
        ),
      )
      dxfLine("WINDOWS", a.x, a.y, b.x, b.y)
    }

    // Symbols (rotated bounding boxes)
    for (sym in plan.symbols) {
      val hw = sym.widthMm * 0.5f
      val hd = sym.depthMm * 0.5f
      val rad = sym.rotationDeg * PI / 180.0
      val c = cos(rad).toFloat()
      val s = sin(rad).toFloat()
      fun rot(lx: Float, ly: Float) = Vec2(sym.center.x + lx * c - ly * s, sym.center.y + lx * s + ly * c)
      dxfClosedPoly(
        "SYMBOLS",
        listOf(rot(-hw, -hd), rot(hw, -hd), rot(hw, hd), rot(-hw, hd)),
      )
    }

    // Dimensions
    for (dim in plan.dimensions) {
      val (p1, p2) = GeometryMath.dimensionLineEndpoints(dim)
      dxfLine("DIMENSIONS", dim.start.x, dim.start.y, p1.x, p1.y)
      dxfLine("DIMENSIONS", dim.end.x, dim.end.y, p2.x, p2.y)
      dxfLine("DIMENSIONS", p1.x, p1.y, p2.x, p2.y)
      val mx = (p1.x + p2.x) * 0.5f
      val my = (p1.y + p2.y) * 0.5f - 80f
      val label = UnitFormatter.formatLength(GeometryMath.distance(dim.start, dim.end), unitSystem)
      dxfText("DIMENSIONS", mx, my, 130f, label)
    }

    // Text Labels
    for (txt in plan.texts) {
      dxfText("TEXT", txt.position.x, txt.position.y, txt.fontSizeMm, txt.text, txt.rotationDeg)
    }

    // Room Areas
    for (room in rooms) {
      dxfText("AREAS", room.center.x, room.center.y + 180f, 150f, UnitFormatter.formatArea(room.areaSqMm, unitSystem))
    }

    dxfText("TEXT", 0f, -1200f, 220f, planName)

    dxfPair(0, "ENDSEC")
    dxfPair(0, "EOF")
    return sb.toString()
  }

  private fun escapeXml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
}
