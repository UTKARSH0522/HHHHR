package com.example.core

import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.PaymentStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class CancellationPolicyDecision(
    val allowed: Boolean,
    val refundPaise: Long,
    val expertCompensationPaise: Long,
    val policyNote: String
)

object ConsultationLifecycleEngine {

    private val allowedBookingTransitions: Map<BookingStatus, Set<BookingStatus>> = mapOf(
        BookingStatus.PENDING_PAYMENT to setOf(
            BookingStatus.CONFIRMED,
            BookingStatus.SCHEDULED,
            BookingStatus.CANCELLED
        ),
        BookingStatus.CONFIRMED to setOf(
            BookingStatus.SCHEDULED,
            BookingStatus.IN_PROGRESS,
            BookingStatus.ATTENDANCE_PENDING,
            BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED,
            BookingStatus.CANCELLED,
            BookingStatus.DISPUTED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.SCHEDULED to setOf(
            BookingStatus.IN_PROGRESS,
            BookingStatus.ATTENDANCE_PENDING,
            BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED,
            BookingStatus.CANCELLED,
            BookingStatus.DISPUTED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.IN_PROGRESS to setOf(
            BookingStatus.ATTENDANCE_PENDING,
            BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED,
            BookingStatus.DISPUTED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.ATTENDANCE_PENDING to setOf(
            BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED,
            BookingStatus.DISPUTED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.COMPLETION_PENDING to setOf(
            BookingStatus.COMPLETED,
            BookingStatus.DISPUTED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.COMPLETED to setOf(
            BookingStatus.DISPUTED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.DISPUTED to setOf(
            BookingStatus.COMPLETED,
            BookingStatus.REFUNDED
        ),
        BookingStatus.CANCELLED to setOf(BookingStatus.REFUNDED),
        BookingStatus.REFUNDED to emptySet()
    )

    fun canTransitionBooking(from: BookingStatus, to: BookingStatus): Boolean {
        if (from == to) return true
        return allowedBookingTransitions[from]?.contains(to) == true
    }

    fun scheduledStartEpochMs(booking: Booking): Long {
        return try {
            val zone = ZoneId.of(booking.timezone.ifBlank { BrandConfig.DEFAULT_TIMEZONE })
            val date = LocalDate.parse(booking.dateIso)
            val startMin = booking.startMinute.toInt().coerceIn(0, 1439)
            ZonedDateTime.of(
                date.year, date.monthValue, date.dayOfMonth,
                startMin / 60, startMin % 60, 0, 0, zone
            ).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    fun evaluateCancellationRefund(
        booking: Booking,
        cancelledByExpert: Boolean,
        cancellationWindowHours: Long = BrandConfig.DEFAULT_CANCELLATION_WINDOW_HOURS,
        nowEpochMs: Long = System.currentTimeMillis()
    ): CancellationPolicyDecision {
        val status = booking.bookingStatusEnum()
        if (status in setOf(BookingStatus.COMPLETED, BookingStatus.CANCELLED, BookingStatus.REFUNDED)) {
            return CancellationPolicyDecision(
                allowed = false,
                refundPaise = 0L,
                expertCompensationPaise = 0L,
                policyNote = "Bookings in $status state cannot be cancelled."
            )
        }
        if (booking.paymentStatusEnum() != PaymentStatus.SUCCESS) {
            return CancellationPolicyDecision(
                allowed = true,
                refundPaise = 0L,
                expertCompensationPaise = 0L,
                policyNote = "Unpaid slot hold released cleanly with ₹0 charge."
            )
        }
        if (cancelledByExpert) {
            return CancellationPolicyDecision(
                allowed = true,
                refundPaise = booking.amountPaise,
                expertCompensationPaise = 0L,
                policyNote = "Cancelled by expert: customer eligible for 100% refund."
            )
        }
        val startMs = scheduledStartEpochMs(booking)
        val hoursBefore = if (startMs > 0L) (startMs - nowEpochMs) / 3_600_000.0 else 999.0
        return if (hoursBefore >= cancellationWindowHours.toDouble()) {
            CancellationPolicyDecision(
                allowed = true,
                refundPaise = booking.amountPaise,
                expertCompensationPaise = 0L,
                policyNote = "Cancelled >= ${cancellationWindowHours}h before session: eligible for full refund."
            )
        } else {
            // Late customer cancellation inside 24h window: 50% refund / 50% split held for admin/expert protection
            val refund = booking.amountPaise / 2L
            CancellationPolicyDecision(
                allowed = true,
                refundPaise = refund,
                expertCompensationPaise = (booking.expertEarningPaise / 2L),
                policyNote = "Late customer cancellation (<${cancellationWindowHours}h notice): 50% refund policy applies."
            )
        }
    }
}
