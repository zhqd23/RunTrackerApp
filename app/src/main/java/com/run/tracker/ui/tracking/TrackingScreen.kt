package com.run.tracker.ui.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.run.tracker.R
import com.run.tracker.data.model.RunUiState
import com.run.tracker.map.OsmTrackMap
import com.run.tracker.service.HeartRateSource
import com.run.tracker.service.RunState
import com.run.tracker.ui.theme.BlueAccent
import com.run.tracker.ui.theme.GreenLight
import com.run.tracker.ui.theme.OrangeAccent
import com.run.tracker.ui.theme.RedAccent
import com.run.tracker.util.FormatUtils
import org.osmdroid.views.MapView

/**
 * 实时运动页：
 * - 实时显示心率 / 距离 / 步频 / 平均速度 / 即时速度 五项数据；
 * - 地图实时绘制行进轨迹；
 * - 支持开始 / 暂停 / 继续 / 结束。
 */
@Composable
fun TrackingScreen(
    mapView: MapView? = null,
    viewModel: TrackingViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 顶部：心率来源 + 计时
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeartRateSourceChip(uiState.heartRateSource)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = FormatUtils.formatDuration(uiState.durationMs),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 五项实时指标：心率 / 距离 / 步频
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tracking_hr),
                value = "${uiState.heartRate}",
                unit = stringResource(R.string.unit_bpm),
                accent = if (uiState.heartRate > 0) RedAccent else Color.Gray
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tracking_distance),
                value = FormatUtils.formatKmShort(uiState.distanceMeters),
                unit = stringResource(R.string.unit_km_short),
                accent = MaterialTheme.colorScheme.primary
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tracking_cadence),
                value = "${uiState.cadence}",
                unit = stringResource(R.string.unit_spm),
                accent = BlueAccent
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 平均速度 / 即时速度
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tracking_avg_speed),
                value = FormatUtils.formatKmh(uiState.avgSpeedMs),
                unit = stringResource(R.string.unit_kmh_short),
                accent = MaterialTheme.colorScheme.primary
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tracking_current_speed),
                value = FormatUtils.formatKmh(uiState.currentSpeedMs),
                unit = stringResource(R.string.unit_kmh_short),
                accent = OrangeAccent
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 地图：实时轨迹
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                OsmTrackMap(
                    points = uiState.points,
                    modifier = Modifier.fillMaxSize(),
                    autoFollow = true,
                    mapView = mapView
                )
                if (uiState.state == RunState.IDLE || uiState.points.isEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        color = Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(
                                    if (uiState.state == RunState.IDLE) {
                                        R.string.tracking_map_hint_idle
                                    } else {
                                        R.string.tracking_map_hint_waiting_gps
                                    }
                                ),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 控制按钮
        ControlButtons(
            state = uiState.state,
            onStart = { viewModel.start() },
            onPause = { viewModel.pause() },
            onResume = { viewModel.resume() },
            onFinish = { viewModel.finish() }
        )
    }
}

@Composable
private fun HeartRateSourceChip(source: HeartRateSource) {
    val (textRes, color) = when (source) {
        HeartRateSource.IDLE -> R.string.tracking_heartrate_source_idle to Color.Gray
        HeartRateSource.SEARCHING -> R.string.tracking_heartrate_source_searching to BlueAccent
        HeartRateSource.BLE -> R.string.tracking_heartrate_source_ble to GreenLight
        HeartRateSource.SIMULATED -> R.string.tracking_heartrate_source_sim to OrangeAccent
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(
            text = stringResource(textRes),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ControlButtons(
    state: RunState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            RunState.IDLE -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_start),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            RunState.RUNNING -> {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_pause),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = RedAccent
                    )
                ) {
                    Text(
                        text = stringResource(R.string.tracking_finish),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            RunState.PAUSED -> {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_resume),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_finish),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
