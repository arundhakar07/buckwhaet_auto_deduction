/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */
package com.danilkinkin.buckwheat.history

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.TUTORIAL_STAGE
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.editor.EditorViewModel
import com.danilkinkin.buckwheat.editor.dateTimeEdit.DatePickerDialog
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorEditor
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*
import kotlin.math.abs
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun History(
    modifier: Modifier = Modifier,
    spendsViewModel: SpendsViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
    editorViewModel: EditorViewModel = viewModel(),
    readOnly: Boolean = false,
    onClose: () -> Unit = {}
) {
    val scrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var historyList by remember { mutableStateOf<List<RowEntity>>(emptyList()) }
    val budget = spendsViewModel.budget.observeAsState(initial = BigDecimal.ZERO)
    val currency = spendsViewModel.currency.observeAsState(initial = ExtendCurrency.none())
    val startPeriodDate = spendsViewModel.startPeriodDate.observeAsState(initial = Date())
    val finishPeriodDate = spendsViewModel.finishPeriodDate.observeAsState(initial = Date())
    val allTransactions by spendsViewModel.transactions.observeAsState(initial = emptyList())
    val scrollToBottom = remember { mutableStateOf(true) }
    val tutorial by appViewModel.getTutorialStage(TUTORS.SWIPE_EDIT_SPENT).observeAsState(TUTORIAL_STAGE.NONE)
    var isUserTrySwipe by remember { mutableStateOf(false) }

    // Date range filter state
    var selectedFilter by remember { mutableStateOf(HistoryDateFilter.ALL) }
    var customStartDate by remember { mutableStateOf<LocalDate?>(null) }
    var customEndDate by remember { mutableStateOf<LocalDate?>(null) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val currentFilterStartDate = when (selectedFilter) {
        HistoryDateFilter.ALL -> null
        HistoryDateFilter.THIS_MONTH -> LocalDate.now().withDayOfMonth(1)
        HistoryDateFilter.LAST_30_DAYS -> LocalDate.now().minusDays(30)
        HistoryDateFilter.CUSTOM -> customStartDate ?: LocalDate.now().minusDays(7)
    }

    val currentFilterEndDate = when (selectedFilter) {
        HistoryDateFilter.ALL -> null
        HistoryDateFilter.THIS_MONTH -> LocalDate.now()
        HistoryDateFilter.LAST_30_DAYS -> LocalDate.now()
        HistoryDateFilter.CUSTOM -> customEndDate ?: LocalDate.now()
    }

    val filteredTransactions = remember(allTransactions, currentFilterStartDate, currentFilterEndDate) {
        allTransactions.filter { txn ->
            if (txn.type == TransactionType.SET_DAILY_BUDGET) return@filter false
            val txnDate = txn.date.toLocalDate()
            if (currentFilterStartDate != null && txnDate.isBefore(currentFilterStartDate)) return@filter false
            if (currentFilterEndDate != null && txnDate.isAfter(currentFilterEndDate)) return@filter false
            true
        }
    }

    val totalSpent = remember(filteredTransactions) {
        filteredTransactions
            .filter { it.type == TransactionType.SPENT }
            .fold(BigDecimal.ZERO) { acc, txn -> acc + txn.value }
    }

    val totalEarned = remember(filteredTransactions) {
        filteredTransactions
            .filter { it.type == TransactionType.INCOME }
            .fold(BigDecimal.ZERO) { acc, txn -> acc + txn.value }
    }

    val netAmount = totalEarned - totalSpent

    LaunchedEffect(filteredTransactions) {
        val composedList = emptyList<RowEntity>().toMutableList()
        var lastSpentDate: LocalDate? = null
        var lastDayTotal: BigDecimal = BigDecimal.ZERO

        filteredTransactions.forEach { spent ->
            if (lastSpentDate === null || !isSameDay(
                    spent.date.time,
                    lastSpentDate!!.toDate().time
                )
            ) {
                if (lastSpentDate !== null) {
                    composedList.add(
                        RowEntity(
                            type = RowEntityType.DayTotal,
                            key = "total-${lastSpentDate}",
                            contentHash = "total-${lastSpentDate}",
                            transaction = null,
                            day = lastSpentDate!!,
                            dayTotal = lastDayTotal,
                        )
                    )
                }

                lastSpentDate = spent.date.toLocalDate()
                lastDayTotal = BigDecimal.ZERO

                composedList.add(
                    RowEntity(
                        type = RowEntityType.DayDivider,
                        key = "header-${lastSpentDate}",
                        contentHash = "header-${lastSpentDate}",
                        transaction = null,
                        day = lastSpentDate!!,
                        dayTotal = null,
                    )
                )
            }

            if (spent.type == TransactionType.SPENT) {
                lastDayTotal += spent.value
            }

            composedList.add(
                RowEntity(
                    type = RowEntityType.Spent,
                    key = "spent-${spent.uid}",
                    contentHash = "spent-${spent.uid}",
                    transaction = spent,
                    day = lastSpentDate!!,
                    dayTotal = null,
                )
            )
        }

        if (filteredTransactions.isNotEmpty() && lastSpentDate !== null) {
            composedList.add(
                RowEntity(
                    type = RowEntityType.DayTotal,
                    key = "total-${lastSpentDate!!}",
                    contentHash = "total-${lastSpentDate}",
                    transaction = null,
                    day = lastSpentDate!!,
                    dayTotal = lastDayTotal,
                )
            )
        }

        historyList = composedList.toList().reversed()
    }

    DisposableEffect(Unit) {
        appViewModel.lockSwipeable.value = false
        scrollToBottom.value = true

        onDispose {
            appViewModel.lockSwipeable.value = false

            if (historyList.isNotEmpty() && isUserTrySwipe) {
                appViewModel.passTutorial(TUTORS.SWIPE_EDIT_SPENT)
            }
        }
    }

    val fapScale by animateFloatAsState(
        targetValue = if (appViewModel.lockSwipeable.value) 1f else 0f,
        animationSpec = TweenSpec(250),
    )

    val animatedList = updateAnimatedItemsState(newList = historyList)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                reverseLayout = true,
                state = scrollState
            ) {
                // Fixer, because if scroll fast end-checker dispose
                item("spacer-2") {
                    Spacer(modifier = Modifier.height(2.dp))
                }

                item("end-checker") {
                    DisposableEffect(Unit) {
                        appViewModel.lockSwipeable.value = false

                        onDispose {
                            appViewModel.lockSwipeable.value = true
                        }
                    }
                }

                item("spacer") {
                    Spacer(modifier = Modifier.height(18.dp))
                }

                animatedItemsIndexed(
                    state = animatedList.value,
                    key = { rowItem -> rowItem.key },
                ) { index, row ->
                    when (row.type) {
                        RowEntityType.DayDivider -> HistoryDateDivider(row.day)
                        RowEntityType.DayTotal -> TotalPerDay(
                            spentPerDay = row.dayTotal!!,
                            currency = currency.value,
                        )
                        RowEntityType.Spent -> if (!readOnly) SwipeActions(
                            startActionsConfig = SwipeActionsConfig(
                                threshold = 0.4f,
                                background = MaterialTheme.colorScheme.tertiaryContainer,
                                backgroundActive = MaterialTheme.colorScheme.tertiary,
                                iconTint = MaterialTheme.colorScheme.onTertiary,
                                icon = painterResource(R.drawable.ic_edit),
                                stayDismissed = false,
                                onDismiss = {
                                    editorViewModel.startEditingSpent(row.transaction!!)
                                    onClose()
                                }
                            ),
                            endActionsConfig = SwipeActionsConfig(
                                threshold = 0.4f,
                                background = MaterialTheme.colorScheme.errorContainer,
                                backgroundActive = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.onError,
                                icon = painterResource(R.drawable.ic_delete_forever),
                                stayDismissed = true,
                                onDismiss = {
                                    spendsViewModel.removeSpent(row.transaction!!)
                                }
                            ),
                            onTried = { isUserTrySwipe = true },
                            showTutorial = index == 2 && tutorial === TUTORIAL_STAGE.READY_TO_SHOW,
                        ) { state ->
                            val size = with(LocalDensity.current) {
                                java.lang.Float.max(
                                    java.lang.Float.min(
                                        16.dp.toPx(),
                                        abs(state.offset.value)
                                    ), 0f
                                ).toDp()
                            }

                            val animateCorners by remember {
                                derivedStateOf {
                                    state.offset.value.absoluteValue > 30
                                }
                            }
                            val startCorners by animateDpAsState(
                                targetValue = when {
                                    state.dismissDirection == DismissDirection.StartToEnd &&
                                            animateCorners -> 8.dp
                                    else -> 0.dp
                                }
                            )
                            val endCorners by animateDpAsState(
                                targetValue = when {
                                    state.dismissDirection == DismissDirection.EndToStart &&
                                            animateCorners -> 8.dp
                                    else -> 0.dp
                                }
                            )

                            Box(
                                modifier = Modifier.height(IntrinsicSize.Min)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            vertical = min(
                                                size / 4f,
                                                4.dp
                                            )
                                        )
                                        .clip(RoundedCornerShape(size)),
                                    color = colorEditor,
                                    shape = RoundedCornerShape(
                                        topStart = startCorners,
                                        bottomStart = startCorners,
                                        topEnd = endCorners,
                                        bottomEnd = endCorners,
                                    ),
                                ) {
                                }
                                Box(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    SpentItem(
                                        transaction = row.transaction!!,
                                        currency = currency.value
                                    )
                                }
                            }
                        } else {
                            SpentItem(
                                transaction = row.transaction!!,
                                currency = currency.value
                            )
                        }
                    }
                }

                if (!readOnly) {
                    item("date-summary-card") {
                        SpendEarnDateSummaryCard(
                            selectedFilter = selectedFilter,
                            startDate = currentFilterStartDate,
                            endDate = currentFilterEndDate,
                            totalSpent = totalSpent,
                            totalEarned = totalEarned,
                            netAmount = netAmount,
                            currency = currency.value,
                            onFilterSelected = { selectedFilter = it },
                            onSelectCustomRange = { showStartDatePicker = true }
                        )
                    }

                    if (budget.value > BigDecimal.ZERO) {
                        item("budget-info") {
                            WholeBudgetCard(
                                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                                budget = budget.value,
                                currency = currency.value,
                                startDate = startPeriodDate.value,
                                finishDate = finishPeriodDate.value,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        LocalWindowInsets.current.calculateTopPadding()
                                    )
                            )
                        }
                    } else {
                        item("top-padding") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        LocalWindowInsets.current.calculateTopPadding()
                                    )
                            )
                        }
                    }
                }
            }

            if (historyList.isEmpty()) {
                NoSpends(Modifier.weight(1f))
            }
        }

        if (!readOnly) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                FloatingActionButton(
                    modifier = Modifier
                        .padding(end = 24.dp, bottom = 32.dp)
                        .scale(fapScale),
                    onClick = {
                        coroutineScope.launch {
                            scrollState.animateScrollToItem(0)
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                }
            }
        }

        if (showStartDatePicker) {
            DatePickerDialog(
                initDate = customStartDate ?: LocalDate.now().minusDays(7),
                onSelect = { selected ->
                    customStartDate = selected
                    showStartDatePicker = false
                    showEndDatePicker = true
                },
                onClose = { showStartDatePicker = false }
            )
        }

        if (showEndDatePicker) {
            DatePickerDialog(
                initDate = customEndDate ?: LocalDate.now(),
                disableBeforeDate = customStartDate,
                onSelect = { selected ->
                    customEndDate = selected
                    showEndDatePicker = false
                    selectedFilter = HistoryDateFilter.CUSTOM
                },
                onClose = { showEndDatePicker = false }
            )
        }
    }
}

@Preview
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        History()
    }
}
