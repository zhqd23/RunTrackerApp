package com.run.tracker.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.run.tracker.R
import com.run.tracker.data.db.RunStatsRow
import com.run.tracker.data.repository.UserProfileStore
import com.run.tracker.util.FormatUtils
import com.run.tracker.util.PeriodUtils
import java.util.Locale

/**
 * 统计页：累计统计 + 周报 / 月报周期统计。
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val stats by viewModel.stats.collectAsState()
    val tab by viewModel.tab.collectAsState()
    val periodStats by viewModel.periodStats.collectAsState()
    val periodRange by viewModel.periodRange.collectAsState()

    val context = LocalContext.current
    val profileStore = remember(context) { UserProfileStore(context) }
    var weightKg by remember { mutableStateOf(profileStore.weightKg) }
    var showWeightDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.stats_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showWeightDialog = true }) {
                Text(
                    text = stringResource(
                        R.string.stats_weight_entry,
                        formatWeight(weightKg)
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        TabRow(
            selectedTabIndex = tab.ordinal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            StatsTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { viewModel.selectTab(t) },
                    text = { Text(stringResource(t.labelRes)) }
                )
            }
        }

        when (tab) {
            StatsTab.TOTAL -> {
                if (stats == null || stats!!.totalCount == 0) {
                    EmptyHint(stringResource(R.string.stats_empty_hint))
                } else {
                    TotalStatsContent(stats!!)
                }
            }
            StatsTab.WEEK, StatsTab.MONTH -> {
                val title = stringResource(
                    if (tab == StatsTab.WEEK) R.string.stats_week else R.string.stats_month
                )
                val rangeText = if (periodRange.first > 0) {
                    PeriodUtils.formatRangeText(periodRange.first, periodRange.second)
                } else ""
                if (periodStats == null || periodStats!!.totalCount == 0) {
                    EmptyHint(stringResource(R.string.stats_period_empty, title, rangeText))
                } else {
                    PeriodStatsContent(
                        title = title,
                        rangeText = rangeText,
                        stats = periodStats!!
                    )
                }
            }
        }
    }

    if (showWeightDialog) {
        WeightDialog(
            initialWeight = weightKg,
            onDismiss = { showWeightDialog = false },
            onConfirm = { newWeight ->
                weightKg = newWeight
                // 存体重并重算历史卡路里，避免新旧记录口径不一致
                viewModel.saveWeight(newWeight)
                showWeightDialog = false
            }
        )
    }
}

/** 体重输入：整数不带小数点，避免显示成 65.0 */
private fun formatWeight(kg: Double): String =
    if (kg % 1.0 == 0.0) kg.toInt().toString() else String.format(Locale.US, "%.1f", kg)

@Composable
private fun WeightDialog(
    initialWeight: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var text by remember { mutableStateOf(formatWeight(initialWeight)) }
    val parsed = text.toDoubleOrNull()
    val valid = parsed != null &&
        parsed in UserProfileStore.MIN_WEIGHT_KG..UserProfileStore.MAX_WEIGHT_KG

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.weight_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() || it == '.' }
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.weight_dialog_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = text.isNotEmpty() && !valid
                )
                Text(
                    text = stringResource(R.string.weight_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid
            ) { Text(stringResource(R.string.weight_dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 累计统计内容 */
@Composable
private fun TotalStatsContent(stats: RunStatsRow) {
    val avgSpeedMs = if (stats.totalDurationMs > 0) {
        stats.totalDistanceMeters / (stats.totalDurationMs / 1000.0)
    } else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 累计跑量
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.stats_total_distance),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = FormatUtils.formatKm(stats.totalDistanceMeters),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = " ${stringResource(R.string.unit_km_short)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // 次数 / 时长 / 配速
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_total_count),
                value = "${stats.totalCount}",
                unit = stringResource(R.string.stats_unit_times)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_total_duration),
                value = FormatUtils.formatDuration(stats.totalDurationMs),
                unit = ""
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_avg_pace),
                value = FormatUtils.formatPace(avgSpeedMs),
                unit = stringResource(R.string.unit_per_km)
            )
        }

        // 平均心率 / 总卡路里
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_avg_heartrate),
                value = "${stats.avgHeartRate.toInt()}",
                unit = stringResource(R.string.unit_bpm)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_total_kcal),
                value = String.format(java.util.Locale.US, "%.0f", stats.totalCalories),
                unit = stringResource(R.string.unit_kcal_short)
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** 周报 / 月报周期统计内容 */
@Composable
private fun PeriodStatsContent(
    title: String,
    rangeText: String,
    stats: RunStatsRow
) {
    val avgSpeedMs = if (stats.totalDurationMs > 0) {
        stats.totalDistanceMeters / (stats.totalDurationMs / 1000.0)
    } else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 周期跑量
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(
                        R.string.stats_period_distance_title,
                        title,
                        rangeText
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = FormatUtils.formatKm(stats.totalDistanceMeters),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = " ${stringResource(R.string.unit_km_short)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // 次数 / 时长 / 配速
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_period_count),
                value = "${stats.totalCount}",
                unit = stringResource(R.string.stats_unit_times)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_period_duration),
                value = FormatUtils.formatDuration(stats.totalDurationMs),
                unit = ""
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_avg_pace),
                value = FormatUtils.formatPace(avgSpeedMs),
                unit = stringResource(R.string.unit_per_km)
            )
        }

        // 平均心率 / 卡路里
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_avg_heartrate),
                value = "${stats.avgHeartRate.toInt()}",
                unit = stringResource(R.string.unit_bpm)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_kcal),
                value = String.format(java.util.Locale.US, "%.0f", stats.totalCalories),
                unit = stringResource(R.string.unit_kcal_short)
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "$label $unit".trim(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
