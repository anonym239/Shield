@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import dev.shield.R
import dev.shield.data.db.HourCount
import dev.shield.ui.theme.LocalStatusColors
import java.util.Calendar

/** Requests vs. blocked, last 24 h, one bar pair per hour (Vico). */
@Composable
fun HourChart(hours: List<HourCount>) {
    val ctx = LocalContext.current
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(hours) {
        if (hours.isNotEmpty()) {
            producer.runTransaction {
                columnSeries {
                    series(hours.map { it.queries })
                    series(hours.map { it.blocked })
                }
            }
        }
    }
    val hourLabels = remember(hours) {
        hours.map { h ->
            Calendar.getInstance().apply { timeInMillis = h.hour * 3_600_000L }.get(Calendar.HOUR_OF_DAY)
        }
    }
    val bottomFormatter = remember(hourLabels) {
        CartesianValueFormatter { _, x, _ -> hourLabels.getOrNull(x.toInt())?.let { "%02d".format(it) } ?: "" }
    }
    val allowedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val blockedColor = LocalStatusColors.current.red
    val total = hours.sumOf { it.queries }
    val blocked = hours.sumOf { it.blocked }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(fill = fill(allowedColor), thickness = 5.dp),
                    rememberLineComponent(fill = fill(blockedColor), thickness = 5.dp),
                ),
            ),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = ctx.getString(R.string.cd_chart, total, blocked) },
    )
}
