/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */
package com.danilkinkin.buckwheat.autotrack

import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.regex.Pattern

data class ParsedTransaction(
    val amount: BigDecimal,
    val type: TransactionType,
    val merchant: String,
    val rawText: String
)

object TransactionParser {

    // Common indicators of OTP/Promotional messages that shouldn't be parsed
    private val OTP_FILTER = Pattern.compile(
        """(?i)\b(otp|one time password|verification code|secret code|do not share|claim offer|won|lottery)\b"""
    )

    // Debit indicators
    private val DEBIT_PATTERNS = Pattern.compile(
        """(?i)\b(debited|debit|spent|paid|purchase of|charged|transferred to|withdrawn|sent\s+rs|sent\s+inr)\b"""
    )

    // Credit indicators
    private val CREDIT_PATTERNS = Pattern.compile(
        """(?i)\b(credited|credit|deposited|received\s+rs|received\s+inr|refund\s+of|cashback\s+of)\b"""
    )

    // Amount extraction regex supporting INR, Rs, ₹, $, €, £
    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("""(?i)(?:rs\.?|inr|₹|\$|€|£)\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
        Pattern.compile("""(?i)([0-9,]+(?:\.[0-9]{1,2})?)\s*(?:rs\.?|inr|₹)"""),
        Pattern.compile("""(?i)(?:debited by|credited with|amount of)\s*([0-9,]+(?:\.[0-9]{1,2})?)""")
    )

    // Merchant / Payee extraction
    private val MERCHANT_PATTERNS = listOf(
        Pattern.compile("""(?i)(?:at|to|in|for)\s+([A-Za-z0-9\s&'_\.\-]{2,30}?)(?:\s+(?:on|via|ref|bal|using|avl|effective|\.|\n|$))"""),
        Pattern.compile("""(?i)(?:vpa|upi id)\s+([a-zA-Z0-9.\-_]+@[a-zA-Z]+)""")
    )

    fun parse(text: String, sender: String? = null): ParsedTransaction? {
        if (text.isBlank()) return null

        // Ignore pure OTPs and promos unless there's a strong debit/credit context
        if (OTP_FILTER.matcher(text).find() && !text.contains("debited", ignoreCase = true) && !text.contains("credited", ignoreCase = true)) {
            return null
        }

        // Determine transaction type
        val isDebit = DEBIT_PATTERNS.matcher(text).find()
        val isCredit = CREDIT_PATTERNS.matcher(text).find()

        if (!isDebit && !isCredit) {
            return null
        }

        val type = if (isDebit) TransactionType.SPENT else TransactionType.INCOME

        // Extract amount
        var amount: BigDecimal? = null
        for (pattern in AMOUNT_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val rawAmountStr = matcher.group(1) ?: matcher.group(2)
                if (rawAmountStr != null) {
                    try {
                        val cleanedStr = rawAmountStr.replace(",", "").trim()
                        val parsed = BigDecimal(cleanedStr)
                        if (parsed > BigDecimal.ZERO) {
                            amount = parsed
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (amount == null) return null

        // Extract merchant / comment
        var merchant = ""
        for (pattern in MERCHANT_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val candidate = matcher.group(1)?.trim() ?: ""
                if (candidate.isNotEmpty() && !candidate.equals("your", ignoreCase = true) && !candidate.equals("the", ignoreCase = true)) {
                    merchant = candidate
                    break
                }
            }
        }

        if (merchant.isBlank()) {
            merchant = sender?.replace(Regex("^(AD|VK|VM|BP|BZ|CP|IM|AX)-"), "") ?: "Auto-detected"
        }

        return ParsedTransaction(
            amount = amount,
            type = type,
            merchant = merchant.trim(),
            rawText = text
        )
    }
}
