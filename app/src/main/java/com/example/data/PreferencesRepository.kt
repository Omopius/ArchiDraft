package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "archdraft_prefs")

data class DraftingPreferences(
  val unitSystem: UnitSystem = UnitSystem.METRIC,
  val gridSnap: Boolean = true,
  val angleSnap: Boolean = true,
  val defaultWallThicknessMm: Float = 150f,
  val activePlanId: String = "starter_studio_v2",
)

class PreferencesRepository(private val context: Context) {

  private object Keys {
    val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    val GRID_SNAP = booleanPreferencesKey("grid_snap")
    val ANGLE_SNAP = booleanPreferencesKey("angle_snap")
    val WALL_THICKNESS = floatPreferencesKey("wall_thickness_mm")
    val ACTIVE_PLAN_ID = stringPreferencesKey("active_plan_id")
  }

  val preferences: Flow<DraftingPreferences> = context.dataStore.data.map { prefs ->
    val unit = runCatching {
      UnitSystem.valueOf(prefs[Keys.UNIT_SYSTEM] ?: UnitSystem.METRIC.name)
    }.getOrDefault(UnitSystem.METRIC)
    DraftingPreferences(
      unitSystem = unit,
      gridSnap = prefs[Keys.GRID_SNAP] ?: true,
      angleSnap = prefs[Keys.ANGLE_SNAP] ?: true,
      defaultWallThicknessMm = prefs[Keys.WALL_THICKNESS] ?: 150f,
      activePlanId = prefs[Keys.ACTIVE_PLAN_ID] ?: "starter_studio_v2",
    )
  }

  suspend fun setUnitSystem(unitSystem: UnitSystem) {
    context.dataStore.edit { it[Keys.UNIT_SYSTEM] = unitSystem.name }
  }

  suspend fun setGridSnap(enabled: Boolean) {
    context.dataStore.edit { it[Keys.GRID_SNAP] = enabled }
  }

  suspend fun setAngleSnap(enabled: Boolean) {
    context.dataStore.edit { it[Keys.ANGLE_SNAP] = enabled }
  }

  suspend fun setDefaultWallThickness(mm: Float) {
    context.dataStore.edit { it[Keys.WALL_THICKNESS] = mm }
  }

  suspend fun setActivePlanId(id: String) {
    context.dataStore.edit { it[Keys.ACTIVE_PLAN_ID] = id }
  }
}
