package com.run.tracker.map

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.run.tracker.R
import com.run.tracker.data.model.TrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** 创建并初始化一个 osmdroid MapView（可供多处复用的共享实例） */
fun createMapView(context: Context, interactive: Boolean = true): MapView =
    MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(interactive)
        isEnabled = interactive
        minZoomLevel = 3.0
        maxZoomLevel = 20.0
        controller.setZoom(16.0)
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
    if (points.isEmpty()) return null

    val geoPoints = points.map { GeoPoint(it.lat, it.lon) }

    if (geoPoints.size >= 2) {
        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.rgb(46, 125, 50)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(polyline)
    }

    // 起点与终点标记
    val start = points.first()
    val startMarker = Marker(map).apply {
        position = GeoPoint(start.lat, start.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = map.context.getString(R.string.map_marker_start)
    }
    map.overlays.add(startMarker)

    if (points.size >= 2) {
        val end = points.last()
        val endMarker = Marker(map).apply {
            position = GeoPoint(end.lat, end.lon)
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
