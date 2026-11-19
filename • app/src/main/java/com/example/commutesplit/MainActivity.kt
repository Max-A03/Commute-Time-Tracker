package com.example.commutesplit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            this, getSharedPreferences("osm", Context.MODE_PRIVATE)
        )
        setContent {
            MaterialTheme { AppRoot() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Route", "Track", "History")
    Scaffold(
        topBar = { TopAppBar(title = { Text("Commute Split Timer") }) },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(t) },
                        icon = {}
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> RouteScreen()
                1 -> TrackScreen()
                else -> HistoryScreen()
            }
        }
    }
}

// ---------------------------------------------------------------
// ROUTE SCREEN: set Home/Work, drop pins, auto-detect
// ---------------------------------------------------------------
@Composable
fun RouteScreen() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).dao() }
    val scope = rememberCoroutineScope()
    val waypoints by dao.observeWaypoints().collectAsState(initial = emptyList())

    // What the next map tap will create: "home", "work" or "pin"
    var mode by remember { mutableStateOf("pin") }
    var message by remember { mutableStateOf("Pick a mode, then tap the map.") }
    var busy by remember { mutableStateOf(false) }
    var pendingName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = mode == "home", onClick = { mode = "home" }, label = { Text("Set Home") })
            FilterChip(selected = mode == "work", onClick = { mode = "work" }, label = { Text("Set Work") })
            FilterChip(selected = mode == "pin", onClick = { mode = "pin" }, label = { Text("Drop pin") })
        }

        if (mode == "pin") {
            OutlinedTextField(
                value = pendingName,
                onValueChange = { pendingName = it },
                label = { Text("Name for next pin (optional)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                singleLine = true
            )
        }

        Text(message, Modifier.padding(8.dp), fontSize = 13.sp)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(11.0)
                        // Start on Leeds; it will jump to your Home once set.
                        controller.setCenter(GeoPoint(53.8008, -1.5491))
                    }
                },
                update = { map ->
                    map.overlays.clear()
                    waypoints.forEach { w ->
                        val m = Marker(map)
                        m.position = GeoPoint(w.lat, w.lon)
                        m.title = "${w.orderIndex}. ${w.name}"
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        map.overlays.add(m)
                    }
                    val receiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            scope.launch {
                                handleMapTap(dao, mode, pendingName, p.latitude, p.longitude)
                                message = when (mode) {
                                    "home" -> "Home set."
                                    "work" -> "Work set."
                                    else -> "Pin added."
                                }
                                if (mode == "pin") pendingName = ""
                            }
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    }
                    map.overlays.add(0, MapEventsOverlay(receiver))
                    map.invalidate()
                }
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    val home = waypoints.firstOrNull { it.isHome }
                    val work = waypoints.firstOrNull { it.isWork }
                    if (home == null || work == null) {
                        message = "Set Home and Work first."
                    } else {
                        busy = true
                        message = "Looking for towns and villages..."
                        scope.launch {
                            try {
                                val places = AutoDetect.findPlaces(home.lat, home.lon, work.lat, work.lon)
                                dao.deleteAutoWaypoints()
                                places.forEach {
                                    dao.insertWaypoint(
                                        Waypoint(name = it.name, lat = it.lat, lon = it.lon, orderIndex = 0, isAuto = true)
                                    )
                                }
                                renumber(dao)
                                message = "Found ${places.size} places. Delete any you don't pass."
                            } catch (e: Exception) {
                                message = "Auto-detect failed. Check your internet connection."
                            }
                            busy = false
                        }
                    }
                }
            ) { Text("Auto-detect") }
        }

        LazyColumn(Modifier.heightIn(max = 180.dp).padding(horizontal = 8.dp)) {
            items(waypoints, key = { it.id }) { w ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val label = when {
                        w.isHome -> "Home"
                        w.isWork -> "Work"
                        w.isAuto -> "Auto"
                        else -> "Pin"
                    }
                    Text("${w.orderIndex}. ${w.name} ($label)", Modifier.weight(1f))
                    if (!w.isHome && !w.isWork) {
                        TextButton(onClick = { scope.launch { dao.deleteWaypoint(w); renumber(dao) } }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun handleMapTap(dao: AppDao, mode: String, name: String, lat: Double, lon: Double) {
    val existing = dao.getWaypoints()
    when (mode) {
        "home" -> {
            existing.filter { it.isHome }.forEach { dao.deleteWaypoint(it) }
            dao.insertWaypoint(Waypoint(name = "Home", lat = lat, lon = lon, orderIndex = 0, isHome = true))
        }
        "work" -> {
            existing.filter { it.isWork }.forEach { dao.deleteWaypoint(it) }
            dao.insertWaypoint(Waypoint(name = "Work", lat = lat, lon = lon, orderIndex = 0, isWork = true))
        }
        else -> {
            val finalName = name.ifBlank { "Pin ${existing.count { !it.isHome && !it.isWork } + 1}" }
            dao.insertWaypoint(Waypoint(name = finalName, lat = lat, lon = lon, orderIndex = 0))
        }
    }
    renumber(dao)
}

// Puts waypoints in driving order: Home first, Work last, and everything else
// sorted by how far along the Home-to-Work line it sits.
private suspend fun renumber(dao: AppDao) {
    val all = dao.getWaypoints()
    val home = all.firstOrNull { it.isHome }
    val work = all.firstOrNull { it.isWork }
    val middle = all.filter { !it.isHome && !it.isWork }

    val sortedMiddle = if (home != null && work != null) {
        val dx = work.lon - home.lon
        val dy = work.lat - home.lat
        val lenSq = dx * dx + dy * dy
        middle.sortedBy {
            if (lenSq == 0.0) 0.0 else ((it.lon - home.lon) * dx + (it.lat - home.lat) * dy) / lenSq
        }
    } else middle

    val ordered = listOfNotNull(home) + sortedMiddle + listOfNotNull(work)
    ordered.forEachIndexed { i, w -> dao.updateWaypoint(w.copy(orderIndex = i)) }
}

// ---------------------------------------------------------------
// TRACK SCREEN: big Start/Stop button and live status
// ---------------------------------------------------------------
@Composable
fun TrackScreen() {
    val context = LocalContext.current
    val running by TrackingState.running.collectAsState()
    val status by TrackingState.status.collectAsState()
    var permissionMessage by remember { mutableStateOf("") }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Background location is a bonus; the foreground service works without it. */ }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionMessage = ""
            TrackingService.start(context)
            if (Build.VERSION.SDK_INT >= 29) {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            permissionMessage = "Location permission is needed to time your commute."
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(status, fontSize = 20.sp)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (running) {
                    TrackingService.stop(context)
                } else {
                    val hasFine = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasFine) {
                        TrackingService.start(context)
                    } else {
                        val perms = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        foregroundLauncher.launch(perms.toTypedArray())
                    }
                }
            },
            modifier = Modifier.size(width = 240.dp, height = 96.dp)
        ) {
            Text(if (running) "STOP" else "START COMMUTE", fontSize = 22.sp)
        }
        if (permissionMessage.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(permissionMessage)
        }
    }
}

