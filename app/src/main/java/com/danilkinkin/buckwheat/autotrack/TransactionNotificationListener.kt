/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */
package com.danilkinkin.buckwheat.autotrack

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

@AndroidEntryPoint
class TransactionNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"

        // Deduplicate recent transactions to prevent double-logging (e.g. if both SMS & notification arrive)
        private val recentTransactions = ConcurrentHashMap<String, Long>()
        private const val DEDUP_WINDOW_MS = 60_000L // 1 minute
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val combinedContent = "$title. $text $bigText".trim()
        val packageName = sbn.packageName ?: ""

        // Fast check: look for money keywords
        val parsed = TransactionParser.parse(combinedContent, packageName) ?: return

        // Deduplication key
        val dedupKey = "${parsed.amount}_${parsed.type}_${parsed.merchant}"
        val now = System.currentTimeMillis()
        val lastSeen = recentTransactions[dedupKey]

        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicate notification transaction: $dedupKey")
            return
        }
        recentTransactions[dedupKey] = now

        // Cleanup old dedup entries
        recentTransactions.entries.removeIf { (now - it.value) > DEDUP_WINDOW_MS }

        Log.d(TAG, "Parsed notification payment: Amount=${parsed.amount}, Type=${parsed.type}, Merchant=${parsed.merchant}")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    AutoTrackEntryPoint::class.java
                )
                val repository = entryPoint.spendsRepository()

                if (parsed.type == TransactionType.SPENT) {
                    val transaction = Transaction(
                        type = TransactionType.SPENT,
                        value = parsed.amount,
                        date = Date(),
                        comment = parsed.merchant
                    )
                    repository.addSpent(transaction)
                } else if (parsed.type == TransactionType.INCOME) {
                    val currentBudget = repository.getBudget().first()
                    val finishDate = repository.getFinishPeriodDate().first() ?: Date()
                    repository.changeBudget(currentBudget + parsed.amount, finishDate)
                }

                ExtendWidgetReceiver.requestUpdateData(applicationContext)
                MinimalWidgetReceiver.requestUpdateData(applicationContext)

                AutoTrackNotifier.notifyTransaction(applicationContext, parsed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log transaction from notification", e)
            }
        }
    }
}
