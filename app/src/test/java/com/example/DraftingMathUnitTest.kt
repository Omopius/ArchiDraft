package com.example

import com.example.export.PlanExporter
import com.example.geometry.GeometryMath
import com.example.geometry.GuideAxis
import com.example.geometry.RoomAreaEngine
import com.example.geometry.SmartGuideEngine
import com.example.geometry.SnapKind
import com.example.geometry.TempDimensionEngine
import com.example.model.SamplePlans
import com.example.model.SelectionTarget
import com.example.model.TextLabel
import com.example.model.UnitFormatter
import com.example.model.UnitSystem
import com.example.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftingMathUnitTest {

  @Test
  fun testDimensionParsingMetricAndImperial() {
    assertEquals(1200f, UnitFormatter.parseDimensionInput("1200", UnitSystem.METRIC)!!, 0.1f)
    assertEquals(1200f, UnitFormatter.parseDimensionInput("1.2m", UnitSystem.METRIC)!!, 0.1f)
    assertEquals(150f, UnitFormatter.parseDimensionInput("15cm", UnitSystem.METRIC)!!, 0.1f)
    // 4'6" = 54 inches = 1371.6 mm
    assertEquals(1371.6f, UnitFormatter.parseDimensionInput("4'6\"", UnitSystem.METRIC)!!, 0.5f)
    assertEquals(914.4f, UnitFormatter.parseDimensionInput("36\"", UnitSystem.IMPERIAL)!!, 0.5f)
  }

  @Test
  fun testWallSolidSegmentsCutDoorsAndWindows() {
    val plan = SamplePlans.starterApartment()
    val bottomWall = plan.walls.first { it.id == "w_bottom" }
    val segs = GeometryMath.computeWallSolidSegments(bottomWall, plan.doors, plan.windows)
    assertEquals(2, segs.size)
    assertTrue(segs[0].second < segs[1].first)
  }

  @Test
  fun testTempDimensionsForWallAndSymbolEdit() {
    val plan = SamplePlans.starterApartment()
    val wallDims = TempDimensionEngine.computeForSelection(plan, SelectionTarget.SelectedWall("w_bed_div"))
    assertTrue(wallDims.isNotEmpty())

    val symDims = TempDimensionEngine.computeForSelection(plan, SelectionTarget.SelectedSymbol("sym_bed"))
    assertEquals(2, symDims.size)

    val xDim = symDims.first()
    val updatedPlan = TempDimensionEngine.applyTempDimensionEdit(plan, xDim, 500f)
    val newDims = TempDimensionEngine.computeForSelection(updatedPlan, SelectionTarget.SelectedSymbol("sym_bed"))
    val updatedX = newDims.find { it.id == xDim.id }
    assertNotNull(updatedX)
    assertEquals(500f, updatedX!!.distanceMm, 2f)
  }

  @Test
  fun testExteriorInToInDimensionsAndTextHitTest() {
    val plan = SamplePlans.starterApartment()
    val extDims = GeometryMath.generateExteriorInToInDimensions(plan.walls)
    assertTrue(extDims.size >= 6)
    // Top Living/Kitchen in-to-in should be 3725 - 115 = 3610mm
    val topLiving = extDims.first()
    assertEquals(3610f, GeometryMath.distance(topLiving.start, topLiving.end), 1f)

    // Starter plan has text labels that are hit-testable
    val bedText = plan.texts.first { it.id == "txt_bedroom" }
    val hit = GeometryMath.hitTest(bedText.position, plan, 50f)
    assertEquals(SelectionTarget.SelectedText(bedText.id), hit)
  }

  @Test
  fun testRoomAreaCalculatorMetricAndImperial() {
    val plan = SamplePlans.starterApartment()
    val rooms = RoomAreaEngine.detectRooms(plan)
    assertEquals(3, rooms.size)
    val totalSqM = rooms.sumOf { it.areaSqMeters.toDouble() }.toFloat()
    // Living (3.61*5.17 + 1.20*2.01) + Bed (3.21*3.01) + Bath (2.01*2.01) ≈ 34.77 m²
    assertEquals(34.77f, totalSqM, 0.5f)

    val metricStr = UnitFormatter.formatArea(rooms.first().areaSqMm, UnitSystem.METRIC)
    assertTrue(metricStr.endsWith("m²"))
    val imperialStr = UnitFormatter.formatArea(rooms.first().areaSqMm, UnitSystem.IMPERIAL)
    assertTrue(imperialStr.endsWith("sq ft"))
  }

  @Test
  fun testWallEndpointAndTJunctionSnapping() {
    val plan = SamplePlans.starterApartment()
    // Near (3800, 3400) endpoint
    val endSnap = GeometryMath.snapDrawPoint(
      rawPoint = Vec2(3840f, 3430f),
      anchor = null,
      walls = plan.walls,
      gridSnap = true,
      angleSnap = true,
      unitSystem = UnitSystem.METRIC,
      endpointThresholdMm = 150f,
    )
    assertEquals(SnapKind.ENDPOINT, endSnap.snapKind)
    assertEquals(3800f, endSnap.point.x, 1f)
    assertEquals(3400f, endSnap.point.y, 1f)

    // Near top wall interior (2100, 35) -> T_JUNCTION onto y=0
    val tSnap = GeometryMath.snapDrawPoint(
      rawPoint = Vec2(2100f, 35f),
      anchor = null,
      walls = plan.walls,
      gridSnap = true,
      angleSnap = true,
      unitSystem = UnitSystem.METRIC,
      endpointThresholdMm = 150f,
    )
    assertEquals(SnapKind.T_JUNCTION, tSnap.snapKind)
    assertEquals(0f, tSnap.point.y, 1f)
  }

  @Test
  fun testDxfExportAndTextOpaqueFlag() {
    val plan = SamplePlans.starterApartment()
    val rooms = RoomAreaEngine.detectRooms(plan)
    val dxf = PlanExporter.renderToDxf(plan, UnitSystem.METRIC, "Studio Concept", rooms)
    assertTrue(dxf.contains("SECTION"))
    assertTrue(dxf.contains("ENTITIES"))
    assertTrue(dxf.contains("WALLS"))
    assertTrue(dxf.contains("DOORS"))
    assertTrue(dxf.contains("WINDOWS"))
    assertTrue(dxf.contains("AREAS"))
    assertTrue(dxf.contains("EOF"))

    val opaqueLabel = TextLabel(text = "KITCHEN", position = Vec2(1000f, 1000f))
    assertTrue(opaqueLabel.hasOpaqueBackground)
    val transparentLabel = opaqueLabel.copy(hasOpaqueBackground = false)
    assertFalse(transparentLabel.hasOpaqueBackground)
  }

  @Test
  fun testSmartGuidesAndRoomBoundaryLookup() {
    val plan = SamplePlans.starterApartment()
    val rooms = RoomAreaEngine.detectRooms(plan)

    // Moving coffee table slightly off-center from sofa (x=1650) should snap back to 1650 with a VERTICAL guide
    val coffee = plan.symbols.first { it.id == "sym_coffee" }
    val guideRes = SmartGuideEngine.alignSymbolMove(
      rawCenter = Vec2(1690f, 2000f),
      movingSymbol = coffee,
      plan = plan,
      rooms = rooms,
      snapToleranceMm = 100f,
    )
    assertEquals(1650f, guideRes.snappedPoint.x, 1f)
    assertTrue(guideRes.guides.any { it.axis == GuideAxis.VERTICAL })

    // Room boundary hit-testing: point inside bedroom vs outside plan
    val bedRoom = RoomAreaEngine.findRoomAt(Vec2(4500f, 1200f), rooms)
    assertNotNull(bedRoom)
    assertEquals(3.21f * 3.01f, bedRoom!!.areaSqMeters, 0.3f)

    val outside = RoomAreaEngine.findRoomAt(Vec2(-500f, -500f), rooms)
    assertNull(outside)
  }
}