// ---------------------------------------------------------------
// HISTORY SCREEN: every trip, each leg's time, and averages
// ---------------------------------------------------------------
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).dao() }
    val scope = rememberCoroutineScope()
    val trips by dao.observeTrips().collectAsState(initial = emptyList())
    val splits by dao.observeSplits().collectAsState(initial = emptyList())

    val dateFormat = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK) }

    // Build legs for every trip: time between consecutive splits, starting from trip start.
    data class Leg(val label: String, val seconds: Long)
    val legsByTrip = trips.associate { trip ->
        val tripSplits = splits.filter { it.tripId == trip.id }.sortedBy { it.orderIndex }
        var previousTime = trip.startTime
        var previousName = "Home"
        val legs = tripSplits.map { s ->
            val leg = Leg("$previousName to ${s.waypointName}", (s.timestamp - previousTime) / 1000)
            previousTime = s.timestamp
            previousName = s.waypointName
            leg
        }
        trip.id to legs
    }

    // Average seconds per leg label across all trips.
    val averages = legsByTrip.values.flatten()
        .groupBy { it.label }
        .mapValues { (_, list) -> list.map { it.seconds }.average().toLong() }

    fun fmt(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        if (trips.isEmpty()) {
            item { Text("No trips yet. Go to Track and press Start on your next commute.") }
        }

        if (averages.isNotEmpty()) {
            item {
                Text("Average per leg", fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
            }
            items(averages.entries.toList()) { (label, avg) ->
                Text("$label: ${fmt(avg)}")
            }
            item { Spacer(Modifier.height(16.dp)); HorizontalDivider() }
        }

        items(trips, key = { it.id }) { trip ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                val total = trip.endTime?.let { (it - trip.startTime) / 1000 }
                Text(
                    dateFormat.format(Date(trip.startTime)) +
                        (total?.let { "  (total ${fmt(it)})" } ?: "  (unfinished)"),
                    fontSize = 16.sp
                )
                legsByTrip[trip.id].orEmpty().forEach { leg ->
                    val avg = averages[leg.label]
                    // Flag any leg that is more than 20% slower than its average.
                    val slow = avg != null && leg.seconds > avg * 1.2
                    Text(
                        "  ${leg.label}: ${fmt(leg.seconds)}" + if (slow) "  (slower than usual)" else "",
                        fontSize = 14.sp
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        dao.deleteSplitsForTrip(trip.id)
                        dao.deleteTrip(trip.id)
                    }
                }) { Text("Delete trip") }
                HorizontalDivider()
            }
        }
    }
}
