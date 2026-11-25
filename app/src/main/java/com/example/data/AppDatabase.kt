package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_plans")
data class PlanEntity(
  @PrimaryKey val id: String,
  val name: String,
  val updatedAt: Long,
  val jsonContent: String,
)

@Dao
interface PlanDao {
  @Query("SELECT * FROM saved_plans ORDER BY updatedAt DESC")
  fun getAllPlans(): Flow<List<PlanEntity>>

  @Query("SELECT * FROM saved_plans WHERE id = :id LIMIT 1")
  suspend fun getPlanById(id: String): PlanEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPlan(plan: PlanEntity)

  @Query("DELETE FROM saved_plans WHERE id = :id")
  suspend fun deletePlanById(id: String)
}

@Database(entities = [PlanEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
  abstract fun planDao(): PlanDao

  companion object {
    @Volatile
    private var instance: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "archdraft_plans.db",
        ).build().also { instance = it }
      }
  }
}

class PlanRepository(private val planDao: PlanDao) {
  val allPlans: Flow<List<PlanEntity>> = planDao.getAllPlans()

  suspend fun getPlanById(id: String): PlanEntity? = planDao.getPlanById(id)

  suspend fun savePlan(plan: PlanEntity) = planDao.upsertPlan(plan)

  suspend fun deletePlan(id: String) = planDao.deletePlanById(id)
}
