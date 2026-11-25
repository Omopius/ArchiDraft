package com.example.model

import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class Vec2(
  val x: Float,
  val y: Float,
)

@JsonClass(generateAdapter = true)
data class Wall(
  val id: String = UUID.randomUUID().toString(),
  val start: Vec2,
  val end: Vec2,
  val thicknessMm: Float = 150f,
)

enum class DoorType(val label: String) {
  SINGLE_SWING("Single"),
  DOUBLE_SWING("Double"),
  SLIDING("Sliding"),
}

@JsonClass(generateAdapter = true)
data class DoorOpening(
  val id: String = UUID.randomUUID().toString(),
  val wallId: String,
  val t: Float,
  val widthMm: Float = 900f,
  val doorType: DoorType = DoorType.SINGLE_SWING,
  val swingLeft: Boolean = true,
  val flipHinge: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class WindowOpening(
  val id: String = UUID.randomUUID().toString(),
  val wallId: String,
  val t: Float,
  val widthMm: Float = 1200f,
  val sillHeightMm: Float = 900f,
)

enum class FixtureCategory(val label: String) {
  FIXTURE("Fixtures"),
  FURNITURE("Furniture"),
}

enum class SymbolType(
  val displayName: String,
  val category: FixtureCategory,
  val defaultWidthMm: Float,
  val defaultDepthMm: Float,
) {
  WC_TOILET("Toilet / WC", FixtureCategory.FIXTURE, 420f, 680f),
  WASHBASIN("Washbasin", FixtureCategory.FIXTURE, 560f, 440f),
  SHOWER_TRAY("Shower Tray", FixtureCategory.FIXTURE, 900f, 900f),
  BATHTUB("Bathtub", FixtureCategory.FIXTURE, 1700f, 750f),
  KITCHEN_SINK("Kitchen Sink", FixtureCategory.FIXTURE, 1200f, 600f),
  STOVE_HOB("Stove / Hob", FixtureCategory.FIXTURE, 600f, 600f),

  BED_SINGLE("Single Bed", FixtureCategory.FURNITURE, 1000f, 2000f),
  BED_DOUBLE("Double Bed", FixtureCategory.FURNITURE, 1400f, 2000f),
  BED_QUEEN("Queen Bed", FixtureCategory.FURNITURE, 1600f, 2050f),
  SOFA_2SEAT("2-Seat Sofa", FixtureCategory.FURNITURE, 1600f, 850f),
  SOFA_3SEAT("3-Seat Sofa", FixtureCategory.FURNITURE, 2200f, 900f),
  SOFA_LSHAPE("L-Shape Sofa", FixtureCategory.FURNITURE, 2400f, 1650f),
  DINING_TABLE("Dining Table", FixtureCategory.FURNITURE, 1800f, 950f),
  COFFEE_TABLE("Coffee Table", FixtureCategory.FURNITURE, 1200f, 650f),
  DESK_CHAIR("Desk & Chair", FixtureCategory.FURNITURE, 1400f, 750f),
  WARDROBE("Wardrobe", FixtureCategory.FURNITURE, 1800f, 600f),
}

@JsonClass(generateAdapter = true)
data class PlacedSymbol(
  val id: String = UUID.randomUUID().toString(),
  val type: SymbolType,
  val center: Vec2,
  val rotationDeg: Float = 0f,
  val widthMm: Float = type.defaultWidthMm,
  val depthMm: Float = type.defaultDepthMm,
)

@JsonClass(generateAdapter = true)
data class LinearDimension(
  val id: String = UUID.randomUUID().toString(),
  val start: Vec2,
  val end: Vec2,
  val offsetMm: Float = 450f,
)

@JsonClass(generateAdapter = true)
data class TextLabel(
  val id: String = UUID.randomUUID().toString(),
  val text: String,
  val position: Vec2,
  val fontSizeMm: Float = 220f,
  val rotationDeg: Float = 0f,
  val hasOpaqueBackground: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class FloorPlanData(
  val walls: List<Wall> = emptyList(),
  val doors: List<DoorOpening> = emptyList(),
  val windows: List<WindowOpening> = emptyList(),
  val symbols: List<PlacedSymbol> = emptyList(),
  val dimensions: List<LinearDimension> = emptyList(),
  val texts: List<TextLabel> = emptyList(),
)

enum class DraftingTool(val label: String) {
  SELECT("Select"),
  WALL("Wall"),
  DOOR("Door"),
  WINDOW("Window"),
  FIXTURE("Symbols"),
  DIMENSION("Measure"),
  ROOM_LABEL("Room"),
  TEXT("Text"),
  ERASER("Eraser"),
}

sealed interface SelectionTarget {
  val id: String

  data class SelectedWall(override val id: String) : SelectionTarget
  data class SelectedDoor(override val id: String) : SelectionTarget
  data class SelectedWindow(override val id: String) : SelectionTarget
  data class SelectedSymbol(override val id: String) : SelectionTarget
  data class SelectedDimension(override val id: String) : SelectionTarget
  data class SelectedText(override val id: String) : SelectionTarget
}

sealed interface TempDimAction {
  data class ResizeWallLength(val wallId: String, val anchorStart: Boolean) : TempDimAction
  data class OffsetWallPerpendicular(val wallId: String, val referenceWallId: String, val moveNormal: Vec2) : TempDimAction
  data class OffsetOpeningAlongWall(val openingId: String, val isDoor: Boolean, val fromStart: Boolean) : TempDimAction
  data class MoveSymbolToWallGap(val symbolId: String, val moveDirection: Vec2, val anchorEdgeWorld: Vec2) : TempDimAction
}

data class TempDimension(
  val id: String,
  val start: Vec2,
  val end: Vec2,
  val labelCenter: Vec2,
  val distanceMm: Float,
  val action: TempDimAction,
)

object DraftingPresets {
  val wallThicknessMm = listOf(100f, 150f, 200f, 230f)
  val doorWidthsMm = listOf(750f, 800f, 900f, 1200f, 1500f)
  val windowWidthsMm = listOf(600f, 900f, 1200f, 1500f, 1800f)
  val textSizesMm = listOf(150f to "Small", 220f to "Medium", 320f to "Large")
  val roomLabelPresets = listOf(
    "Living Room",
    "Bedroom",
    "Kitchen",
    "Bathroom",
    "Dining",
    "Entry",
    "Balcony",
    "Study",
  )
}
