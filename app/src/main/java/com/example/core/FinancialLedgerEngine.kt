package com.example.core

import com.example.data.model.AttendanceStatus
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.DisputeStatus
import com.example.data.model.LedgerDirection
import com.example.data.model.LedgerEntry
import com.example.data.model.LedgerEntryStatus
import com.example.data.model.LedgerEntryType
import com.example.data.model.PaymentStatus
import com.example.data.model.PayoutEligibilityStatus
import com.example.data.model.PayoutRequest
import com.example.data.model.PayoutStatus
import com.example.data.model.PlatformSettings
import com.example.data.model.ProviderSettlementStatus

data class DetailedFinancialSplit(
    val amountPaise: Long,
    val commissionRateBps: Long,
    val commissionPaise: Long,
    val gatewayFeePaise: Long,
    val taxDeductionPaise: Long,
    val expertNetEarningPaise: Long
)

data class PayoutEligibilityCheck(
    val eligible: Boolean,
    val status: PayoutEligibilityStatus,
    val reasons: List<String>,
    val releaseEligibleAtEpochMs: Long
)

data class ExpertWalletBreakdown(
    val grossRevenuePaise: Long = 0L,
    val platformCommissionPaise: Long = 0L,
    val gatewayFeePaise: Long = 0L,
    val taxDeductionPaise: Long = 0L,
    val netEarningsPaise: Long = 0L,
    val pendingEarningsPaise: Long = 0L,
    val availableForPayoutPaise: Long = 0L,
    val payoutProcessingPaise: Long = 0L,
    val paidOutPaise: Long = 0L,
    val refundedPaise: Long = 0L,
    val disputedOrHeldPaise: Long = 0L,
    val eligibleBookingIds: List<String> = emptyList()
)

object FinancialLedgerEngine {

    /**
     * Authoritative integer-paise financial breakdown.
     * - Standard platform commission: 15% (1500 bps)
     * - Expert referral commission: 10% (1000 bps)
     * - Gateway fee is transparently tracked (borne out of platform commission, never reducing quoted expert net unless taxDeduction applies)
     */
    fun calculateFinancialSplit(
        amountPaise: Long,
        commissionRateBps: Long,
        gatewayFeeBps: Long = BrandConfig.DEFAULT_GATEWAY_FEE_BPS,
        tdsBps: Long = BrandConfig.DEFAULT_TDS_BPS
    ): DetailedFinancialSplit {
        require(amountPaise >= 0L) { "Amount cannot be negative" }
        require(commissionRateBps in 0L..5000L) { "Commission bps out of range" }
        require(gatewayFeeBps in 0L..1000L) { "Gateway fee bps out of range" }
        require(tdsBps in 0L..3000L) { "TDS bps out of range" }

        val commissionPaise = (amountPaise * commissionRateBps) / 10000L
        val grossExpertPayable = amountPaise - commissionPaise
        val gatewayFeePaise = (amountPaise * gatewayFeeBps) / 10000L
        val taxDeductionPaise = (grossExpertPayable * tdsBps) / 10000L
        val expertNetEarningPaise = (grossExpertPayable - taxDeductionPaise).coerceAtLeast(0L)

        return DetailedFinancialSplit(
            amountPaise = amountPaise,
            commissionRateBps = commissionRateBps,
            commissionPaise = commissionPaise,
            gatewayFeePaise = gatewayFeePaise,
            taxDeductionPaise = taxDeductionPaise,
            expertNetEarningPaise = expertNetEarningPaise
        )
    }

