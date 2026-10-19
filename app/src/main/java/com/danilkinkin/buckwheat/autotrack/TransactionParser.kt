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

    // Bank account or financial instrument indicators (e.g. A/c, card, ending 1234, *3802, UPI, VPA)
    private val ACCOUNT_PATTERNS = Pattern.compile(
        """(?i)(?:a/c|acct|account|card|\*+[0-9]{3,4}|[xX]+[0-9]{3,4}|ending\s+[0-9]{3,4}|vpa|upi)"""
    )

    // Common indicators of OTP/Promotional/Bill due messages that must be strictly rejected
    private val REJECT_PATTERNS = Pattern.compile(
        """(?i)\b(otp|one time password|verification code|secret code|do not share|claim offer|cashback on your next|recharge now|due on|validity|win|won|lottery)\b"""
    )

    // Debit indicators
    private val DEBIT_PATTERNS = Pattern.compile(
        """(?i)\b(debited|debit|spent|paid|purchase of|charged|transferred|withdrawn|sent\s+rs|sent\s+inr)\b"""
    )

    // Credit indicators
    private val CREDIT_PATTERNS = Pattern.compile(
        """(?i)\b(credited|credit|deposited|received\s+rs|received\s+inr|refund|cashback)\b"""
    )

    // Pattern to identify and mask balance statements so available balance is never confused with transaction amount
    private val BALANCE_MASK = Pattern.compile(
        """(?i)(?:/|\b)?(?:avl\s*bal|available\s*bal|bal(?:ance)?|avl\s*limit)\s*[:\.]?\s*(?:rs\.?|rs:|inr|₹|\$|€|£)?\s*[:\.]?\s*[0-9,]+(?:\.[0-9]{1,2})?"""
    )

    // Amount extraction regex supporting Rs:, Rs., INR, ₹, $, €, £
    private val AMOUNT_PATTERNS = listOf(
        // Direct debit/credit context: e.g. "Debited Rs:110.00" or "Debited by Rs 1,200.00"
        Pattern.compile("""(?i)(?:debited|credited|spent|paid|withdrawn|charged|deposited|transferred)\s*(?:by|with|for|of)?\s*[:\.]?\s*(?:rs\.?|rs:|inr|₹|\$|€|£)?\s*[:\.]?\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
        // Currency followed by amount: "Rs:110.00", "Rs. 110.00", "INR 110.00", "₹110.00"
        Pattern.compile("""(?i)(?:rs\.?|rs:|inr|₹|\$|€|£)\s*[:\.]?\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
        // Amount followed by currency: "110.00 Rs"
        Pattern.compile("""(?i)([0-9,]+(?:\.[0-9]{1,2})?)\s*(?:rs\.?|rs:|inr|₹)""")
    )

    // Merchant / Payee extraction
    private val FVG_PATTERN = Pattern.compile(
        """(?i)Fvg:\s*([A-Za-z0-9\s&'_\.\-]{2,40}?)(?:\s*(?:/|on|via|ref|bal|avl|effective|\.|\n|$))"""
    )
    private val VPA_PATTERN = Pattern.compile(
        """(?i)(?:to\s+)?(?:VPA|UPI\s+ID)\s+([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)"""
    )
    private val AT_PATTERN = Pattern.compile(
        """(?i)(?:at|to|in|for|transfer to)\s+([A-Za-z0-9\s&'_\.\-@]{2,30}?)(?:\s+(?:on|via|ref|bal|using|avl|effective|\.|\n|$))"""
    )
    private val BY_PATTERN = Pattern.compile(
        """(?i)(?:by)\s+([A-Za-z0-9\s/_\.\-]{2,30}?)(?:\s+(?:on|via|ref|bal|using|avl|effective|\.|\n|$))"""
    )

    fun parse(text: String, sender: String? = null): ParsedTransaction? {
        if (text.isBlank()) return null

        // 1. Must contain account or financial instrument indicator
        if (!ACCOUNT_PATTERNS.matcher(text).find()) {
            return null
        }

        // 2. Reject OTPs, marketing, bills due, recharges
        if (REJECT_PATTERNS.matcher(text).find()) {
            return null
        }

        // 3. Determine transaction type (Debit vs Credit)
        val isDebit = DEBIT_PATTERNS.matcher(text).find()
        val isCredit = CREDIT_PATTERNS.matcher(text).find()
        if (!isDebit && !isCredit) {
            return null
        }
        val type = if (isDebit) TransactionType.SPENT else TransactionType.INCOME

        // 4. Mask out available balance so it is never confused with the transaction amount
        val textWithoutBal = BALANCE_MASK.matcher(text).replaceAll(" ")

        // 5. Extract transaction amount
        var amount: BigDecimal? = null
        for (pattern in AMOUNT_PATTERNS) {
            val matcher = pattern.matcher(textWithoutBal)
            if (matcher.find()) {
                val rawAmountStr = matcher.group(1)
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

        // 6. Extract merchant / payee / beneficiary
        var merchant = ""
        val fvgMatcher = FVG_PATTERN.matcher(text)
        if (fvgMatcher.find()) {
            merchant = fvgMatcher.group(1)?.trim() ?: ""
        }

        if (merchant.isBlank()) {
            val vpaMatcher = VPA_PATTERN.matcher(text)
            if (vpaMatcher.find()) {
                merchant = vpaMatcher.group(1)?.trim() ?: ""
            }
        }

        if (merchant.isBlank()) {
            val atMatcher = AT_PATTERN.matcher(text)
            if (atMatcher.find()) {
                val candidate = atMatcher.group(1)?.trim()?.trimEnd('.', ',', '/') ?: ""
                if (candidate.isNotEmpty() && !candidate.equals("your", ignoreCase = true) &&
                    !candidate.equals("the", ignoreCase = true) && !candidate.equals("account", ignoreCase = true)
                ) {
                    merchant = candidate
                }
            }
        }

        if (merchant.isBlank()) {
            val byMatcher = BY_PATTERN.matcher(text)
            if (byMatcher.find()) {
                val candidate = byMatcher.group(1)?.trim()?.trimEnd('.', ',', '/') ?: ""
                if (candidate.isNotEmpty() && !candidate.equals("your", ignoreCase = true) &&
                    !candidate.equals("the", ignoreCase = true) && !candidate.equals("mob bk", ignoreCase = true) &&
                    !candidate.equals("account", ignoreCase = true)
                ) {
                    merchant = candidate
                }
            }
        }

        if (merchant.isBlank()) {
            merchant = sender?.replace(Regex("^(AD|VK|VM|BP|BZ|CP|IM|AX)-", RegexOption.IGNORE_CASE), "") ?: "Bank Transaction"
        }

        return ParsedTransaction(
            amount = amount,
            type = type,
            merchant = merchant.trim(),
            rawText = text
        )
    }
}
