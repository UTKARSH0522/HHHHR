package com.example

import com.example.core.AttendanceScenario
import com.example.core.AttendanceVerificationEngine
import com.example.core.ConsultationLifecycleEngine
import com.example.core.CurrencyUtils
import com.example.core.FinancialLedgerEngine
import com.example.core.MeetParticipantEvidence
import com.example.core.MeetSessionInterval
import com.example.core.PaymentVerificationRequest
import com.example.core.PaymentVerificationResult
import com.example.core.RazorpaySignatureVerifier
import com.example.core.TimeSlotUtils
import com.example.data.model.AttendanceStatus
import com.example.data.model.AvailabilityException
import com.example.data.model.AvailabilityRule
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
import com.example.data.model.SlotLock
import com.example.data.model.VerificationSource
import com.example.data.model.safeEnumValueOf
import com.example.data.repository.MarketplaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HRConnectBusinessLogicTest {

    // --- 1. COMMISSION & FINANCIAL SPLIT ---

    @Test
    fun `commission math uses exact integer paise for 15 percent platform and 10 percent referral`() {
        val platformBreakdown = CurrencyUtils.calculateCommission(
            amountPaise = 100000L, // Rs 1,000
            commissionRateBps = 1500L
        )
        assertEquals(15000L, platformBreakdown.commissionPaise) // Rs 150
        assertEquals(85000L, platformBreakdown.expertEarningPaise) // Rs 850
        assertEquals(100000L, platformBreakdown.commissionPaise + platformBreakdown.expertEarningPaise)

        val referralBreakdown = CurrencyUtils.calculateCommission(
            amountPaise = 100000L, // Rs 1,000
            commissionRateBps = 1000L
        )
        assertEquals(10000L, referralBreakdown.commissionPaise) // Rs 100
        assertEquals(90000L, referralBreakdown.expertEarningPaise) // Rs 900
        assertEquals(100000L, referralBreakdown.commissionPaise + referralBreakdown.expertEarningPaise)
    }

    @Test
    fun `detailed financial split tracks gateway fee and optional tds without altering commission math`() {
        val split = FinancialLedgerEngine.calculateFinancialSplit(
            amountPaise = 200000L, // Rs 2,000
            commissionRateBps = 1500L, // 15% = Rs 300
            gatewayFeeBps = 200L, // 2% = Rs 40
            tdsBps = 1000L // 10% TDS on gross payable (Rs 1,700) = Rs 170
        )
        assertEquals(30000L, split.commissionPaise)
        assertEquals(4000L, split.gatewayFeePaise)
        assertEquals(17000L, split.taxDeductionPaise)
        assertEquals(153000L, split.expertNetEarningPaise)
        assertEquals(
            split.amountPaise,
            split.commissionPaise + split.taxDeductionPaise + split.expertNetEarningPaise
        )
    }

    @Test
    fun `historical booking financial snapshot remains isolated when platform commission changes`() {
        val oldSplit = FinancialLedgerEngine.calculateFinancialSplit(
            amountPaise = 100000L,
            commissionRateBps = 1500L,
            gatewayFeeBps = 200L,
            tdsBps = 0L
        )
        val historicalBooking = Booking(
            id = "bk_old",
            amountPaise = oldSplit.amountPaise,
            commissionRateBps = oldSplit.commissionRateBps,
            commissionPaise = oldSplit.commissionPaise,
            gatewayFeePaise = oldSplit.gatewayFeePaise,
            taxDeductionPaise = oldSplit.taxDeductionPaise,
            expertEarningPaise = oldSplit.expertNetEarningPaise
        )
        // Admin updates default commission to 20% later
        val newSplit = FinancialLedgerEngine.calculateFinancialSplit(
            amountPaise = 100000L,
            commissionRateBps = 2000L,
            gatewayFeeBps = 200L,
            tdsBps = 0L
        )
        assertEquals(1500L, historicalBooking.commissionRateBps)
        assertEquals(15000L, historicalBooking.commissionPaise)
        assertEquals(85000L, historicalBooking.expertEarningPaise)
        assertEquals(20000L, newSplit.commissionPaise)
        assertEquals(80000L, newSplit.expertNetEarningPaise)
    }

    // --- 2. RAZORPAY SIGNATURE & PAYMENT VERIFICATION ---

    @Test
    fun `razorpay hmac sha256 signature verification accepts valid and rejects tampered payloads`() {
        val secret = "test_secret_123"
        val orderId = "order_test_99"
        val paymentId = "pay_test_77"
        val sig = RazorpaySignatureVerifier.computeHmacSha256Hex("$orderId|$paymentId", secret)

        assertTrue(RazorpaySignatureVerifier.verifyOrderSignature(orderId, paymentId, sig, secret))
        assertFalse(RazorpaySignatureVerifier.verifyOrderSignature(orderId, "pay_tampered", sig, secret))

        val webhookBody = """{"event":"payment.captured","payload":{"payment":{"id":"$paymentId"}}}"""
        val webhookSig = RazorpaySignatureVerifier.computeHmacSha256Hex(webhookBody, secret)
        assertTrue(RazorpaySignatureVerifier.verifyWebhookSignature(webhookBody, webhookSig, secret))
        assertFalse(RazorpaySignatureVerifier.verifyWebhookSignature(webhookBody, "deadbeef", secret))
    }

    @Test
    fun `payment consistency check rejects wrong currency amount mismatch and expired hold`() {
        val secret = "test_secret_abc"
        val orderId = "order_1"
        val paymentId = "pay_1"
        val sig = RazorpaySignatureVerifier.computeHmacSha256Hex("$orderId|$paymentId", secret)
        val now = 1_700_000_000_000L

        val valid = RazorpaySignatureVerifier.verifyPaymentConsistency(
            PaymentVerificationRequest(
                orderId = orderId,
                paymentId = paymentId,
                signature = sig,
                expectedAmountPaise = 99900L,
                actualAmountPaise = 99900L,
                currency = "INR",
                holdExpiresAtEpochMs = now + 60_000L,
                nowEpochMs = now
            ),
            signingSecret = secret
        )
        assertTrue(valid is PaymentVerificationResult.Verified)

        val wrongCurrency = RazorpaySignatureVerifier.verifyPaymentConsistency(
            PaymentVerificationRequest(
                orderId = orderId,
                paymentId = paymentId,
                signature = sig,
                expectedAmountPaise = 99900L,
                actualAmountPaise = 99900L,
                currency = "USD",
                holdExpiresAtEpochMs = now + 60_000L,
                nowEpochMs = now
            ),
            signingSecret = secret
        )
        assertTrue(wrongCurrency is PaymentVerificationResult.Rejected)

        val wrongAmount = RazorpaySignatureVerifier.verifyPaymentConsistency(
            PaymentVerificationRequest(
                orderId = orderId,
                paymentId = paymentId,
                signature = sig,
                expectedAmountPaise = 99900L,
                actualAmountPaise = 100L,
                currency = "INR",
                holdExpiresAtEpochMs = now + 60_000L,
                nowEpochMs = now
            ),
            signingSecret = secret
        )
        assertTrue(wrongAmount is PaymentVerificationResult.Rejected)

        val expiredHold = RazorpaySignatureVerifier.verifyPaymentConsistency(
            PaymentVerificationRequest(
                orderId = orderId,
                paymentId = paymentId,
                signature = sig,
                expectedAmountPaise = 99900L,
                actualAmountPaise = 99900L,
                currency = "INR",
                holdExpiresAtEpochMs = now - 1L,
                nowEpochMs = now
            ),
            signingSecret = secret
        )
        assertTrue(expiredHold is PaymentVerificationResult.Rejected)
    }

    // --- 3. SLOT LOCKING & AVAILABILITY ---

    @Test
    fun `slot availability calculation ignores expired holds and respects breaks locks and exceptions`() {
        val futureDate = LocalDate.of(2028, 6, 15) // Thursday -> dayOfWeek = 4
        val dateIso = futureDate.toString()
        val nowMs = ZonedDateTime.of(2028, 6, 14, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
            .toInstant().toEpochMilli()

        val rule = AvailabilityRule(
            id = "rule_4",
            expertId = "exp_1",
            dayOfWeek = TimeSlotUtils.isoDayOfWeek(dateIso).toLong(),
            enabled = true,
            startMinute = 600L, // 10:00 AM
            endMinute = 780L, // 1:00 PM
            breakStartMinute = 660L, // 11:00 AM - 12:00 PM break
            breakEndMinute = 720L
        )

        val confirmedLock = SlotLock(
            id = "lock_1",
            expertId = "exp_1",
            dateIso = dateIso,
            startMinute = 600L,
            endMinute = 630L,
            bookingStatus = BookingStatus.CONFIRMED.name,
            holdExpiresAtEpochMs = nowMs - 5000L
        )
        val expiredHold = SlotLock(
            id = "lock_2",
            expertId = "exp_1",
            dateIso = dateIso,
            startMinute = 630L,
            endMinute = 660L,
            bookingStatus = BookingStatus.PENDING_PAYMENT.name,
            holdExpiresAtEpochMs = nowMs - 1000L
        )
        val activeHold = SlotLock(
            id = "lock_3",
            expertId = "exp_1",
            dateIso = dateIso,
            startMinute = 720L,
            endMinute = 750L,
            bookingStatus = BookingStatus.PENDING_PAYMENT.name,
            holdExpiresAtEpochMs = nowMs + 300_000L
        )
        val cancelledLock = SlotLock(
            id = "lock_4",
            expertId = "exp_1",
            dateIso = dateIso,
            startMinute = 750L,
            endMinute = 780L,
            bookingStatus = BookingStatus.CANCELLED.name,
            holdExpiresAtEpochMs = nowMs + 300_000L
        )

        assertTrue(confirmedLock.isBlocking(nowMs))
        assertFalse(expiredHold.isBlocking(nowMs))
        assertTrue(activeHold.isBlocking(nowMs))
        assertFalse(cancelledLock.isBlocking(nowMs))

        val slots = MarketplaceRepository.computeAvailableSlots(
            dateIso = dateIso,
            durationMinutes = 30,
            rules = listOf(rule),
            exceptions = emptyList(),
            slotLocks = listOf(confirmedLock, expiredHold, activeHold, cancelledLock),
            nowMs = nowMs
        )
        assertEquals(listOf(630, 750), slots.map { it.startMinute })

        val blockedSlots = MarketplaceRepository.computeAvailableSlots(
            dateIso = dateIso,
            durationMinutes = 30,
            rules = listOf(rule),
            exceptions = listOf(AvailabilityException(expertId = "exp_1", dateIso = dateIso, unavailable = true)),
            slotLocks = emptyList(),
            nowMs = nowMs
        )
        assertTrue(blockedSlots.isEmpty())
    }

    // --- 4. BOOKING LIFECYCLE & CANCELLATION POLICIES ---

    @Test
    fun `lifecycle state machine enforces valid transitions and blocks illegal jumps`() {
        assertTrue(ConsultationLifecycleEngine.canTransitionBooking(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED))
        assertTrue(ConsultationLifecycleEngine.canTransitionBooking(BookingStatus.CONFIRMED, BookingStatus.COMPLETED))
        assertTrue(ConsultationLifecycleEngine.canTransitionBooking(BookingStatus.COMPLETED, BookingStatus.DISPUTED))
        assertFalse(ConsultationLifecycleEngine.canTransitionBooking(BookingStatus.PENDING_PAYMENT, BookingStatus.COMPLETED))
        assertFalse(ConsultationLifecycleEngine.canTransitionBooking(BookingStatus.CANCELLED, BookingStatus.COMPLETED))
        assertFalse(ConsultationLifecycleEngine.canTransitionBooking(BookingStatus.REFUNDED, BookingStatus.CONFIRMED))
    }

    @Test
    fun `cancellation refund policy respects 24h notice expert cancel and post-completion lock`() {
        val ist = ZoneId.of("Asia/Kolkata")
        val scheduledDate = LocalDate.of(2028, 8, 10)
        val booking = Booking(
            id = "bk_cancel",
            dateIso = scheduledDate.toString(),
            startMinute = 600L, // 10:00 AM IST
            timezone = "Asia/Kolkata",
            amountPaise = 100000L,
            expertEarningPaise = 85000L,
            bookingStatus = BookingStatus.CONFIRMED.name,
            paymentStatus = PaymentStatus.SUCCESS.name
        )

        val sessionStartMs = ZonedDateTime.of(2028, 8, 10, 10, 0, 0, 0, ist).toInstant().toEpochMilli()

        // Customer cancels 30h before session -> 100% refund
        val earlyDecision = ConsultationLifecycleEngine.evaluateCancellationRefund(
            booking = booking,
            cancelledByExpert = false,
            cancellationWindowHours = 24L,
            nowEpochMs = sessionStartMs - (30L * 3_600_000L)
        )
        assertTrue(earlyDecision.allowed)
        assertEquals(100000L, earlyDecision.refundPaise)

        // Customer cancels 6h before session (<24h) -> 50% refund policy
        val lateDecision = ConsultationLifecycleEngine.evaluateCancellationRefund(
            booking = booking,
            cancelledByExpert = false,
            cancellationWindowHours = 24L,
            nowEpochMs = sessionStartMs - (6L * 3_600_000L)
        )
        assertTrue(lateDecision.allowed)
        assertEquals(50000L, lateDecision.refundPaise)
        assertEquals(42500L, lateDecision.expertCompensationPaise)

        // Expert cancels even 2h before -> 100% customer refund
        val expertDecision = ConsultationLifecycleEngine.evaluateCancellationRefund(
            booking = booking,
            cancelledByExpert = true,
            cancellationWindowHours = 24L,
            nowEpochMs = sessionStartMs - (2L * 3_600_000L)
        )
        assertTrue(expertDecision.allowed)
        assertEquals(100000L, expertDecision.refundPaise)

        // Completed booking cannot be cancelled
        val completedDecision = ConsultationLifecycleEngine.evaluateCancellationRefund(
            booking = booking.copy(bookingStatus = BookingStatus.COMPLETED.name),
            cancelledByExpert = false,
            nowEpochMs = sessionStartMs + 3_600_000L
        )
        assertFalse(completedDecision.allowed)
    }

    // --- 5. GOOGLE MEET ATTENDANCE VERIFICATION (SCENARIOS A - G) ---

    @Test
    fun `google meet attendance scenario A merges reconnect intervals and verifies sufficient overlap`() {
        val startMs = 1_800_000_000_000L
        val m = 60_000L
        // 30-min session -> required = min(30, max(10, ceil(30*0.65)=20)) = 20 mins
        val eval = AttendanceVerificationEngine.evaluateMeetParticipants(
            scheduledStartEpochMs = startMs,
            scheduledDurationMinutes = 30L,
            expectedCustomerEmail = "Customer@Gmail.com",
            expectedExpertEmail = "expert@company.in",
            participants = listOf(
                MeetParticipantEvidence(
                    participantId = "p_cust",
                    displayName = "Customer",
                    signedInGoogleEmail = "customer@gmail.com",
                    signedInUserResource = "users/1",
                    isAnonymousOrPhone = false,
                    // Dropped for 2m then reconnected -> total 25m
                    sessions = listOf(
                        MeetSessionInterval(startMs, startMs + 10 * m),
                        MeetSessionInterval(startMs + 12 * m, startMs + 27 * m)
                    )
                ),
                MeetParticipantEvidence(
                    participantId = "p_exp",
                    displayName = "Expert",
                    signedInGoogleEmail = "expert@company.in",
                    signedInUserResource = "users/2",
                    isAnonymousOrPhone = false,
                    sessions = listOf(MeetSessionInterval(startMs, startMs + 28 * m))
                )
            )
        )
        assertEquals(AttendanceStatus.VERIFIED, eval.status)
        assertEquals(VerificationSource.GOOGLE_MEET_REST_API, eval.verificationSource)
        assertEquals(AttendanceScenario.SCENARIO_A_BOTH_ATTENDED, eval.scenario)
        assertEquals(25L, eval.customerMinutes)
        assertEquals(28L, eval.expertMinutes)
        assertEquals(25L, eval.overlapMinutes)
        assertFalse(eval.requiresAdminReview)
    }

    @Test
    fun `google meet attendance scenarios B C D E F detect no-shows mismatches and short sessions`() {
        val startMs = 1_800_000_000_000L
        val m = 60_000L

        // Scenario B: Expert no-show
        val scenarioB = AttendanceVerificationEngine.evaluateMeetParticipants(
            scheduledStartEpochMs = startMs,
            scheduledDurationMinutes = 30L,
            expectedCustomerEmail = "cust@gmail.com",
            expectedExpertEmail = "exp@gmail.com",
            participants = listOf(
                MeetParticipantEvidence("p1", "Cust", "cust@gmail.com", null, false, listOf(MeetSessionInterval(startMs, startMs + 15 * m)))
            )
        )
        assertEquals(AttendanceScenario.SCENARIO_B_EXPERT_NO_SHOW, scenarioB.scenario)
        assertEquals(AttendanceStatus.FAILED, scenarioB.status)
        assertTrue(scenarioB.requiresAdminReview)

        // Scenario C: Customer no-show
        val scenarioC = AttendanceVerificationEngine.evaluateMeetParticipants(
            scheduledStartEpochMs = startMs,
            scheduledDurationMinutes = 30L,
            expectedCustomerEmail = "cust@gmail.com",
            expectedExpertEmail = "exp@gmail.com",
            participants = listOf(
                MeetParticipantEvidence("p2", "Exp", "exp@gmail.com", null, false, listOf(MeetSessionInterval(startMs, startMs + 16 * m)))
            )
        )
        assertEquals(AttendanceScenario.SCENARIO_C_CUSTOMER_NO_SHOW, scenarioC.scenario)
        assertEquals(AttendanceStatus.PARTIALLY_VERIFIED, scenarioC.status)
        assertTrue(scenarioC.requiresAdminReview)

        // Scenario D: Identity mismatch (wrong Google account / phone caller)
        val scenarioD = AttendanceVerificationEngine.evaluateMeetParticipants(
            scheduledStartEpochMs = startMs,
            scheduledDurationMinutes = 30L,
            expectedCustomerEmail = "cust@gmail.com",
            expectedExpertEmail = "exp@gmail.com",
            participants = listOf(
                MeetParticipantEvidence("p1", "Cust", "cust@gmail.com", null, false, listOf(MeetSessionInterval(startMs, startMs + 25 * m))),
                MeetParticipantEvidence("p2", "Wrong Email", "personal@yahoo.com", null, false, listOf(MeetSessionInterval(startMs, startMs + 25 * m)))
            )
        )
        assertEquals(AttendanceScenario.SCENARIO_D_IDENTITY_MISMATCH, scenarioD.scenario)
        assertEquals(AttendanceStatus.MISMATCH, scenarioD.status)
        assertNotNull(scenarioD.unmatchedDetails)

        // Scenario E: Both joined, overlap < 20 min threshold
        val scenarioE = AttendanceVerificationEngine.evaluateMeetParticipants(
            scheduledStartEpochMs = startMs,
            scheduledDurationMinutes = 30L,
            expectedCustomerEmail = "cust@gmail.com",
            expectedExpertEmail = "exp@gmail.com",
            participants = listOf(
                MeetParticipantEvidence("p1", "Cust", "cust@gmail.com", null, false, listOf(MeetSessionInterval(startMs, startMs + 6 * m))),
                MeetParticipantEvidence("p2", "Exp", "exp@gmail.com", null, false, listOf(MeetSessionInterval(startMs, startMs + 6 * m)))
            )
        )
        assertEquals(AttendanceScenario.SCENARIO_E_INSUFFICIENT_DURATION, scenarioE.scenario)
        assertEquals(AttendanceStatus.MANUAL_REVIEW, scenarioE.status)

        // Scenario F: Neither joined
        val scenarioF = AttendanceVerificationEngine.evaluateMeetParticipants(
            scheduledStartEpochMs = startMs,
            scheduledDurationMinutes = 30L,
            expectedCustomerEmail = "cust@gmail.com",
            expectedExpertEmail = "exp@gmail.com",
            participants = emptyList()
        )
        assertEquals(AttendanceScenario.SCENARIO_F_BOTH_ABSENT, scenarioF.scenario)
        assertEquals(AttendanceStatus.FAILED, scenarioF.status)
    }

    @Test
    fun `scenario G check-in fallback never falsely claims google meet api verification`() {
        val fallbackEval = AttendanceVerificationEngine.evaluateCheckInFallback(
            customerCheckInAtMs = 1000L,
            expertCheckInAtMs = 2000L,
            customerConfirmedDone = true,
            expertConfirmedDone = true
        )
        assertEquals(AttendanceScenario.SCENARIO_G_FALLBACK_CHECKIN_ONLY, fallbackEval.scenario)
        assertEquals(VerificationSource.SUPPLEMENTARY_CHECKIN_FALLBACK, fallbackEval.verificationSource)
        assertEquals(AttendanceStatus.PARTIALLY_VERIFIED, fallbackEval.status)
        assertFalse(fallbackEval.status == AttendanceStatus.VERIFIED)
    }

    // --- 6. PAYOUT ELIGIBILITY, 24H DISPUTE WINDOW & 6-BUCKET WALLET ---

    @Test
    fun `payout eligibility requires verified attendance dual completion and elapsed 24h dispute window`() {
        val nowMs = 1_800_100_000_000L
        val windowEndMs = nowMs + 86_400_000L

        val baseCompleted = Booking(
            id = "bk_elig",
            amountPaise = 100000L,
            commissionPaise = 15000L,
            expertEarningPaise = 85000L,
            bookingStatus = BookingStatus.COMPLETED.name,
            paymentStatus = PaymentStatus.SUCCESS.name,
            providerSettlementStatus = ProviderSettlementStatus.SETTLED.name,
            attendanceStatus = AttendanceStatus.VERIFIED.name,
            customerCompletedConfirmed = true,
            expertCompletedConfirmed = true,
            completedAtEpochMs = nowMs,
            disputeWindowEndsAtEpochMs = windowEndMs,
            disputeStatus = DisputeStatus.NONE.name
        )

        // 1. Inside 24h dispute window -> blocked (NOT_ELIGIBLE)
        val duringWindow = FinancialLedgerEngine.evaluateBookingPayoutEligibility(
            booking = baseCompleted,
            nowEpochMs = nowMs + 3_600_000L
        )
        assertFalse(duringWindow.eligible)
        assertEquals(PayoutEligibilityStatus.NOT_ELIGIBLE, duringWindow.status)

        // 2. Single-party completion -> blocked
        val expertOnlyCompleted = baseCompleted.copy(customerCompletedConfirmed = false)
        val singleCheck = FinancialLedgerEngine.evaluateBookingPayoutEligibility(
            booking = expertOnlyCompleted,
            nowEpochMs = windowEndMs + 1000L
        )
        assertFalse(singleCheck.eligible)

        // 3. Failed or mismatched attendance -> blocked even after 24h
        val noShowCheck = FinancialLedgerEngine.evaluateBookingPayoutEligibility(
            booking = baseCompleted.copy(attendanceStatus = AttendanceStatus.FAILED.name),
            nowEpochMs = windowEndMs + 1000L
        )
        assertFalse(noShowCheck.eligible)

        // 4. Open dispute -> ON_HOLD
        val disputedCheck = FinancialLedgerEngine.evaluateBookingPayoutEligibility(
            booking = baseCompleted.copy(
                bookingStatus = BookingStatus.DISPUTED.name,
                disputeStatus = DisputeStatus.OPEN.name
            ),
            nowEpochMs = windowEndMs + 1000L
        )
        assertFalse(disputedCheck.eligible)
        assertEquals(PayoutEligibilityStatus.ON_HOLD, disputedCheck.status)

        // 5. After 24h window + no dispute + verified + dual confirmed -> ELIGIBLE
        val eligibleCheck = FinancialLedgerEngine.evaluateBookingPayoutEligibility(
            booking = baseCompleted,
            nowEpochMs = windowEndMs + 1000L
        )
        assertTrue(eligibleCheck.eligible)
        assertEquals(PayoutEligibilityStatus.ELIGIBLE, eligibleCheck.status)

        // 6. Admin manual review approval passes attendance gate (requires ADMIN_MANUAL_REVIEW source)
        val adminApprovedCheck = FinancialLedgerEngine.evaluateBookingPayoutEligibility(
            booking = baseCompleted.copy(
                attendanceStatus = AttendanceStatus.MANUAL_REVIEW.name,
                attendanceVerificationSource = VerificationSource.ADMIN_MANUAL_REVIEW.name,
                attendanceNotes = "ADMIN_APPROVED after invoice audit"
            ),
            nowEpochMs = windowEndMs + 1000L
        )
        assertTrue(adminApprovedCheck.eligible)

        // 7. Double payout prevention: PROCESSING or PAID booking cannot become ELIGIBLE again
        assertEquals(
            PayoutEligibilityStatus.PAID,
            FinancialLedgerEngine.evaluateBookingPayoutEligibility(
                baseCompleted.copy(payoutEligibilityStatus = PayoutEligibilityStatus.PAID.name),
                nowEpochMs = windowEndMs + 1000L
            ).status
        )
    }

    @Test
    fun `wallet 6-bucket computation handles eligible pending disputed processing paid partial refunds and failed payout retry`() {
        val nowMs = 1_800_200_000_000L

        // bk1: Eligible Rs 850 (after 24h dispute window)
        val bk1 = Booking(
            id = "bk_1",
            amountPaise = 100000L,
            commissionPaise = 15000L,
            gatewayFeePaise = 2000L,
            expertEarningPaise = 85000L,
            bookingStatus = BookingStatus.COMPLETED.name,
            paymentStatus = PaymentStatus.SUCCESS.name,
            providerSettlementStatus = ProviderSettlementStatus.SETTLED.name,
            attendanceStatus = AttendanceStatus.VERIFIED.name,
            customerCompletedConfirmed = true,
            expertCompletedConfirmed = true,
            completedAtEpochMs = nowMs - 100_000_000L,
            disputeWindowEndsAtEpochMs = nowMs - 10_000_000L
        )
        // bk2: Pending inside 24h window Rs 850
        val bk2 = bk1.copy(
            id = "bk_2",
            completedAtEpochMs = nowMs - 1_000_000L,
            disputeWindowEndsAtEpochMs = nowMs + 50_000_000L
        )
        // bk3: Disputed hold with 50% partial refund already resolved? No, active OPEN dispute Rs 850
        val bk3 = bk1.copy(
            id = "bk_3",
            bookingStatus = BookingStatus.DISPUTED.name,
            disputeStatus = DisputeStatus.OPEN.name
        )
        // bk4: Eligible Rs 850 with 50% partial refund (Rs 425 remaining)
        val bk4 = bk1.copy(
            id = "bk_4",
            paymentStatus = PaymentStatus.PARTIALLY_REFUNDED.name,
            refundedAmountPaise = 50000L
        )

        val ledger = listOf(
            LedgerEntry(entryType = LedgerEntryType.PAYMENT_CAPTURED.name, direction = LedgerDirection.CREDIT.name, amountPaise = 400000L),
            LedgerEntry(entryType = LedgerEntryType.PLATFORM_COMMISSION.name, direction = LedgerDirection.CREDIT.name, amountPaise = 60000L),
            LedgerEntry(entryType = LedgerEntryType.COMMISSION_REVERSAL.name, direction = LedgerDirection.DEBIT.name, amountPaise = 7500L),
            LedgerEntry(entryType = LedgerEntryType.GATEWAY_FEE.name, direction = LedgerDirection.DEBIT.name, amountPaise = 8000L),
            LedgerEntry(entryType = LedgerEntryType.EXPERT_EARNING.name, direction = LedgerDirection.CREDIT.name, amountPaise = 340000L),
            LedgerEntry(entryType = LedgerEntryType.REFUND_COMPLETED.name, direction = LedgerDirection.DEBIT.name, amountPaise = 50000L)
        )

        val payouts = listOf(
            PayoutRequest(id = "pr_paid", amountPaise = 42500L, status = PayoutStatus.PAID.name),
            PayoutRequest(id = "pr_proc", amountPaise = 20000L, status = PayoutStatus.PROCESSING.name),
            // Failed & Rejected payouts must NOT lock or deduct withdrawable balance!
            PayoutRequest(id = "pr_fail", amountPaise = 30000L, status = PayoutStatus.FAILED.name),
            PayoutRequest(id = "pr_rej", amountPaise = 10000L, status = PayoutStatus.REJECTED.name)
        )

        val wallet = FinancialLedgerEngine.computeExpertWalletBreakdown(
            bookings = listOf(bk1, bk2, bk3, bk4),
            ledger = ledger,
            payouts = payouts,
            nowEpochMs = nowMs
        )

        assertEquals(400000L, wallet.grossRevenuePaise)
        assertEquals(52500L, wallet.platformCommissionPaise) // 60000 - 7500 reversal
        assertEquals(8000L, wallet.gatewayFeePaise)
        assertEquals(85000L, wallet.pendingEarningsPaise) // bk2
        assertEquals(85000L, wallet.disputedOrHeldPaise) // bk3
        assertEquals(42500L, wallet.paidOutPaise)
        assertEquals(20000L, wallet.payoutProcessingPaise)
        assertEquals(50000L, wallet.refundedPaise)
        // Eligible from bk1 (85000) + bk4 remaining (42500) = 127500 minus processing (20000) minus paid (42500) = 65000
        assertEquals(65000L, wallet.availableForPayoutPaise)
        assertEquals(listOf("bk_1", "bk_4"), wallet.eligibleBookingIds)
    }

    @Test
    fun `cross-booking cancellation and full refund does not subtract refunded commission from another valid booking`() {
        val nowMs = 1_800_200_000_000L
        val validEligible = Booking(
            id = "bk_valid",
            amountPaise = 100000L,
            commissionRateBps = 1500L,
            commissionPaise = 15000L,
            gatewayFeePaise = 2000L,
            taxDeductionPaise = 0L,
            expertEarningPaise = 85000L,
            bookingStatus = BookingStatus.COMPLETED.name,
            paymentStatus = PaymentStatus.SUCCESS.name,
            providerSettlementStatus = ProviderSettlementStatus.SANDBOX_SETTLED.name,
            attendanceStatus = AttendanceStatus.VERIFIED.name,
            customerCompletedConfirmed = true,
            expertCompletedConfirmed = true,
            completedAtEpochMs = nowMs - 100_000_000L,
            disputeWindowEndsAtEpochMs = nowMs - 10_000_000L
        )
        val cancelledAndRefunded = Booking(
            id = "bk_cancelled_refunded",
            amountPaise = 100000L,
            commissionRateBps = 1500L,
            commissionPaise = 15000L,
            gatewayFeePaise = 2000L,
            taxDeductionPaise = 0L,
            expertEarningPaise = 85000L,
            refundedAmountPaise = 100000L,
            bookingStatus = BookingStatus.REFUNDED.name,
            paymentStatus = PaymentStatus.REFUNDED.name,
            providerSettlementStatus = ProviderSettlementStatus.SANDBOX_SETTLED.name
        )

        // Ledger captures both bookings, plus BOTH cancellation adjustment & full Rs 1,000 refund
        val ledger = listOf(
            LedgerEntry(bookingId = "bk_valid", entryType = LedgerEntryType.PAYMENT_CAPTURED.name, direction = LedgerDirection.CREDIT.name, amountPaise = 100000L),
            LedgerEntry(bookingId = "bk_valid", entryType = LedgerEntryType.PLATFORM_COMMISSION.name, direction = LedgerDirection.CREDIT.name, amountPaise = 15000L),
            LedgerEntry(bookingId = "bk_valid", entryType = LedgerEntryType.GATEWAY_FEE.name, direction = LedgerDirection.DEBIT.name, amountPaise = 2000L),
            LedgerEntry(bookingId = "bk_valid", entryType = LedgerEntryType.EXPERT_EARNING.name, direction = LedgerDirection.CREDIT.name, amountPaise = 85000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.PAYMENT_CAPTURED.name, direction = LedgerDirection.CREDIT.name, amountPaise = 100000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.PLATFORM_COMMISSION.name, direction = LedgerDirection.CREDIT.name, amountPaise = 15000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.GATEWAY_FEE.name, direction = LedgerDirection.DEBIT.name, amountPaise = 2000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.EXPERT_EARNING.name, direction = LedgerDirection.CREDIT.name, amountPaise = 85000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.CANCELLATION_ADJUSTMENT.name, direction = LedgerDirection.DEBIT.name, amountPaise = 85000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.REFUND_COMPLETED.name, direction = LedgerDirection.DEBIT.name, amountPaise = 100000L),
            LedgerEntry(bookingId = "bk_cancelled_refunded", entryType = LedgerEntryType.COMMISSION_REVERSAL.name, direction = LedgerDirection.DEBIT.name, amountPaise = 15000L)
        )

        val wallet = FinancialLedgerEngine.computeExpertWalletBreakdown(
            bookings = listOf(validEligible, cancelledAndRefunded),
            ledger = ledger,
            payouts = emptyList(),
            nowEpochMs = nowMs
        )

        // Valid booking's Rs 850 net earning must remain 85000 paise (never Rs 700 or Rs 0!)
        assertEquals(85000L, wallet.netEarningsPaise)
        assertEquals(85000L, wallet.availableForPayoutPaise)
        assertEquals(15000L, wallet.platformCommissionPaise)
        assertEquals(100000L, wallet.refundedPaise)
        assertEquals(listOf("bk_valid"), wallet.eligibleBookingIds)
    }

    @Test
    fun `thousand rupee consultation exact ledger split verifies 15pct 10pct and 10pct tds`() {
        val mktSplit = FinancialLedgerEngine.calculateFinancialSplit(100000L, 1500L, 200L, 0L)
        assertEquals(15000L, mktSplit.commissionPaise)
        assertEquals(2000L, mktSplit.gatewayFeePaise)
        assertEquals(0L, mktSplit.taxDeductionPaise)
        assertEquals(85000L, mktSplit.expertNetEarningPaise)

        val refSplit = FinancialLedgerEngine.calculateFinancialSplit(100000L, 1000L, 200L, 0L)
        assertEquals(10000L, refSplit.commissionPaise)
        assertEquals(90000L, refSplit.expertNetEarningPaise)

        val tdsSplit = FinancialLedgerEngine.calculateFinancialSplit(100000L, 1500L, 200L, 1000L)
        assertEquals(15000L, tdsSplit.commissionPaise)
        assertEquals(8500L, tdsSplit.taxDeductionPaise)
        assertEquals(76500L, tdsSplit.expertNetEarningPaise)
        assertEquals(100000L, tdsSplit.commissionPaise + tdsSplit.taxDeductionPaise + tdsSplit.expertNetEarningPaise)
    }

    @Test
    fun `safeEnumValueOf safely falls back when reading unknown legacy enum strings`() {
        assertEquals(BookingStatus.PENDING_PAYMENT, safeEnumValueOf("UNKNOWN_LEGACY_STATE", BookingStatus.PENDING_PAYMENT))
        assertEquals(PayoutStatus.PAID, safeEnumValueOf("PAID", PayoutStatus.PENDING))
    }
}
