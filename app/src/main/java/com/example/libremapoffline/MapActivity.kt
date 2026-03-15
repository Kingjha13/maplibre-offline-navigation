package com.example.libremapoffline

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import java.util.PriorityQueue
import kotlin.math.*

class MapActivity : AppCompatActivity() {

    private val TAG = "MapActivity"
    private var lastRouteStart: Point? = null
    private var lastRouteEnd: Point? = null
    private var lastToastTime = 0L
    private var lastRouteTime = 0L

    private val ROUTE_RECALC_DISTANCE_METERS = 3.0
    private val ROUTE_RECALC_INTERVAL_MS = 1500L
    private val TOAST_INTERVAL_MS = 2500L

    private lateinit var mapView: MapView
    private lateinit var locationClient: FusedLocationProviderClient

    private lateinit var routeSource: GeoJsonSource
    private var dotIndex = 0
    private lateinit var userSource: GeoJsonSource

    private val graph = mutableMapOf<Point, MutableSet<Point>>()
    private val corridorSegments = mutableListOf<Pair<Point, Point>>()

    private var userLocation: Point? = null
    private var selectedDestination: Point? = null

    private val handler = Handler(Looper.getMainLooper())
    private var animationRunning = false
    private var dashPhase = 0f

    private var intentLat: Double? = null
    private var intentLng: Double? = null

    private val SNAP_GRID = 0.00002

    private val BRIDGE_DISTANCE_METERS = 8.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.actiivity_map)

        intentLat = intent.getDoubleExtra("lat", Double.NaN).takeIf { !it.isNaN() }
        intentLng = intent.getDoubleExtra("lng", Double.NaN).takeIf { !it.isNaN() }


        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        locationClient = LocationServices.getFusedLocationProviderClient(this)

        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri("asset://style.json")) { style ->

                loadCampusData(style)
                setupUser(style)
                setupRoute(style)

                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(22.28873891102211, 73.36377677349803))
                    .zoom(18.8)
                    .build()

                if (intentLat != null && intentLng != null) {
                    val dest = Point.fromLngLat(intentLng!!, intentLat!!)
                    selectedDestination = nearestNode(snapToCorridor(dest))
                    Toast.makeText(this, "Destination selected ✅", Toast.LENGTH_SHORT).show()
                }

                startGPS()
                map.addOnMapClickListener { latLng ->
                    val screen: PointF = map.projection.toScreenLocation(latLng)
                    val features = map.queryRenderedFeatures(screen, "building-fill")

                    if (features.isNotEmpty()) {
                        val f = features[0]
                        val buildingName =
                            f.getStringProperty("Name")
                                ?: f.getStringProperty("name")
                                ?: "Building"

                        val destRaw = getPolygonCenterPoint(f)
                        if (destRaw != null) {
                            selectedDestination = nearestNode(snapToCorridor(destRaw))
                            Toast.makeText(this, "Route to: $buildingName", Toast.LENGTH_SHORT).show()

                            userLocation?.let { u ->
                                drawRoute(u, selectedDestination!!)
                            } ?: Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
            }
        }
    }

    private fun loadCampusData(style: Style) {
        val json = assets.open("linetest.geojson").bufferedReader().use { it.readText() }
        val fc = FeatureCollection.fromJson(json) ?: return

        style.addSource(GeoJsonSource("campus", fc))

        buildGraph(fc)

        style.addLayer(
            LineLayer("roads-layer", "campus")
                .withFilter(eq(geometryType(), literal("LineString")))
                .withProperties(
                    lineColor("#8E8E8E"),
                    lineWidth(6f),
                    lineJoin("round"),
                    lineCap("round")
                )
        )

        style.addLayer(
            FillLayer("building-fill", "campus")
                .withFilter(eq(geometryType(), literal("Polygon")))
                .withProperties(
                    fillColor("#CFD8DC"),
                    fillOpacity(0.75f)
                )
        )

        style.addLayer(
            LineLayer("building-outline", "campus")
                .withFilter(eq(geometryType(), literal("Polygon")))
                .withProperties(
                    lineColor("#455A64"),
                    lineWidth(2.2f)
                )
        )

        style.addLayer(
            SymbolLayer("building-label", "campus")
                .withFilter(eq(geometryType(), literal("Polygon")))
                .withProperties(
                    textField(coalesce(get("Name"), get("name"))),
                    textSize(14f),
                    textColor("#263238"),
                    textHaloColor("#FFFFFF"),
                    textHaloWidth(2f),
                    textAnchor("center"),
                    textAllowOverlap(true),
                    textIgnorePlacement(true)
                )
        )
    }

    private fun buildGraph(fc: FeatureCollection) {
        graph.clear()
        corridorSegments.clear()

        val roadNodes = mutableListOf<Point>()

        fc.features()?.forEach { feature ->
            val geom = feature.geometry()
            if (geom !is LineString) return@forEach

            val coords = geom.coordinates()
            for (i in 0 until coords.size - 1) {
                val a = normalizePoint(coords[i])
                val b = normalizePoint(coords[i + 1])

                graph.getOrPut(a) { mutableSetOf() }.add(b)
                graph.getOrPut(b) { mutableSetOf() }.add(a)

                corridorSegments.add(a to b)

                roadNodes.add(a)
                roadNodes.add(b)
            }
        }

        connectNearbyRoads(roadNodes)

        Log.d(TAG, "Graph nodes=${graph.size}, segments=${corridorSegments.size}")
    }

    private fun normalizePoint(p: Point): Point {
        val lng = (p.longitude() / SNAP_GRID).roundToInt() * SNAP_GRID
        val lat = (p.latitude() / SNAP_GRID).roundToInt() * SNAP_GRID
        return Point.fromLngLat(lng, lat)
    }
    private fun connectNearbyRoads(nodes: List<Point>) {
        if (nodes.isEmpty()) return

        val unique = nodes.distinct()

        for (i in unique.indices) {
            val a = unique[i]
            for (j in i + 1 until unique.size) {
                val b = unique[j]

                val d = distance(a, b)
                if (d <= BRIDGE_DISTANCE_METERS) {
                    graph.getOrPut(a) { mutableSetOf() }.add(b)
                    graph.getOrPut(b) { mutableSetOf() }.add(a)
                }
            }
        }
    }

    private fun setupUser(style: Style) {
        userSource = GeoJsonSource("user")
        style.addSource(userSource)

        style.addLayer(
            CircleLayer("user-layer", "user").withProperties(
                circleColor("#2962FF"),
                circleRadius(8f)
            )
        )
    }

    //    private fun setupRoute(style: Style) {
