package com.example.model

object SamplePlans {
  fun starterApartment(): FloorPlanData {
    // 7200mm x 5400mm modern studio/1BR concept centered around (0,0)..(7200,5400)
    val walls = listOf(
      // Exterior perimeter (230mm)
      Wall(id = "w_top", start = Vec2(0f, 0f), end = Vec2(7200f, 0f), thicknessMm = 230f),
      Wall(id = "w_right", start = Vec2(7200f, 0f), end = Vec2(7200f, 5400f), thicknessMm = 230f),
      Wall(id = "w_bottom", start = Vec2(7200f, 5400f), end = Vec2(0f, 5400f), thicknessMm = 230f),
      Wall(id = "w_left", start = Vec2(0f, 5400f), end = Vec2(0f, 0f), thicknessMm = 230f),
      // Interior partitions (150mm)
      Wall(id = "w_bed_div", start = Vec2(3800f, 0f), end = Vec2(3800f, 3400f), thicknessMm = 150f),
      Wall(id = "w_bath_h", start = Vec2(3800f, 3200f), end = Vec2(7200f, 3200f), thicknessMm = 150f),
      Wall(id = "w_bath_v", start = Vec2(5000f, 3200f), end = Vec2(5000f, 5400f), thicknessMm = 150f),
    )

    val doors = listOf(
      // Main entrance door on bottom wall
      DoorOpening(
        id = "d_entry",
        wallId = "w_bottom",
        t = 0.84f,
        widthMm = 900f,
        doorType = DoorType.SINGLE_SWING,
        swingLeft = true,
        flipHinge = false,
      ),
      // Bedroom door
      DoorOpening(
        id = "d_bed",
        wallId = "w_bed_div",
        t = 0.78f,
        widthMm = 800f,
        doorType = DoorType.SINGLE_SWING,
        swingLeft = false,
        flipHinge = false,
      ),
      // Bathroom door
      DoorOpening(
        id = "d_bath",
        wallId = "w_bath_v",
        t = 0.30f,
        widthMm = 750f,
        doorType = DoorType.SINGLE_SWING,
        swingLeft = true,
        flipHinge = true,
      ),
    )

    val windows = listOf(
      // Living room wide window
      WindowOpening(
        id = "win_living",
        wallId = "w_top",
        t = 0.27f,
        widthMm = 1800f,
        sillHeightMm = 600f,
      ),
      // Bedroom window
      WindowOpening(
        id = "win_bed",
        wallId = "w_right",
        t = 0.29f,
        widthMm = 1500f,
        sillHeightMm = 900f,
      ),
      // Kitchenette side window
      WindowOpening(
        id = "win_kitchen",
        wallId = "w_left",
        t = 0.55f,
        widthMm = 1200f,
        sillHeightMm = 1050f,
      ),
    )

    val symbols = listOf(
      // Living room sofa + coffee table
      PlacedSymbol(
        id = "sym_sofa",
        type = SymbolType.SOFA_3SEAT,
        center = Vec2(1650f, 920f),
        rotationDeg = 0f,
        widthMm = 2200f,
        depthMm = 900f,
      ),
      PlacedSymbol(
        id = "sym_coffee",
        type = SymbolType.COFFEE_TABLE,
        center = Vec2(1650f, 1950f),
        rotationDeg = 0f,
        widthMm = 1200f,
        depthMm = 650f,
      ),
      // Dining table
      PlacedSymbol(
        id = "sym_dining",
        type = SymbolType.DINING_TABLE,
        center = Vec2(2100f, 3850f),
        rotationDeg = 0f,
        widthMm = 1600f,
        depthMm = 900f,
      ),
      // Kitchen block along left wall
      PlacedSymbol(
        id = "sym_ksink",
        type = SymbolType.KITCHEN_SINK,
        center = Vec2(420f, 2900f),
        rotationDeg = 90f,
        widthMm = 1200f,
        depthMm = 600f,
      ),
      PlacedSymbol(
        id = "sym_stove",
        type = SymbolType.STOVE_HOB,
        center = Vec2(420f, 3950f),
        rotationDeg = 90f,
        widthMm = 600f,
        depthMm = 600f,
      ),
      // Bedroom Queen bed + wardrobe
      PlacedSymbol(
        id = "sym_bed",
        type = SymbolType.BED_QUEEN,
        center = Vec2(5650f, 1220f),
        rotationDeg = 0f,
        widthMm = 1600f,
        depthMm = 2050f,
      ),
      PlacedSymbol(
        id = "sym_wardrobe",
        type = SymbolType.WARDROBE,
        center = Vec2(4190f, 1200f),
        rotationDeg = 90f,
        widthMm = 1800f,
        depthMm = 600f,
      ),
      // Bathroom fixtures
      PlacedSymbol(
        id = "sym_shower",
        type = SymbolType.SHOWER_TRAY,
        center = Vec2(6620f, 4820f),
        rotationDeg = 0f,
        widthMm = 900f,
        depthMm = 900f,
      ),
      PlacedSymbol(
        id = "sym_wc",
        type = SymbolType.WC_TOILET,
        center = Vec2(5750f, 3660f),
        rotationDeg = 0f,
        widthMm = 420f,
        depthMm = 680f,
      ),
      PlacedSymbol(
        id = "sym_basin",
        type = SymbolType.WASHBASIN,
        center = Vec2(6580f, 3550f),
        rotationDeg = 0f,
        widthMm = 560f,
        depthMm = 440f,
      ),
    )

    // Exterior dimension chains showing inside-to-inside ("in-to-in") clear widths of each space
    val dimensions = listOf(
      // Top exterior chain (in-to-in): Living/Kitchen (3.61 m) & Bedroom (3.21 m)
      LinearDimension(
        id = "dim_ext_top_living",
        start = Vec2(115f, 115f),
        end = Vec2(3725f, 115f),
        offsetMm = -640f,
      ),
      LinearDimension(
        id = "dim_ext_top_bed",
        start = Vec2(3875f, 115f),
        end = Vec2(7085f, 115f),
        offsetMm = -640f,
      ),
      // Right exterior chain (in-to-in): Bedroom (3.01 m) & Bath (2.01 m)
      LinearDimension(
        id = "dim_ext_right_bed",
        start = Vec2(7085f, 115f),
        end = Vec2(7085f, 3125f),
        offsetMm = -640f,
      ),
      LinearDimension(
        id = "dim_ext_right_bath",
        start = Vec2(7085f, 3275f),
        end = Vec2(7085f, 5285f),
        offsetMm = -640f,
      ),
      // Bottom exterior chain (in-to-in): Bath (2.01 m) & Living/Dining (4.81 m)
      LinearDimension(
        id = "dim_ext_bottom_bath",
        start = Vec2(7085f, 5285f),
        end = Vec2(5075f, 5285f),
        offsetMm = -640f,
      ),
      LinearDimension(
        id = "dim_ext_bottom_dining",
        start = Vec2(4925f, 5285f),
        end = Vec2(115f, 5285f),
        offsetMm = -640f,
      ),
      // Left exterior chain (in-to-in): Living/Kitchen full clear depth (5.17 m)
      LinearDimension(
        id = "dim_ext_left_living",
        start = Vec2(115f, 5285f),
        end = Vec2(115f, 115f),
        offsetMm = -640f,
      ),
    )

    val texts = listOf(
      TextLabel(
        id = "txt_living",
        text = "LIVING / DINING",
        position = Vec2(2150f, 2750f),
        fontSizeMm = 220f,
      ),
      TextLabel(
        id = "txt_bedroom",
        text = "BEDROOM",
        position = Vec2(5650f, 2650f),
        fontSizeMm = 220f,
      ),
      TextLabel(
        id = "txt_bath",
        text = "BATH",
        position = Vec2(5750f, 4650f),
        fontSizeMm = 200f,
      ),
    )

    return FloorPlanData(
      walls = walls,
      doors = doors,
      windows = windows,
      symbols = symbols,
      dimensions = dimensions,
      texts = texts,
    )
  }
}
