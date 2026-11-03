package com.run.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WGS-84 -> GCJ-02 坐标转换的单元测试。
 *
 * 国内底图使用 GCJ-02，若不做转换，轨迹会整体偏移数百米；
 * 国外坐标则应保持原样（不做偏移）。
 */
class CoordUtilsTest {

    @Test
    fun `中国范围内需要转换`() {
        assertFalse(CoordUtils.outOfChina(39.908, 116.397))  // 北京
        assertFalse(CoordUtils.outOfChina(36.08, 120.37))    // 青岛
        assertFalse(CoordUtils.outOfChina(23.13, 113.26))    // 广州
    }

    @Test
    fun `中国范围外不转换`() {
        assertTrue(CoordUtils.outOfChina(51.5074, -0.1278))  // 伦敦
        assertTrue(CoordUtils.outOfChina(35.6762, 139.6503)) // 东京
        assertTrue(CoordUtils.outOfChina(0.0, 0.0))
    }

    @Test
    fun `北京坐标转换后偏移数百米`() {
        val lat = 39.908
        val lon = 116.397
        val (gcjLat, gcjLon) = CoordUtils.wgs84ToGcj02(lat, lon)

        val moved = LocationUtils.haversineDistanceMeters(lat, lon, gcjLat, gcjLon)
        assertTrue("偏移应明显大于 100 米，实际 $moved", moved > 100)
        assertTrue("偏移应小于 1000 米，实际 $moved", moved < 1000)
    }

    @Test
    fun `转换方向为北上东移`() {
        val (gcjLat, gcjLon) = CoordUtils.wgs84ToGcj02(39.908, 116.397)
        // 国内 GCJ-02 相对 WGS-84 的偏移方向
        assertTrue(gcjLat > 39.908)
        assertTrue(gcjLon > 116.397)
    }

    @Test
    fun `国外坐标保持原值`() {
        val (lat, lon) = CoordUtils.wgs84ToGcj02(51.5074, -0.1278)
        assertEquals(51.5074, lat, 1e-9)
        assertEquals(-0.1278, lon, 1e-9)
    }

    @Test
    fun `同一坐标多次转换结果稳定`() {
        val first = CoordUtils.wgs84ToGcj02(36.08, 120.37)
        val second = CoordUtils.wgs84ToGcj02(36.08, 120.37)
        assertEquals(first.first, second.first, 1e-12)
        assertEquals(first.second, second.second, 1e-12)
    }
}
