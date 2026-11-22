package com.example.core

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CommissionBreakdown(
    val amountPaise: Long,
    val commissionRateBps: Long,
    val commissionPaise: Long,
    val expertEarningPaise: Long
)

object CurrencyUtils {
    private val indianLocale = Locale("en", "IN")

    fun formatInrFromPaise(paise: Long): String {
        val rupees = paise / 100.0
        val formatter = NumberFormat.getCurrencyInstance(indianLocale)
        formatter.maximumFractionDigits = if (paise % 100L == 0L) 0 else 2
        return formatter.format(rupees)
    }

    fun formatInr(rupees: Long): String = formatInrFromPaise(rupees * 100L)

    fun calculateCommission(amountPaise: Long, commissionRateBps: Long): CommissionBreakdown {
        require(amountPaise >= 0L) { "Amount cannot be negative" }
        require(commissionRateBps in 0L..5000L) { "Commission bps out of valid bounds" }
        val commissionPaise = (amountPaise * commissionRateBps) / 10000L
        val expertEarningPaise = amountPaise - commissionPaise
        return CommissionBreakdown(
            amountPaise = amountPaise,
            commissionRateBps = commissionRateBps,
            commissionPaise = commissionPaise,
            expertEarningPaise = expertEarningPaise
        )
    }

    fun formatCommissionPercent(bps: Long): String {
        val pct = bps / 100.0
        return if (bps % 100L == 0L) "${bps / 100}%" else String.format(Locale.US, "%.1f%%", pct)
    }
}

object TimeSlotUtils {
    private val istZone: ZoneId = ZoneId.of(BrandConfig.DEFAULT_TIMEZONE)
    private val dateDisplayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH)

    fun currentIstDate(nowMs: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(istZone).toLocalDate()

    fun currentIstMinuteOfDay(nowMs: Long = System.currentTimeMillis()): Int {
        val zdt = Instant.ofEpochMilli(nowMs).atZone(istZone)
        return zdt.hour * 60 + zdt.minute
    }

    fun upcomingDatesIso(days: Int = 14, nowMs: Long = System.currentTimeMillis()): List<String> {
        val today = currentIstDate(nowMs)
        return (0 until days).map { today.plusDays(it.toLong()).toString() }
    }

    fun formatDateDisplay(dateIso: String): String =
        try {
            LocalDate.parse(dateIso).format(dateDisplayFormatter)
        } catch (_: Exception) {
            dateIso
        }

    fun formatMinute(minuteOfDay: Int): String {
        val clamped = minuteOfDay.coerceIn(0, 1439)
        val hour24 = clamped / 60
        val min = clamped % 60
        val amPm = if (hour24 < 12) "AM" else "PM"
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return String.format(Locale.ENGLISH, "%d:%02d %s", hour12, min, amPm)
    }

    fun formatMinuteRange(startMinute: Int, durationMinutes: Int): String =
        "${formatMinute(startMinute)} – ${formatMinute(startMinute + durationMinutes)} IST"

    fun isFutureSlot(
        dateIso: String,
        startMinute: Int,
        timezone: String = BrandConfig.DEFAULT_TIMEZONE,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            val zone = ZoneId.of(timezone)
            val date = LocalDate.parse(dateIso)
            val slotZdt = ZonedDateTime.of(
                date.year, date.monthValue, date.dayOfMonth,
                startMinute / 60, startMinute % 60, 0, 0, zone
            )
            slotZdt.toInstant().toEpochMilli() > nowMs + 5 * 60_000L
        } catch (_: Exception) {
            false
        }
    }

    fun isoDayOfWeek(dateIso: String): Int =
        try {
            LocalDate.parse(dateIso).dayOfWeek.value
        } catch (_: Exception) {
            1
        }
}
