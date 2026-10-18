/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */
package com.danilkinkin.buckwheat.autotrack

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.entities.TransactionType

object AutoTrackNotifier {
    private const val CHANNEL_ID = "buckwheat_auto_transactions"
    private const val CHANNEL_NAME = "Auto-Logged Transactions"

    fun notifyTransaction(
        context: Context,
        parsed: ParsedTransaction
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when payments are automatically logged into Buckwheat"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = if (parsed.type == TransactionType.SPENT) {
            "Expense Auto-Logged: -${parsed.amount}"
        } else {
            "Income Auto-Logged: +${parsed.amount}"
        }

        val body = if (parsed.merchant.isNotEmpty()) {
            "Logged at ${parsed.merchant}. Budget updated automatically."
        } else {
            "Budget updated automatically from transaction message."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\nOriginal: \"${parsed.rawText}\""))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }
}
