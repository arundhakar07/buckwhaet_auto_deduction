/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */
package com.danilkinkin.buckwheat.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.ui.colorOnEditor
import com.danilkinkin.buckwheat.ui.isNightMode
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class HistoryDateFilter(val label: String) {
    ALL("All"),
    THIS_MONTH("This Month"),
    LAST_30_DAYS("Last 30d"),
    CUSTOM("Custom")
}

@Composable
fun SpendEarnDateSummaryCard(
    selectedFilter: HistoryDateFilter,
    startDate: LocalDate?,
    endDate: LocalDate?,
    totalSpent: BigDecimal,
    totalEarned: BigDecimal,
    netAmount: BigDecimal,
    currency: ExtendCurrency,
    modifier: Modifier = Modifier,
    onFilterSelected: (HistoryDateFilter) -> Unit,
    onSelectCustomRange: () -> Unit,
) {
    val context = LocalContext.current
    val isNight = isNightMode()
    val greenColor = if (isNight) Color(0xFF81C784) else Color(0xFF2E7D32)
    val redColor = if (isNight) Color(0xFFEF9A9A) else Color(0xFFC62828)

    val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Filter Pills Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HistoryDateFilter.values().forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val pillBg by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                    )
                    val pillText by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else colorOnEditor
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = pillBg,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onFilterSelected(filter)
                                if (filter == HistoryDateFilter.CUSTOM) {
                                    onSelectCustomRange()
                                }
                            }
                    ) {
                        Text(
                            text = filter.label,
                            color = pillText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Date Range Text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = selectedFilter == HistoryDateFilter.CUSTOM) {
                        onSelectCustomRange()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val rangeText = when {
                    startDate != null && endDate != null -> {
                        "${startDate.format(dateFormatter)} — ${endDate.format(dateFormatter)}"
                    }
                    startDate != null -> "From ${startDate.format(dateFormatter)}"
                    endDate != null -> "Until ${endDate.format(dateFormatter)}"
                    else -> "All Recorded Transactions"
                }

                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorOnEditor.copy(alpha = 0.8f)
                )

                if (selectedFilter == HistoryDateFilter.CUSTOM) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = "Edit dates",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Summary Stats Row (Spent, Earned, Net)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Total Spent
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Spent",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorOnEditor.copy(alpha = 0.65f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = numberFormat(context, totalSpent, currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = redColor
                    )
                }

                // Total Earned
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Earned",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorOnEditor.copy(alpha = 0.65f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "+ " + numberFormat(context, totalEarned, currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = greenColor
                    )
                }

                // Net Balance
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Net",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorOnEditor.copy(alpha = 0.65f)
                    )
                    Spacer(Modifier.height(4.dp))
                    val netPrefix = if (netAmount >= BigDecimal.ZERO) "+ " else ""
                    val netColor = if (netAmount >= BigDecimal.ZERO) greenColor else redColor
                    Text(
                        text = netPrefix + numberFormat(context, netAmount, currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = netColor
                    )
                }
            }
        }
    }
}
