package com.example.commutesplit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

object TrackingState {
    // The screen watches this to show live progress.
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Not tracking")
}

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val triggerRadiusMetres = 150f

    private var tripId = 0L
    private var route: List<Waypoint> = emptyList()
    private var nextIndex = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleLocation(loc)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking(finished = false)
            return START_NOT_STICKY
        }
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        createChannel()
        val notification = buildNotification("Tracking your commute")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        scope.launch {
            val dao = AppDatabase.get(this@TrackingService).dao()
            route = dao.getWaypoints()
            if (route.size < 2) {
                TrackingState.status.value = "Add Home and Work first"
                stopTracking(finished = false)
                return@launch
            }
            tripId = dao.insertTrip(Trip(startTime = System.currentTimeMillis()))
            // Home is index 0, so the first thing to find is waypoint 1.
            nextIndex = 1
            TrackingState.running.value = true
            TrackingState.status.value = "Heading to ${route[nextIndex].name}"
            requestUpdates()
        }
    }

    @Suppress("MissingPermission")
    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun handleLocation(loc: Location) {
        if (nextIndex >= route.size) return
        val target = route[nextIndex]
        val result = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, target.lat, target.lon, result)
        val distance = result[0]

        if (distance <= triggerRadiusMetres) {
            val now = System.currentTimeMillis()
            val reached = target
            nextIndex++
            scope.launch {
                val dao = AppDatabase.get(this@TrackingService).dao()
                dao.insertSplit(
                    Split(
                        tripId = tripId,
                        waypointId = reached.id,
                        waypointName = reached.name,
                        orderIndex = reached.orderIndex,
                        timestamp = now
                    )
                )
                if (reached.isWork || nextIndex >= route.size) {
                    dao.finishTrip(tripId, now)
                    TrackingState.status.value = "Arrived at ${reached.name}"
                    stopTracking(finished = true)
                } else {
                    TrackingState.status.value = "Heading to ${route[nextIndex].name}"
                    updateNotification("Next: ${route[nextIndex].name}")
                }
            }
        } else {
            TrackingState.status.value =
                "Heading to ${target.name} (${distance.toInt()} m away)"
        }
    }

    private fun stopTracking(finished: Boolean) {
        client.removeLocationUpdates(callback)
        TrackingState.running.value = false
        if (!finished && tripId != 0L) {
            // Manual stop before arriving: save the end time so it still shows in history.
            scope.launch {
                AppDatabase.get(this@TrackingService).dao()
                    .finishTrip(tripId, System.currentTimeMillis())
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Commute tracking", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Commute Split Timer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "commute_tracking"
        const val ACTION_STOP = "com.example.commutesplit.STOP"

        fun start(context: Context) {
            val i = Intent(context, TrackingService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, TrackingService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