//        routeSource = GeoJsonSource("route")
//        style.addSource(routeSource)
//
//        val routeLayer = LineLayer("route-layer", "route").withProperties(
//            lineColor("#2962FF"),
//            lineWidth(8f),
//            lineJoin("round"),
//            lineCap("round"),
//            lineOpacity(1.0f),
//            lineDasharray(arrayOf(2f, 2f))
//        )
//
//        if (style.getLayer("roads-layer") != null) {
//            style.addLayerAbove(routeLayer, "roads-layer")
//        } else {
//            style.addLayer(routeLayer)
//        }
//    }
    private fun setupRoute(style: Style) {
        routeSource = GeoJsonSource("route")
        style.addSource(routeSource)

        val routeLayer = LineLayer("route-layer", "route").withProperties(
            lineColor("#1A73E8"),   // Google blue
            lineWidth(9f),
            lineJoin("round"),
            lineCap("round"),
            lineOpacity(0.95f)
            // ❌ remove dash
        )

        if (style.getLayer("road  s-layer") != null) {
            style.addLayerAbove(routeLayer, "roads-layer")
        } else {
            style.addLayer(routeLayer)
        }

        // 🔵 moving dot
        style.addLayerAbove(
            CircleLayer("route-dot", "route").withProperties(
                circleColor("#1A73E8"),
                circleRadius(6f),
                circleOpacity(1f)
            ),
            "route-layer"
        )
    }


    @SuppressLint("MissingPermission")
    private fun startGPS() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                500
            )
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1500
        ).build()

        locationClient.requestLocationUpdates(
            request,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val raw = result.lastLocation ?: return

                    val rawPoint = Point.fromLngLat(raw.longitude, raw.latitude)
                    val snapped = snapToCorridor(rawPoint)

                    userLocation = snapped
                    userSource.setGeoJson(snapped)

                    val dest = selectedDestination ?: return

                    val now = System.currentTimeMillis()

                    val movedEnough = lastRouteStart == null || distance(lastRouteStart!!, snapped) > ROUTE_RECALC_DISTANCE_METERS
                    val destChanged = lastRouteEnd == null || distance(lastRouteEnd!!, dest) > 1.0
                    val timeOk = (now - lastRouteTime) > ROUTE_RECALC_INTERVAL_MS

                    if ((movedEnough || destChanged) && timeOk) {
                        lastRouteStart = snapped
                        lastRouteEnd = dest
                        lastRouteTime = now
                        drawRoute(snapped, dest)
                    }
                }
            },
            mainLooper
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun drawRoute(start: Point, end: Point) {
        if (graph.isEmpty()) return

        val s = nearestNode(start)
        val e = nearestNode(end)

        val path = dijkstraShortestPath(s, e)

        if (path.size < 2) {
            routeSource.setGeoJson(FeatureCollection.fromFeatures(arrayOf())) // clear route

            val now = System.currentTimeMillis()
            if (now - lastToastTime > TOAST_INTERVAL_MS) {
                lastToastTime = now
            }
            return
        }

        routeSource.setGeoJson(LineString.fromLngLats(path))
        dashPhase = 0f
        animationRunning = false
        startRouteAnimation()
    }


    private fun dijkstraShortestPath(start: Point, goal: Point): List<Point> {
        val dist = mutableMapOf<Point, Double>()
        val prev = mutableMapOf<Point, Point?>()

        val pq = PriorityQueue(compareBy<Pair<Point, Double>> { it.second })

        dist[start] = 0.0
        prev[start] = null
        pq.add(start to 0.0)

        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll()

            if (u == goal) break
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue

            for (v in graph[u].orEmpty()) {
                val alt = d + distance(u, v)
                if (alt < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = alt
                    prev[v] = u
                    pq.add(v to alt)
                }
            }
        }

        if (!prev.containsKey(goal)) return emptyList()

        val path = mutableListOf<Point>()
        var cur: Point? = goal
        while (cur != null) {
            path.add(0, cur)
            cur = prev[cur]
        }
        return path
    }

    private fun nearestNode(p: Point): Point {
        return graph.keys.minByOrNull { distance(it, p) } ?: p
    }

