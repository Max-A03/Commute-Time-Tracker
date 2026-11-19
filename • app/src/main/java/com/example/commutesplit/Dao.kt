package com.example.commutesplit

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Waypoints
    @Query("SELECT * FROM waypoints ORDER BY orderIndex ASC")
    fun observeWaypoints(): Flow<List<Waypoint>>

    @Query("SELECT * FROM waypoints ORDER BY orderIndex ASC")
    suspend fun getWaypoints(): List<Waypoint>

    @Insert
    suspend fun insertWaypoint(w: Waypoint): Long

    @Update
    suspend fun updateWaypoint(w: Waypoint)

    @Delete
    suspend fun deleteWaypoint(w: Waypoint)

    @Query("DELETE FROM waypoints WHERE isAuto = 1")
    suspend fun deleteAutoWaypoints()

    // Trips
    @Insert
    suspend fun insertTrip(t: Trip): Long

    @Query("UPDATE trips SET endTime = :end WHERE id = :tripId")
    suspend fun finishTrip(tripId: Long, end: Long)

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeTrips(): Flow<List<Trip>>

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Query("DELETE FROM splits WHERE tripId = :tripId")
    suspend fun deleteSplitsForTrip(tripId: Long)

    // Splits
    @Insert
    suspend fun insertSplit(s: Split)

    @Query("SELECT * FROM splits ORDER BY tripId DESC, orderIndex ASC")
    fun observeSplits(): Flow<List<Split>>
}
