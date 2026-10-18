package com.danilkinkin.buckwheat.autotrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoTrackEntryPoint {
    fun spendsRepository(): SpendsRepository
}

class SmsTransactionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsTransactionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: ""
        val fullBody = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }

        Log.d(TAG, "Incoming SMS from $sender: $fullBody")

        val parsed = TransactionParser.parse(fullBody, sender) ?: return

        Log.d(TAG, "Parsed transaction: Amount=${parsed.amount}, Type=${parsed.type}, Merchant=${parsed.merchant}")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
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
                    // When money is credited, add to current overall budget
                    val currentBudget = repository.getBudget().first()
                    val finishDate = repository.getFinishPeriodDate().first() ?: Date()
                    repository.changeBudget(currentBudget + parsed.amount, finishDate)
                }

                // Update widgets immediately
                ExtendWidgetReceiver.requestUpdateData(context.applicationContext)
                MinimalWidgetReceiver.requestUpdateData(context.applicationContext)

                // Show notification to user
                AutoTrackNotifier.notifyTransaction(context.applicationContext, parsed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record auto-tracked transaction", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
