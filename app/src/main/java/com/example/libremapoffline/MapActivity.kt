package com.example.libremapoffline

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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
    private val SNAP_GRID = 0.00002
    private val BRIDGE_DISTANCE_METERS = 8.0
    private val ROUTE_RECALC_DISTANCE_METERS = 3.0
    private val ROUTE_RECALC_INTERVAL_MS = 1500L
    private val TOAST_INTERVAL_MS = 2500L
    private val CAMPUS_CENTER = LatLng(22.28873891102211, 73.36377677349803)
    private val CAMPUS_DEFAULT_ZOOM = 18.8
    private val SNAP_TO_CORRIDOR_THRESHOLD_METERS = 25.0

    private lateinit var mapView: MapView
    private lateinit var searchBar: AutoCompleteTextView
    private lateinit var destinationCard: View
    private lateinit var tvDestName: TextView
    private lateinit var tvDestMeta: TextView
    private lateinit var btnClearRoute: ImageButton

    private var mapLibreMap: MapLibreMap? = null

    private lateinit var locationClient: FusedLocationProviderClient
    private var userLocation: Point? = null

    private lateinit var routeSource: GeoJsonSource
    private lateinit var userSource: GeoJsonSource
    private lateinit var pinSource: GeoJsonSource

    private val graph = mutableMapOf<Point, MutableSet<Point>>()
    private val corridorSegments = mutableListOf<Pair<Point, Point>>()

    private var selectedDestination: Point? = null
    private var selectedDestName: String = ""
    private var lastRouteStart: Point? = null
    private var lastRouteEnd: Point? = null
    private var lastToastTime = 0L
    private var lastRouteTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var animationRunning = false
    private var dotIndex = 0

    private val buildingMap = mutableMapOf<String, Point>()

    private var intentLat: Double? = null
    private var intentLng: Double? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.actiivity_map)

        intentLat = intent.getDoubleExtra("lat", Double.NaN).takeIf { !it.isNaN() }
        intentLng = intent.getDoubleExtra("lng", Double.NaN).takeIf { !it.isNaN() }

        mapView         = findViewById(R.id.mapView)
        searchBar       = findViewById(R.id.searchBar)
        destinationCard = findViewById(R.id.destinationCard)
        tvDestName      = findViewById(R.id.tvDestName)
        tvDestMeta      = findViewById(R.id.tvDestMeta)
        btnClearRoute   = findViewById(R.id.btnClearRoute)

        mapView.onCreate(savedInstanceState)
        locationClient = LocationServices.getFusedLocationProviderClient(this)

        btnClearRoute.setOnClickListener { clearRoute() }

        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle(Style.Builder().fromUri("asset://style.json")) { style ->

                loadCampusData(style)
                setupUser(style)
                setupRoute(style)
                setupPin(style)

                map.cameraPosition = CameraPosition.Builder()
                    .target(CAMPUS_CENTER)
                    .zoom(CAMPUS_DEFAULT_ZOOM)
                    .build()

                if (intentLat != null && intentLng != null) {
                    val dest = Point.fromLngLat(intentLng!!, intentLat!!)
                    routeTo(dest, label = "Selected location")
                }

                setupSearch()
                startGPS()

                map.addOnMapClickListener { latLng ->
                    val screen: PointF = map.projection.toScreenLocation(latLng)
                    val features = map.queryRenderedFeatures(screen, "building-fill")
                    if (features.isNotEmpty()) {
                        val feature = features[0]
                        val name = feature.getStringProperty("Name")
                            ?: feature.getStringProperty("name")
                            ?: "Building"
                        val center = getPolygonCenterPoint(feature)
                        if (center != null) routeTo(center, label = name)
                    }
                    true
                }
            }
        }
    }

    private fun showDestinationCard(name: String, destPoint: Point) {
        tvDestName.text = name
        tvDestMeta.text = "Calculating route…"

        if (destinationCard.visibility != View.VISIBLE) {
            destinationCard.visibility = View.VISIBLE
            destinationCard.translationY = 400f
            destinationCard.animate()
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        pinSource.setGeoJson(destPoint)

        userLocation?.let { frameBothPoints(it, destPoint) }
    }

    private fun updateCardMeta(distanceMeters: Double) {
        val distStr = if (distanceMeters >= 1000)
            "${"%.1f".format(distanceMeters / 1000)} km"
        else
            "${distanceMeters.toInt()} m"

        val etaSeconds = (distanceMeters / 1.2).toInt()
        val etaStr = when {
            etaSeconds < 60   -> "< 1 min walk"
            etaSeconds < 3600 -> "${etaSeconds / 60} min walk"
            else              -> "${etaSeconds / 3600} hr walk"
        }

        tvDestMeta.text = "$distStr  ·  $etaStr"
    }

    private fun hideDestinationCard() {
        destinationCard.animate()
            .translationY(400f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { destinationCard.visibility = View.GONE }
            .start()
    }

    private fun clearRoute() {
        selectedDestination = null
        selectedDestName    = ""
        lastRouteStart      = null
        lastRouteEnd        = null

        routeSource.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        pinSource.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        hideDestinationCard()

        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(CAMPUS_CENTER)
                    .zoom(CAMPUS_DEFAULT_ZOOM)
                    .build()
            ), 800
        )
    }

    private fun frameBothPoints(user: Point, dest: Point) {
        val map = mapLibreMap ?: return
        try {
            val bounds = LatLngBounds.Builder()
                .include(LatLng(user.latitude(), user.longitude()))
                .include(LatLng(dest.latitude(), dest.longitude()))
                .build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160), 900)
        } catch (e: Exception) {
            Log.w(TAG, "frameBothPoints: ${e.message}")
        }
    }


    private fun setupSearch() {
        if (buildingMap.isEmpty()) {
            Log.w(TAG, "setupSearch: buildingMap is empty — no polygon features found?")
            return
        }

        val names   = buildingMap.keys.sorted()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        searchBar.setAdapter(adapter)

        searchBar.setOnItemClickListener { _, _, position, _ ->
            val name = adapter.getItem(position) ?: return@setOnItemClickListener
            val dest = buildingMap[name] ?: return@setOnItemClickListener
            searchBar.clearFocus()
            searchBar.setText("")
            routeTo(dest, label = name)
        }

        searchBar.setOnEditorActionListener { _, _, _ ->
            val query = searchBar.text.toString().trim()
            val match = buildingMap.entries
                .firstOrNull { it.key.equals(query, ignoreCase = true) }
            if (match != null) {
                searchBar.clearFocus()
                searchBar.setText("")
                routeTo(match.value, label = match.key)
            } else if (query.isNotEmpty()) {
                Toast.makeText(this, "\"$query\" not found", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }


    private fun loadCampusData(style: Style) {
        val json = assets.open("maptest.geojson").bufferedReader().use { it.readText() }
        val fc   = FeatureCollection.fromJson(json) ?: return

        style.addSource(GeoJsonSource("campus", fc))
        buildGraph(fc)

        style.addLayer(
            LineLayer("roads-layer", "campus")
                .withFilter(eq(geometryType(), literal("LineString")))
                .withProperties(
                    lineColor("#8E8E8E"), lineWidth(6f),
                    lineJoin("round"), lineCap("round")
                )
        )
        style.addLayer(
            FillLayer("building-fill", "campus")
                .withFilter(eq(geometryType(), literal("Polygon")))
                .withProperties(fillColor("#CFD8DC"), fillOpacity(0.75f))
        )
        style.addLayer(
            LineLayer("building-outline", "campus")
                .withFilter(eq(geometryType(), literal("Polygon")))
                .withProperties(lineColor("#455A64"), lineWidth(2.2f))
        )
        style.addLayer(
            SymbolLayer("building-label", "campus")
                .withFilter(eq(geometryType(), literal("Polygon")))
                .withProperties(
                    textField(coalesce(get("Name"), get("name"))),
                    textSize(14f), textColor("#263238"),
                    textHaloColor("#FFFFFF"), textHaloWidth(2f),
                    textAnchor("center"),
                    textAllowOverlap(true), textIgnorePlacement(true)
                )
        )
    }

    private fun buildGraph(fc: FeatureCollection) {
        graph.clear(); corridorSegments.clear(); buildingMap.clear()
        val roadNodes = mutableListOf<Point>()

        fc.features()?.forEach { feature ->
            when (val geom = feature.geometry()) {
                is LineString -> {
                    val coords = geom.coordinates()
                    for (i in 0 until coords.size - 1) {
                        val a = normalizePoint(coords[i])
                        val b = normalizePoint(coords[i + 1])
                        graph.getOrPut(a) { mutableSetOf() }.add(b)
                        graph.getOrPut(b) { mutableSetOf() }.add(a)
                        corridorSegments.add(a to b)
                        roadNodes += a; roadNodes += b
                    }
                }
                is Polygon -> {
                    val name = feature.getStringProperty("Name")
                        ?: feature.getStringProperty("name")
                    if (!name.isNullOrBlank()) {
                        getPolygonCenterPoint(feature)?.let { buildingMap[name] = it }
                    }
                }
            }
        }

        connectNearbyRoads(roadNodes)
        Log.d(TAG, "Graph: nodes=${graph.size}, segments=${corridorSegments.size}, buildings=${buildingMap.size}")
    }


    private fun setupUser(style: Style) {
        userSource = GeoJsonSource("user")
        style.addSource(userSource)
        style.addLayer(
            CircleLayer("user-layer", "user").withProperties(
                circleColor("#2962FF"), circleRadius(9f),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)
            )
        )
    }

    private fun setupRoute(style: Style) {
        routeSource = GeoJsonSource("route")
        style.addSource(routeSource)

        val routeLayer = LineLayer("route-layer", "route").withProperties(
            lineColor("#1A73E8"), lineWidth(9f),
            lineJoin("round"), lineCap("round"), lineOpacity(0.95f)
        )
        if (style.getLayer("roads-layer") != null)
            style.addLayerAbove(routeLayer, "roads-layer")
        else
            style.addLayer(routeLayer)

        style.addLayerAbove(
            CircleLayer("route-dot", "route").withProperties(
                circleColor("#1A73E8"), circleRadius(6f), circleOpacity(1f)
            ),
            "route-layer"
        )
    }

    private fun setupPin(style: Style) {
        pinSource = GeoJsonSource("pin-source")
        style.addSource(pinSource)
        style.addImage("destination-pin", createPinBitmap())
        style.addLayer(
            SymbolLayer("pin-layer", "pin-source").withProperties(
                iconImage("destination-pin"),
                iconAnchor("bottom"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconSize(1.0f)
            )
        )
    }
    private fun createPinBitmap(): Bitmap {
        val size   = 80
        val bmp    = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val red    = Color.parseColor("#E53935")
        val cx     = size / 2f
        val r      = size * 0.32f

        // Stem
        val path = android.graphics.Path().apply {
            moveTo(cx - 8f, cx + r - 4f)
            lineTo(cx + 8f, cx + r - 4f)
            lineTo(cx, size.toFloat() - 2f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red })

        canvas.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red })
        canvas.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f
        })

        canvas.drawCircle(cx, cx, r * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })

        return bmp
    }


    private fun routeTo(rawDest: Point, label: String) {
        selectedDestName    = label
        selectedDestination = nearestNode(snapToCorridor(rawDest))
        showDestinationCard(label, rawDest)
        userLocation?.let { drawRoute(it, selectedDestination!!) }
            ?: run { tvDestMeta.text = "Waiting for GPS…" }
    }

    private fun drawRoute(start: Point, end: Point) {
        if (graph.isEmpty()) return

        val path = dijkstraShortestPath(nearestNode(start), nearestNode(end))

        if (path.size < 2) {
            routeSource.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
            tvDestMeta.text = "No path found"
            val now = System.currentTimeMillis()
            if (now - lastToastTime > TOAST_INTERVAL_MS) {
                lastToastTime = now
                Toast.makeText(this, "No path to $selectedDestName", Toast.LENGTH_SHORT).show()
            }
            return
        }

        routeSource.setGeoJson(LineString.fromLngLats(path))
        dotIndex = 0; animationRunning = false
        startRouteAnimation()

        val totalDist = path.zipWithNext { a, b -> distance(a, b) }.sum()
        updateCardMeta(totalDist)

        userLocation?.let { frameBothPoints(it, end) }
    }

    private fun dijkstraShortestPath(start: Point, goal: Point): List<Point> {
        val dist = mutableMapOf<Point, Double>()
        val prev = mutableMapOf<Point, Point?>()
        val pq   = PriorityQueue(compareBy<Pair<Point, Double>> { it.second })

        dist[start] = 0.0; prev[start] = null
        pq.add(start to 0.0)

        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll()
            if (u == goal) break
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue
            for (v in graph[u].orEmpty()) {
                val alt = d + distance(u, v)
                if (alt < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = alt; prev[v] = u
                    pq.add(v to alt)
                }
            }
        }

        if (!prev.containsKey(goal)) return emptyList()
        val path = mutableListOf<Point>()
        var cur: Point? = goal
        while (cur != null) { path.add(0, cur); cur = prev[cur] }
        return path
    }


    @SuppressLint("MissingPermission")
    private fun startGPS() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }

        locationClient.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500).build(),
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val raw     = result.lastLocation ?: return
                    val snapped = snapToCorridor(Point.fromLngLat(raw.longitude, raw.latitude))

                    userLocation = snapped
                    userSource.setGeoJson(snapped)

                    val dest = selectedDestination ?: return
                    val now  = System.currentTimeMillis()

                    val movedEnough = lastRouteStart == null ||
                            distance(lastRouteStart!!, snapped) > ROUTE_RECALC_DISTANCE_METERS
                    val destChanged = lastRouteEnd == null ||
                            distance(lastRouteEnd!!, dest) > 1.0
                    val timeOk = (now - lastRouteTime) > ROUTE_RECALC_INTERVAL_MS

                    if ((movedEnough || destChanged) && timeOk) {
                        lastRouteStart = snapped; lastRouteEnd = dest; lastRouteTime = now
                        drawRoute(snapped, dest)
                    }
                }
            },
            mainLooper
        )
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGPS()
            else Toast.makeText(this, "Location permission required!", Toast.LENGTH_SHORT).show()
        }
    }


    private fun startRouteAnimation() {
        if (animationRunning) return
        animationRunning = true

        handler.post(object : Runnable {
            override fun run() {
                mapView.getMapAsync { map ->
                    val style  = map.style ?: return@getMapAsync
                    val src    = style.getSourceAs<GeoJsonSource>("route") ?: return@getMapAsync
                    val coords = (src.querySourceFeatures(null)
                        .firstOrNull()?.geometry() as? LineString)
                        ?.coordinates() ?: return@getMapAsync
                    if (coords.isEmpty()) return@getMapAsync

                    dotIndex = (dotIndex + 1) % coords.size
                    val dotSource = GeoJsonSource("route-dot-src", coords[dotIndex])
                    if (style.getSource("route-dot-src") == null)
                        style.addSource(dotSource)
                    else
                        dotSource.setGeoJson(coords[dotIndex])
                }
                handler.postDelayed(this, 450)
            }
        })
    }


    private fun normalizePoint(p: Point): Point {
        val lng = (p.longitude() / SNAP_GRID).roundToInt() * SNAP_GRID
        val lat = (p.latitude()  / SNAP_GRID).roundToInt() * SNAP_GRID
        return Point.fromLngLat(lng, lat)
    }

    private fun connectNearbyRoads(nodes: List<Point>) {
        val unique = nodes.distinct()
        for (i in unique.indices) {
            for (j in i + 1 until unique.size) {
                if (distance(unique[i], unique[j]) <= BRIDGE_DISTANCE_METERS) {
                    graph.getOrPut(unique[i]) { mutableSetOf() }.add(unique[j])
                    graph.getOrPut(unique[j]) { mutableSetOf() }.add(unique[i])
                }
            }
        }
    }


    private fun snapToCorridor(p: Point): Point {
        var best = p; var min = Double.MAX_VALUE
        for ((a, b) in corridorSegments) {
            val c = nearestPointOnSegment(p, a, b)
            val d = distance(p, c)
            if (d < min) { min = d; best = c }
        }
        return if (min < SNAP_TO_CORRIDOR_THRESHOLD_METERS) best else p
    }

    private fun nearestPointOnSegment(p: Point, a: Point, b: Point): Point {
        val dx = b.longitude() - a.longitude()
        val dy = b.latitude()  - a.latitude()
        if (dx == 0.0 && dy == 0.0) return a
        val t = ((p.longitude() - a.longitude()) * dx +
                (p.latitude()  - a.latitude())  * dy) / (dx * dx + dy * dy)
        return when {
            t < 0 -> a
            t > 1 -> b
            else  -> Point.fromLngLat(a.longitude() + t * dx, a.latitude() + t * dy)
        }
    }

    private fun nearestNode(p: Point): Point =
        graph.keys.minByOrNull { distance(it, p) } ?: p

    private fun getPolygonCenterPoint(feature: Feature): Point? {
        val ring = (feature.geometry() as? Polygon)
            ?.coordinates()?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Point.fromLngLat(
            ring.sumOf { it.longitude() } / ring.size,
            ring.sumOf { it.latitude()  } / ring.size
        )
    }

    private fun distance(a: Point, b: Point): Double {
        val r    = 6_371_000.0
        val dLat = Math.toRadians(b.latitude()  - a.latitude())
        val dLon = Math.toRadians(b.longitude() - a.longitude())
        val la1  = Math.toRadians(a.latitude())
        val la2  = Math.toRadians(b.latitude())
        val h    = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }


    override fun onStart()     { super.onStart();    mapView.onStart()  }
    override fun onResume()    { super.onResume();   mapView.onResume() }
    override fun onPause()     { mapView.onPause();  super.onPause()    }
    override fun onStop()      { mapView.onStop();   super.onStop()     }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 500
    }
}