    /**
     * Strict payout eligibility rule engine:
     * Never release funds just from a client completion click!
     */
    fun evaluateBookingPayoutEligibility(
        booking: Booking,
        settings: PlatformSettings = PlatformSettings(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): PayoutEligibilityCheck {
        val reasons = mutableListOf<String>()
        val bStatus = booking.bookingStatusEnum()
        val pStatus = booking.paymentStatusEnum()
        val aStatus = booking.attendanceStatusEnum()
        val dStatus = booking.disputeStatusEnum()
        val settleStatus = booking.providerSettlementEnum()

        if (booking.payoutEligibilityEnum() == PayoutEligibilityStatus.PAID) {
            return PayoutEligibilityCheck(false, PayoutEligibilityStatus.PAID, listOf("Already paid out"), booking.disputeWindowEndsAtEpochMs)
        }
        if (booking.payoutEligibilityEnum() == PayoutEligibilityStatus.PROCESSING) {
            return PayoutEligibilityCheck(false, PayoutEligibilityStatus.PROCESSING, listOf("Payout currently processing"), booking.disputeWindowEndsAtEpochMs)
        }

        if (pStatus !in setOf(PaymentStatus.SUCCESS, PaymentStatus.PARTIALLY_REFUNDED)) {
            reasons.add("Payment status is $pStatus (must be SUCCESS or PARTIALLY_REFUNDED)")
        }
        if (settleStatus !in setOf(ProviderSettlementStatus.SETTLED, ProviderSettlementStatus.SANDBOX_SETTLED)) {
            reasons.add("Provider settlement is $settleStatus (awaiting settlement)")
        }
        if (bStatus in setOf(BookingStatus.CANCELLED, BookingStatus.REFUNDED, BookingStatus.PENDING_PAYMENT)) {
            reasons.add("Booking status $bStatus is not eligible for payout")
        }
        if (bStatus == BookingStatus.DISPUTED || dStatus in setOf(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)) {
            reasons.add("Active dispute hold ($dStatus)")
        }
        if (booking.refundedAmountPaise >= booking.amountPaise && booking.amountPaise > 0L) {
            reasons.add("Booking was fully refunded")
        }

        val isAdminApproved = booking.attendanceVerificationSource == com.example.data.model.VerificationSource.ADMIN_MANUAL_REVIEW.name &&
            booking.attendanceNotes?.contains("ADMIN_APPROVED", ignoreCase = true) == true
        val attendancePassed = aStatus in setOf(AttendanceStatus.VERIFIED, AttendanceStatus.PARTIALLY_VERIFIED) ||
            (aStatus == AttendanceStatus.MANUAL_REVIEW && isAdminApproved)
        if (!attendancePassed) {
            reasons.add("Attendance is $aStatus (requires verified attendance or admin review approval)")
        }

        val completionConfirmed = (booking.customerCompletedConfirmed && booking.expertCompletedConfirmed) ||
            (bStatus == BookingStatus.COMPLETED && isAdminApproved && attendancePassed)
        if (!completionConfirmed) {
            reasons.add("Completion confirmation incomplete (Customer=${booking.customerCompletedConfirmed}, Expert=${booking.expertCompletedConfirmed})")
        }

        val windowMs = settings.disputeWindowHours.coerceAtLeast(1L) * 3_600_000L
        val effectiveWindowEnds = when {
            booking.disputeWindowEndsAtEpochMs > 0L -> booking.disputeWindowEndsAtEpochMs
            booking.completedAtEpochMs > 0L -> booking.completedAtEpochMs + windowMs
            else -> 0L
        }
        if (effectiveWindowEnds == 0L || nowEpochMs < effectiveWindowEnds) {
            reasons.add("24-hour customer dispute window has not elapsed yet")
        }

        val status = when {
            bStatus == BookingStatus.DISPUTED || dStatus in setOf(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW) ->
                PayoutEligibilityStatus.ON_HOLD
            bStatus in setOf(BookingStatus.CANCELLED, BookingStatus.REFUNDED) || pStatus == PaymentStatus.REFUNDED ->
                PayoutEligibilityStatus.REVERSED
            reasons.isEmpty() -> PayoutEligibilityStatus.ELIGIBLE
            else -> PayoutEligibilityStatus.NOT_ELIGIBLE
        }

        return PayoutEligibilityCheck(
            eligible = reasons.isEmpty(),
            status = status,
            reasons = reasons,
            releaseEligibleAtEpochMs = effectiveWindowEnds
        )
    }

    /**
     * Derives all 6 transparent wallet buckets from bookings, immutable ledger entries, and payout requests.
     */
    fun computeExpertWalletBreakdown(
        bookings: List<Booking>,
        ledger: List<LedgerEntry>,
        payouts: List<PayoutRequest>,
        settings: PlatformSettings = PlatformSettings(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): ExpertWalletBreakdown {
        val gross = ledger.filter { it.typeEnum() == LedgerEntryType.PAYMENT_CAPTURED && it.statusEnum() != LedgerEntryStatus.REVERSED }
            .sumOf { it.amountPaise }
        val commGross = ledger.filter { it.typeEnum() == LedgerEntryType.PLATFORM_COMMISSION }
            .sumOf { it.amountPaise }
        val commReversed = ledger.filter { it.typeEnum() == LedgerEntryType.COMMISSION_REVERSAL }
            .sumOf { it.amountPaise }
        val gatewayFees = ledger.filter { it.typeEnum() == LedgerEntryType.GATEWAY_FEE }
            .sumOf { it.amountPaise }
        val taxes = ledger.filter { it.typeEnum() == LedgerEntryType.TAX_DEDUCTION }
            .sumOf { it.amountPaise }
        val refunded = ledger.filter { it.typeEnum() == LedgerEntryType.REFUND_COMPLETED }
            .sumOf { it.amountPaise }
        val cancellationAdj = ledger.filter { it.typeEnum() == LedgerEntryType.CANCELLATION_ADJUSTMENT }
            .sumOf { it.amountPaise }

        val paidOutFromPayouts = payouts.filter { it.statusEnum() == PayoutStatus.PAID }.sumOf { it.amountPaise }
        val processingFromPayouts = payouts.filter {
            it.statusEnum() in setOf(PayoutStatus.PENDING, PayoutStatus.APPROVED, PayoutStatus.PROCESSING)
        }.sumOf { it.amountPaise }

        val activeOrPaidPayouts = payouts.filter {
            it.statusEnum() in setOf(PayoutStatus.PENDING, PayoutStatus.APPROVED, PayoutStatus.PROCESSING, PayoutStatus.PAID)
        }
        val claimedBookingIds = activeOrPaidPayouts.flatMap { it.eligibleBookingIds }.toSet()
        val unlinkedReservedOrPaid = activeOrPaidPayouts
            .filter { it.eligibleBookingIds.isEmpty() }
            .sumOf { it.amountPaise }

        var eligibleTotalPaise = 0L
        var pendingTotalPaise = 0L
        var disputedTotalPaise = 0L
        val eligibleBookingIds = mutableListOf<String>()
        var bookingsNetPayablePaise = 0L

        for (booking in bookings) {
            if (booking.paymentStatusEnum() !in setOf(PaymentStatus.SUCCESS, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED)) {
                continue
            }
            val refundReduction = computeBookingProportionalRefundReduction(booking)
            val remainingPayable = (booking.expertEarningPaise - refundReduction).coerceAtLeast(0L)
            val bStatus = booking.bookingStatusEnum()

            if (bStatus != BookingStatus.CANCELLED && bStatus != BookingStatus.REFUNDED && remainingPayable > 0L) {
                bookingsNetPayablePaise += remainingPayable
            } else if (bStatus == BookingStatus.CANCELLED && booking.paymentStatusEnum() == PaymentStatus.SUCCESS) {
                // If cancelled <24h without full refund yet, reflect any expert compensation retained after adjustment
                val cancelAdj = ledger.filter {
                    it.bookingId == booking.id && it.typeEnum() == LedgerEntryType.CANCELLATION_ADJUSTMENT
                }.sumOf { it.amountPaise }
                bookingsNetPayablePaise += (booking.expertEarningPaise - cancelAdj).coerceAtLeast(0L)
            }

            if (booking.paymentStatusEnum() == PaymentStatus.REFUNDED) {
                continue
            }

            val check = evaluateBookingPayoutEligibility(booking, settings, nowEpochMs)
            when (check.status) {
                PayoutEligibilityStatus.ELIGIBLE -> {
                    if (booking.id !in claimedBookingIds) {
                        eligibleTotalPaise += remainingPayable
                        eligibleBookingIds.add(booking.id)
                    }
                }
                PayoutEligibilityStatus.ON_HOLD -> {
                    disputedTotalPaise += remainingPayable
                }
                PayoutEligibilityStatus.NOT_ELIGIBLE -> {
                    if (bStatus !in setOf(BookingStatus.CANCELLED, BookingStatus.REFUNDED)) {
                        pendingTotalPaise += remainingPayable
                    }
                }
                else -> Unit
            }
        }

        val ledgerCredits = ledger.filter {
            it.typeEnum() == LedgerEntryType.EXPERT_EARNING && it.directionEnum() == LedgerDirection.CREDIT
        }.sumOf { it.amountPaise }

        val effectiveNetEarnings = if (bookings.isNotEmpty()) {
            bookingsNetPayablePaise
        } else {
            (ledgerCredits - cancellationAdj - refunded).coerceAtLeast(0L)
        }
        val availableAfterReservedPayouts = (eligibleTotalPaise - unlinkedReservedOrPaid)
            .coerceAtLeast(0L)

        return ExpertWalletBreakdown(
            grossRevenuePaise = gross,
            platformCommissionPaise = (commGross - commReversed).coerceAtLeast(0L),
            gatewayFeePaise = gatewayFees,
            taxDeductionPaise = taxes,
            netEarningsPaise = effectiveNetEarnings,
            pendingEarningsPaise = pendingTotalPaise,
            availableForPayoutPaise = availableAfterReservedPayouts,
            payoutProcessingPaise = processingFromPayouts,
            paidOutPaise = paidOutFromPayouts,
            refundedPaise = refunded,
            disputedOrHeldPaise = disputedTotalPaise,
            eligibleBookingIds = eligibleBookingIds
        )
    }

    fun computeBookingProportionalRefundReduction(booking: Booking): Long {
        if (booking.amountPaise <= 0L || booking.refundedAmountPaise <= 0L) return 0L
        return (booking.expertEarningPaise * booking.refundedAmountPaise) / booking.amountPaise
    }
}
