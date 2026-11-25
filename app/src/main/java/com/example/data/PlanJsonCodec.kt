package com.example.data

import com.example.model.FloorPlanData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object PlanJsonCodec {
  private val moshi: Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

  private val adapter = moshi.adapter(FloorPlanData::class.java)

  fun toJson(plan: FloorPlanData): String = adapter.toJson(plan)

  fun fromJson(json: String): FloorPlanData? =
    runCatching { adapter.fromJson(json) }.getOrNull()
}
