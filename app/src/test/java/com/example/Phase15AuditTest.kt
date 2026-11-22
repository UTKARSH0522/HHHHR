package com.example

import com.example.core.FinancialLedgerEngine
import com.example.data.model.AttendanceStatus
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.PaymentStatus
import com.example.data.model.PayoutEligibilityStatus
import com.example.data.model.PayoutRequest
import com.example.data.model.PayoutStatus
import com.example.data.model.ProviderSettlementStatus
import com.example.data.model.VerificationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase15AuditTest {

    @Test
    fun `spoofed ADMIN_APPROVED note without ADMIN_MANUAL_REVIEW verification source cannot unlock payout`() {
        val nowMs = 1_800_200_000_000L
        val spoofed = Booking(
            id = "bk_spoof",
            amountPaise = 100000L,
            commissionRateBps = 1500L,
            commissionPaise = 15000L,
            expertEarningPaise = 85000L,
            bookingStatus = BookingStatus.COMPLETED.name,
            paymentStatus = PaymentStatus.SUCCESS.name,
            providerSettlementStatus = ProviderSettlementStatus.SETTLED.name,
            attendanceStatus = AttendanceStatus.MANUAL_REVIEW.name,
            attendanceVerificationSource = VerificationSource.SUPPLEMENTARY_CHECKIN_FALLBACK.name,
            attendanceNotes = "ADMIN_APPROVED spoofed note",
            customerCompletedConfirmed = true,
            expertCompletedConfirmed = true,
            completedAtEpochMs = nowMs - 100_000_000L,
            disputeWindowEndsAtEpochMs = nowMs - 10_000_000L
        )

        val spoofResult = FinancialLedgerEngine.evaluateBookingPayoutEligibility(spoofed, nowEpochMs = nowMs)
        assertFalse(spoofResult.eligible)

        val adminVerified = spoofed.copy(attendanceVerificationSource = VerificationSource.ADMIN_MANUAL_REVIEW.name)
        val validResult = FinancialLedgerEngine.evaluateBookingPayoutEligibility(adminVerified, nowEpochMs = nowMs)
        assertTrue(validResult.eligible)
        assertEquals(PayoutEligibilityStatus.ELIGIBLE, validResult.status)
    }

    @Test
    fun `wallet breakdown never double-subtracts PROCESSING or PAID payouts from unrelated eligible bookings`() {
        val nowMs = 1_800_200_000_000L
        val baseBooking = Booking(
            amountPaise = 100000L,
            commissionRateBps = 1500L,
            commissionPaise = 15000L,
            gatewayFeePaise = 2000L,
            expertEarningPaise = 85000L,
            bookingStatus = BookingStatus.COMPLETED.name,
            paymentStatus = PaymentStatus.SUCCESS.name,
            providerSettlementStatus = ProviderSettlementStatus.SETTLED.name,
            attendanceStatus = AttendanceStatus.VERIFIED.name,
            attendanceVerificationSource = VerificationSource.GOOGLE_MEET_REST_API.name,
            customerCompletedConfirmed = true,
            expertCompletedConfirmed = true,
            completedAtEpochMs = nowMs - 100_000_000L,
            disputeWindowEndsAtEpochMs = nowMs - 10_000_000L
        )

        val bkPaid = baseBooking.copy(id = "bk_paid", payoutEligibilityStatus = PayoutEligibilityStatus.PAID.name)
        val bkProcessing = baseBooking.copy(id = "bk_proc", payoutEligibilityStatus = PayoutEligibilityStatus.PROCESSING.name)
        val bkPendingClaim = baseBooking.copy(id = "bk_pending_claim", payoutEligibilityStatus = PayoutEligibilityStatus.NOT_ELIGIBLE.name)
        val bkFreeEligible = baseBooking.copy(id = "bk_free_eligible", payoutEligibilityStatus = PayoutEligibilityStatus.NOT_ELIGIBLE.name)

        val payouts = listOf(
            PayoutRequest(
                id = "pr_paid",
                amountPaise = 85000L,
                status = PayoutStatus.PAID.name,
                eligibleBookingIds = listOf("bk_paid")
            ),
            PayoutRequest(
                id = "pr_proc",
                amountPaise = 85000L,
                status = PayoutStatus.PROCESSING.name,
                eligibleBookingIds = listOf("bk_proc")
            ),
            PayoutRequest(
                id = "pr_pending",
                amountPaise = 85000L,
                status = PayoutStatus.PENDING.name,
                eligibleBookingIds = listOf("bk_pending_claim")
            ),
            PayoutRequest(
                id = "pr_failed",
                amountPaise = 85000L,
                status = PayoutStatus.FAILED.name,
                eligibleBookingIds = listOf("bk_free_eligible")
            )
        )

        val wallet = FinancialLedgerEngine.computeExpertWalletBreakdown(
            bookings = listOf(bkPaid, bkProcessing, bkPendingClaim, bkFreeEligible),
            ledger = emptyList(),
            payouts = payouts,
            nowEpochMs = nowMs
        )

        assertEquals(340000L, wallet.netEarningsPaise)
        assertEquals(85000L, wallet.paidOutPaise)
        assertEquals(170000L, wallet.payoutProcessingPaise)
        // Must preserve bk_free_eligible (85000) without double-subtracting pr_paid or pr_proc,
        // and must exclude bk_pending_claim (claimed by PENDING payout) while allowing bk_free_eligible after FAILED retry!
        assertEquals(85000L, wallet.availableForPayoutPaise)
        assertEquals(listOf("bk_free_eligible"), wallet.eligibleBookingIds)
    }

    @Test
    fun `partial refund proportionally updates remaining payable and leaves zero negative balances`() {
        val nowMs = 1_800_200_000_000L
        val partialRefundBooking = Booking(
            id = "bk_partial",
            amountPaise = 100000L,
            commissionRateBps = 1500L,
            commissionPaise = 15000L,
            expertEarningPaise = 85000L,
            refundedAmountPaise = 25000L,
            bookingStatus = BookingStatus.COMPLETED.name,
            paymentStatus = PaymentStatus.PARTIALLY_REFUNDED.name,
            providerSettlementStatus = ProviderSettlementStatus.SETTLED.name,
            attendanceStatus = AttendanceStatus.VERIFIED.name,
            customerCompletedConfirmed = true,
            expertCompletedConfirmed = true,
            completedAtEpochMs = nowMs - 100_000_000L,
            disputeWindowEndsAtEpochMs = nowMs - 10_000_000L
        )
        val wallet = FinancialLedgerEngine.computeExpertWalletBreakdown(
            bookings = listOf(partialRefundBooking),
            ledger = emptyList(),
            payouts = emptyList(),
            nowEpochMs = nowMs
        )
        // 25% of 85000 = 21250 -> remaining payable = 63750
        assertEquals(63750L, wallet.netEarningsPaise)
        assertEquals(63750L, wallet.availableForPayoutPaise)
    }
}
