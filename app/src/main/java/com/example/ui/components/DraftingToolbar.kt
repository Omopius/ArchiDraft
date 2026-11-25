package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.DraftingTool

private fun toolIcon(tool: DraftingTool): ImageVector = when (tool) {
  DraftingTool.SELECT -> Icons.Default.NearMe
  DraftingTool.WALL -> Icons.Default.Architecture
  DraftingTool.DOOR -> Icons.Default.DoorFront
  DraftingTool.WINDOW -> Icons.Default.Window
  DraftingTool.FIXTURE -> Icons.Default.Chair
  DraftingTool.DIMENSION -> Icons.Default.SquareFoot
  DraftingTool.ROOM_LABEL -> Icons.Default.MeetingRoom
  DraftingTool.TEXT -> Icons.Default.TextFields
  DraftingTool.ERASER -> Icons.Default.AutoFixOff
}

@Composable
fun DraftingToolbar(
  activeTool: DraftingTool,
  isWideScreen: Boolean,
  onSelectTool: (DraftingTool) -> Unit,
  onOpenSymbolPicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (isWideScreen) {
    Surface(
      modifier = modifier
        .navigationBarsPadding()
        .padding(start = 12.dp, top = 80.dp, bottom = 16.dp),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 2.dp,
      shadowElevation = 4.dp,
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        DraftingTool.entries.forEach { tool ->
          ToolItemButton(
            tool = tool,
            selected = activeTool == tool,
            onClick = {
              onSelectTool(tool)
              if (tool == DraftingTool.FIXTURE) onOpenSymbolPicker()
            },
          )
        }
      }
    }
  } else {
    Surface(
      modifier = modifier
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      shape = RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
      tonalElevation = 3.dp,
      shadowElevation = 6.dp,
    ) {
      Row(
        modifier = Modifier
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        DraftingTool.entries.forEach { tool ->
          ToolItemButton(
            tool = tool,
            selected = activeTool == tool,
            onClick = {
              onSelectTool(tool)
              if (tool == DraftingTool.FIXTURE) onOpenSymbolPicker()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun ToolItemButton(
  tool: DraftingTool,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val bg = if (selected) {
    if (tool == DraftingTool.ERASER) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
  } else {
    Color.Transparent
  }
  val contentColor = if (selected) {
    if (tool == DraftingTool.ERASER) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  Column(
    modifier = Modifier
      .defaultMinSize(minWidth = 54.dp, minHeight = 52.dp)
      .widthIn(min = 56.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp)
      .testTag("tool_${tool.name.lowercase()}"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = toolIcon(tool),
      contentDescription = tool.label,
      tint = contentColor,
      modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = tool.label,
      style = MaterialTheme.typography.labelMedium,
      color = contentColor,
      maxLines = 1,
    )
  }
}
