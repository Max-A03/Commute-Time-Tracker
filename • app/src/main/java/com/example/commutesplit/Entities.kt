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
)

// One commute from pressing Start to arriving at work.
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null
)

// The time you passed a particular waypoint on a particular trip.
@Entity(tableName = "splits")
data class Split(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val waypointId: Long,
    val waypointName: String,
    val orderIndex: Int,
    val timestamp: Long
)
