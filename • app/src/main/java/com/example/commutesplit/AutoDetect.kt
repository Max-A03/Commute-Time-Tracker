package com.example.commutesplit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AutoDetect {

    private val http = OkHttpClient()

    // How far either side of the straight Home-to-Work line a place can be, in metres.
    private const val CORRIDOR_METRES = 3000.0

    data class Place(val name: String, val lat: Double, val lon: Double, val progress: Double)

    suspend fun findPlaces(
        homeLat: Double, homeLon: Double,
        workLat: Double, workLon: Double
    ): List<Place> = withContext(Dispatchers.IO) {
        val south = min(homeLat, workLat) - 0.05
        val north = max(homeLat, workLat) + 0.05
        val west = min(homeLon, workLon) - 0.05
        val east = max(homeLon, workLon) + 0.05

        val query = """
            [out:json][timeout:25];
            node["place"~"^(town|village|hamlet|suburb)$"]["name"]($south,$west,$north,$east);
            out;
        """.trimIndent()

        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(FormBody.Builder().add("data", query).build())
            .build()

        val body = http.newCall(request).execute().use { it.body?.string() } ?: return@withContext emptyList()
        val elements = JSONObject(body).getJSONArray("elements")

        val results = mutableListOf<Place>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val name = e.getJSONObject("tags").optString("name", "")
            if (name.isEmpty()) continue
            val lat = e.getDouble("lat")
            val lon = e.getDouble("lon")

            val (sideways, progress) = project(lat, lon, homeLat, homeLon, workLat, workLon)
            // Keep places near the line and between the two ends.
            if (sideways <= CORRIDOR_METRES && progress in 0.03..0.97) {
                results.add(Place(name, lat, lon, progress))
            }
        }
        results.sortedBy { it.progress }
    }

    // Returns (sideways distance from the Home-Work line in metres, progress 0..1 along it).
    private fun project(
        lat: Double, lon: Double,
        aLat: Double, aLon: Double, bLat: Double, bLon: Double
    ): Pair<Double, Double> {
        val metresPerDegLat = 111_320.0
        val metresPerDegLon = 111_320.0 * cos(Math.toRadians(aLat))

        val px = (lon - aLon) * metresPerDegLon
        val py = (lat - aLat) * metresPerDegLat
        val bx = (bLon - aLon) * metresPerDegLon
        val by = (bLat - aLat) * metresPerDegLat

        val lenSq = bx * bx + by * by
        if (lenSq == 0.0) return Pair(Double.MAX_VALUE, 0.0)

        val t = (px * bx + py * by) / lenSq
        val cx = t * bx
        val cy = t * by
        val sideways = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        return Pair(abs(sideways), t)
    }
}
