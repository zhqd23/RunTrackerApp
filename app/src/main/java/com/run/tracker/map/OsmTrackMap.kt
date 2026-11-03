package com.run.tracker.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.run.tracker.R
import com.run.tracker.data.model.TrackPoint
import com.run.tracker.util.CoordUtils
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * 瓦片源：高德街道图。
 *
 * 选择理由（真机实测结论）：
 * - OSM 官方瓦片（tile.openstreetmap.org）在部分网络环境下直接连接失败，地图整块空白；
 * - CARTO 底图可连通，但已要求 API Key，无水印瓦片会返回 "API KEY REQUIRED" 提示图；
 * - Esri 街道图在 z16 已无国内数据，返回 "Map data not yet available" 占位图；
 * - 高德国内覆盖完整、无需 Key，代价是使用 GCJ-02 坐标，
 *   因此绘制轨迹和设置视野中心时都需经过 [CoordUtils.wgs84ToGcj02] 转换。
 */
private class AmapTileSource : OnlineTileSourceBase(
    "AMapStreet",
    3,
    18,
    256,
    ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8"
    ),
    "© 高德地图 AutoNavi"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return getBaseUrl() + "&x=$x&y=$y&z=$z"
    }
}

private val TILE_SOURCE: OnlineTileSourceBase = AmapTileSource()

/** 每张地图的版权署名覆盖物：轨迹更新会清空 overlays，因此单独保存以便重挂 */
private val copyrightOverlays = java.util.WeakHashMap<MapView, CopyrightOverlay>()

/** 创建并初始化一个 osmdroid MapView（可供多处复用的共享实例） */
fun createMapView(context: Context, interactive: Boolean = true): MapView {
    val map = MapView(context)
    map.setTileSource(TILE_SOURCE)
    map.setMultiTouchControls(interactive)
    map.isEnabled = interactive
    map.minZoomLevel = 3.0
    map.maxZoomLevel = 18.0
    map.controller.setZoom(16.0)
    // 初始视野：优先使用最后已知位置，避免地图停在 (0,0) 大西洋上显示「无数据」。
    // 底图是 GCJ-02 坐标，因此中心点同样要做一次转换。
    lastKnownLocation(context)?.let {
        val (lat, lon) = CoordUtils.wgs84ToGcj02(it.latitude, it.longitude)
        map.controller.setCenter(GeoPoint(lat, lon))
    }
    // 底图署名（瓦片提供方使用条款要求展示）
    val copyright = CopyrightOverlay(context)
    copyrightOverlays[map] = copyright
    map.overlays.add(copyright)
    return map
}

/** 取最近一次已知位置（GPS / 网络 / 被动），无权限或无缓存时返回 null */
@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): Location? {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    return providers.mapNotNull { provider ->
        try {
            manager.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }.maxByOrNull { it.time }
}

/**
 * osmdroid 地图 Compose 封装：在 MapView 上绘制跑步轨迹（Polyline + 起终点标记）。
 *
 * @param points 轨迹点列表
 * @param interactive 是否允许用户交互（详情页传 false）
 * @param autoFollow 实时页传 true：每新增 5 个点自动调整视口跟随轨迹
 * @param mapView 可复用的共享 MapView 实例；传入后本组件不再托管其生命周期（由持有者负责），
 *                避免 Tab 切换时反复重建地图导致卡顿/崩溃
 */
@Composable
fun OsmTrackMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    autoFollow: Boolean = false,
    mapView: MapView? = null
) {
    val context = LocalContext.current
    var followKey by remember { mutableIntStateOf(-1) }
    // 已绘制的点数：轨迹点未增加时不重建覆盖物（暂停、无新定位时避免每秒重绘）
    var renderedSize by remember { mutableIntStateOf(-1) }

    val map = mapView ?: remember { createMapView(context, interactive) }

    // 仅自建实例时托管生命周期：onResume/onPause 跟随 Activity，离开组合时先 onPause 再 onDetach
    if (mapView == null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> map.onResume()
                    Lifecycle.Event.ON_PAUSE -> map.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                map.onPause()
                map.onDetach()
            }
        }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            // 视图已从窗口分离时跳过绘制，避免 osmdroid 在 detach 后更新崩溃
            if (view.isAttachedToWindow && renderedSize != points.size) {
                renderedSize = points.size
                val newKey = updateTrack(view, points, autoFollow, followKey)
                if (newKey != null) followKey = newKey
            }
        }
    )
}

/**
 * 更新轨迹绘制。
 *
 * @return 需要记录的新 followKey（仅在 autoFollow 且发生视口调整时返回）
 */
private fun updateTrack(
    map: MapView,
    points: List<TrackPoint>,
    autoFollow: Boolean,
    currentFollowKey: Int
): Int? {
    map.overlays.clear()
    // 清空后重新挂上版权署名（高德要求展示）
    copyrightOverlays[map]?.let { map.overlays.add(it) }
    if (points.isEmpty()) return null

    // 底图为 GCJ-02，绘制前统一转换；数据库里保存的仍是原始 WGS-84
    val geoPoints = points.map {
        val (lat, lon) = CoordUtils.wgs84ToGcj02(it.lat, it.lon)
        GeoPoint(lat, lon)
    }

    if (geoPoints.size >= 2) {
        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.rgb(46, 125, 50)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(polyline)
    }

    // 起点与终点标记
    val startMarker = Marker(map).apply {
        position = geoPoints.first()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = map.context.getString(R.string.map_marker_start)
    }
    map.overlays.add(startMarker)

    if (geoPoints.size >= 2) {
        val endMarker = Marker(map).apply {
            position = geoPoints.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = map.context.getString(R.string.map_marker_end)
        }
        map.overlays.add(endMarker)
    }

    // 视口跟随策略
    if (autoFollow) {
        val key = points.size / 5
        if (key != currentFollowKey) {
            zoomToBounds(map, geoPoints)
            return key
        }
    } else if (points.size >= 2) {
        zoomToBounds(map, geoPoints)
    } else {
        map.controller.animateTo(geoPoints.first(), 16.0, 400L)
    }
    return null
}

private fun zoomToBounds(map: MapView, points: List<GeoPoint>) {
    if (points.size < 2) {
        map.controller.animateTo(points.first(), 16.0, 400L)
        return
    }
    val bounds = BoundingBox.fromGeoPoints(points)
    map.zoomToBoundingBox(bounds, false, 60)
    map.invalidate()
}