//    private fun startRouteAnimation() {
//        if (animationRunning) return
//        animationRunning = true
//
//        handler.post(object : Runnable {
//            override fun run() {
//                dashPhase += 0.25f
//                if (dashPhase > 6f) dashPhase = 0f
//
//                mapView.getMapAsync { map ->
//                    val style = map.style ?: return@getMapAsync
//                    val layer = style.getLayer("route-layer") as? LineLayer ?: return@getMapAsync
//
//                    layer.setProperties(
//                        lineDasharray(arrayOf(2f + dashPhase, 2f))
//                    )
//                }
//
//                handler.postDelayed(this, 80)
//            }
//        })
//    }

    private fun startRouteAnimation() {
        if (animationRunning) return
        animationRunning = true

        handler.post(object : Runnable {
            override fun run() {
                mapView.getMapAsync { map ->
                    val style = map.style ?: return@getMapAsync
                    val src = style.getSourceAs<GeoJsonSource>("route") ?: return@getMapAsync

                    val geom = src.querySourceFeatures(null)
                        .firstOrNull()?.geometry() as? LineString ?: return@getMapAsync

                    val coords = geom.coordinates()
                    if (coords.isEmpty()) return@getMapAsync

                    dotIndex = (dotIndex + 1) % coords.size
                    val dotPoint = coords[dotIndex]

                    val dotSource = GeoJsonSource("route-dot-src", dotPoint)
                    if (style.getSource("route-dot-src") == null) {
                        style.addSource(dotSource)
                    } else {
                        dotSource.setGeoJson(dotPoint)
                    }
                }

                handler.postDelayed(this, 450) // 👈 smooth & slow like Google
            }
        })
    }

    private fun snapToCorridor(p: Point): Point {
        var best = p
        var min = Double.MAX_VALUE

        for ((a, b) in corridorSegments) {
            val c = nearestPointOnSegment(p, a, b)
            val d = distance(p, c)
            if (d < min) {
                min = d
                best = c
            }
        }
        return if (min < 25.0) best else p
    }

    private fun nearestPointOnSegment(p: Point, a: Point, b: Point): Point {
        val dx = b.longitude() - a.longitude()
        val dy = b.latitude() - a.latitude()

        if (dx == 0.0 && dy == 0.0) return a

        val t = ((p.longitude() - a.longitude()) * dx +
                (p.latitude() - a.latitude()) * dy) / (dx * dx + dy * dy)

        return when {
            t < 0 -> a
            t > 1 -> b
            else -> Point.fromLngLat(
                a.longitude() + t * dx,
                a.latitude() + t * dy
            )
        }
    }

    private fun getPolygonCenterPoint(feature: Feature): Point? {
        val geom = feature.geometry() ?: return null
        if (geom !is Polygon) return null

        val ring = geom.coordinates().firstOrNull() ?: return null
        if (ring.isEmpty()) return null

        var sumLng = 0.0
        var sumLat = 0.0

        ring.forEach {
            sumLng += it.longitude()
            sumLat += it.latitude()
        }

        return Point.fromLngLat(sumLng / ring.size, sumLat / ring.size)
    }

    private fun distance(a: Point, b: Point): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude() - a.latitude())
        val dLon = Math.toRadians(b.longitude() - a.longitude())
        val la1 = Math.toRadians(a.latitude())
        val la2 = Math.toRadians(b.latitude())

        val h = sin(dLat / 2).pow(2.0) +
                cos(la1) * cos(la2) * sin(dLon / 2).pow(2.0)

        return 2 * r * asin(sqrt(h))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 500) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startGPS()
            } else {
                Toast.makeText(this, "Location permission required!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
