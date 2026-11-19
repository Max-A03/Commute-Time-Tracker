package com.example.commutesplit

import androidx.room.Entity
import androidx.room.PrimaryKey

// A waypoint on your route, in the order you pass it.
// Home is orderIndex 0 and Work is the highest orderIndex.
@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val orderIndex: Int,
    val isAuto: Boolean = false,
    val isHome: Boolean = false,
    val isWork: Boolean = false
