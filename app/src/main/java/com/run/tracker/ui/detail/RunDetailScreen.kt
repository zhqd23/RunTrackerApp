package com.run.tracker.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.run.tracker.R
import com.run.tracker.data.db.RunRecordEntity
import com.run.tracker.map.OsmTrackMap
import com.run.tracker.util.FormatUtils

/**
 * 跑步详情页：显示单次跑步的轨迹地图与各项统计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    runId: Long,
    onBack: () -> Unit,
    viewModel: RunDetailViewModel = viewModel()
) {
    LaunchedEffect(runId) { viewModel.load(runId) }
    val run by viewModel.run.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val current = run
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.detail_loading))
            }
        } else {
            DetailContent(
                run = current,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
private fun DetailContent(run: RunRecordEntity, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 轨迹地图
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (run.trackPoints.size >= 2) {
                OsmTrackMap(
                    points = run.trackPoints,
                    modifier = Modifier.fillMaxSize(),
                    interactive = false
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.detail_no_track),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 基本信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = FormatUtils.formatDateTime(run.startTimeMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.detail_distance_value,
                        FormatUtils.formatKm(run.distanceMeters)
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(
                        R.string.detail_duration_value,
                        FormatUtils.formatDuration(run.durationMs)
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(
                        R.string.detail_pace_value,
                        FormatUtils.formatPace(run.avgSpeedMs)
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // 详细统计网格
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailStat(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.detail_avg_speed),
                value = FormatUtils.formatKmh(run.avgSpeedMs) +
                        " " + stringResource(R.string.unit_kmh_short)
            )
            DetailStat(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.detail_max_speed),
                value = FormatUtils.formatKmh(run.maxSpeedMs) +
                        " " + stringResource(R.string.unit_kmh_short)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailStat(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.detail_avg_hr),
                value = "${run.avgHeartRate} ${stringResource(R.string.unit_bpm)}"
            )
            DetailStat(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.detail_max_hr),
                value = "${run.maxHeartRate} ${stringResource(R.string.unit_bpm)}"
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailStat(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.detail_avg_cadence),
                value = "${run.avgCadence} ${stringResource(R.string.unit_spm)}"
            )
            DetailStat(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.detail_calories),
                value = String.format(java.util.Locale.US, "%.0f", run.calories) +
                        " " + stringResource(R.string.unit_kcal_short)
            )
        }
    }
}

@Composable
private fun DetailStat(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
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
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
