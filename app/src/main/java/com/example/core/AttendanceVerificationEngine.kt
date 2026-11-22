package com.example.core

import com.example.data.model.AttendanceStatus
import com.example.data.model.VerificationSource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class MeetSessionInterval(
    val startEpochMs: Long,
    val endEpochMs: Long
)

data class MeetParticipantEvidence(
    val participantId: String,
    val displayName: String,
    val signedInGoogleEmail: String?,
    val signedInUserResource: String?,
    val isAnonymousOrPhone: Boolean,
    val sessions: List<MeetSessionInterval>
)

enum class AttendanceScenario {
    SCENARIO_A_BOTH_ATTENDED,
    SCENARIO_B_EXPERT_NO_SHOW,
    SCENARIO_C_CUSTOMER_NO_SHOW,
    SCENARIO_D_IDENTITY_MISMATCH,
    SCENARIO_E_INSUFFICIENT_DURATION,
    SCENARIO_F_BOTH_ABSENT,
    SCENARIO_G_FALLBACK_CHECKIN_ONLY
}

data class MeetAttendanceEvaluation(
    val status: AttendanceStatus,
    val verificationSource: VerificationSource,
    val scenario: AttendanceScenario,
    val customerMinutes: Long,
    val expertMinutes: Long,
    val overlapMinutes: Long,
    val minimumRequiredMinutes: Long,
    val requiresAdminReview: Boolean,
    val summary: String,
    val unmatchedDetails: String? = null
)

object AttendanceVerificationEngine {

    /**
     * Evaluates Google Meet REST API conference participant sessions against verified booking Google emails
     * and scheduled session window (with configurable grace & minimum overlap threshold).
     */
    fun evaluateMeetParticipants(
        scheduledStartEpochMs: Long,
        scheduledDurationMinutes: Long,
        expectedCustomerEmail: String,
        expectedExpertEmail: String,
        participants: List<MeetParticipantEvidence>,
        minAttendanceRatio: Double = BrandConfig.DEFAULT_MIN_ATTENDANCE_RATIO,
        minAttendanceFloorMinutes: Long = BrandConfig.DEFAULT_MIN_ATTENDANCE_FLOOR_MINUTES,
        earlyGraceMinutes: Long = 10L,
        lateGraceMinutes: Long = 15L
    ): MeetAttendanceEvaluation {
        val requiredMins = min(
            scheduledDurationMinutes,
            max(minAttendanceFloorMinutes, ceil(scheduledDurationMinutes * minAttendanceRatio).toLong())
        ).coerceAtLeast(1L)

        val windowStart = scheduledStartEpochMs - (earlyGraceMinutes * 60_000L)
        val windowEnd = scheduledStartEpochMs + ((scheduledDurationMinutes + lateGraceMinutes) * 60_000L)

        val normCustomerEmail = expectedCustomerEmail.trim().lowercase()
        val normExpertEmail = expectedExpertEmail.trim().lowercase()

        val customerIntervals = mutableListOf<MeetSessionInterval>()
        val expertIntervals = mutableListOf<MeetSessionInterval>()
        val unmatched = mutableListOf<String>()

        for (p in participants) {
            val email = p.signedInGoogleEmail?.trim()?.lowercase().orEmpty()
            val clipped = p.sessions.mapNotNull { clipInterval(it, windowStart, windowEnd) }
            if (clipped.isEmpty()) continue

            when {
                email.isNotBlank() && email == normCustomerEmail -> customerIntervals.addAll(clipped)
                email.isNotBlank() && email == normExpertEmail -> expertIntervals.addAll(clipped)
                p.isAnonymousOrPhone -> unmatched.add("Unverified/Phone caller '${p.displayName}' (${p.participantId})")
                email.isNotBlank() -> unmatched.add("Unrecognized Google account '$email'")
                else -> unmatched.add("Unmapped Google resource '${p.signedInUserResource ?: p.participantId}' (${p.displayName})")
            }
        }

        val mergedCustomer = mergeIntervals(customerIntervals)
        val mergedExpert = mergeIntervals(expertIntervals)
        val overlapIntervals = computeIntersection(mergedCustomer, mergedExpert)

        val custMins = totalMinutes(mergedCustomer)
        val expMins = totalMinutes(mergedExpert)
        val overlapMins = totalMinutes(overlapIntervals)
        val unmatchedText = unmatched.takeIf { it.isNotEmpty() }?.joinToString("; ")

        // Scenario D: identity mismatch or guest caller when verified user expected
        if (unmatched.isNotEmpty() && (custMins < requiredMins || expMins < requiredMins)) {
            return MeetAttendanceEvaluation(
                status = AttendanceStatus.MISMATCH,
                verificationSource = VerificationSource.GOOGLE_MEET_REST_API,
                scenario = AttendanceScenario.SCENARIO_D_IDENTITY_MISMATCH,
                customerMinutes = custMins,
                expertMinutes = expMins,
                overlapMinutes = overlapMins,
                minimumRequiredMinutes = requiredMins,
                requiresAdminReview = true,
                summary = "Identity mismatch or unverified caller detected in Google Meet.",
                unmatchedDetails = unmatchedText
            )
        }

        // Scenario A: Both joined & overlapped for >= threshold
        if (custMins >= requiredMins && expMins >= requiredMins && overlapMins >= requiredMins) {
            return MeetAttendanceEvaluation(
                status = AttendanceStatus.VERIFIED,
                verificationSource = VerificationSource.GOOGLE_MEET_REST_API,
                scenario = AttendanceScenario.SCENARIO_A_BOTH_ATTENDED,
                customerMinutes = custMins,
                expertMinutes = expMins,
                overlapMinutes = overlapMins,
                minimumRequiredMinutes = requiredMins,
                requiresAdminReview = false,
                summary = "Verified via Google Meet REST API: Customer ${custMins}m, Expert ${expMins}m, Overlap ${overlapMins}m (req >= ${requiredMins}m)."
            )
        }

        // Scenario B: Expert no-show (Customer joined/waited, Expert absent)
        if (custMins >= 5L && expMins == 0L) {
            return MeetAttendanceEvaluation(
                status = AttendanceStatus.FAILED,
                verificationSource = VerificationSource.GOOGLE_MEET_REST_API,
                scenario = AttendanceScenario.SCENARIO_B_EXPERT_NO_SHOW,
                customerMinutes = custMins,
                expertMinutes = 0L,
                overlapMinutes = 0L,
                minimumRequiredMinutes = requiredMins,
                requiresAdminReview = true,
                summary = "Expert no-show: Customer waited ${custMins}m, Expert never joined."
            )
        }

        // Scenario C: Customer no-show (Expert waited, Customer absent)
        if (expMins >= 5L && custMins == 0L) {
            return MeetAttendanceEvaluation(
                status = AttendanceStatus.PARTIALLY_VERIFIED,
                verificationSource = VerificationSource.GOOGLE_MEET_REST_API,
                scenario = AttendanceScenario.SCENARIO_C_CUSTOMER_NO_SHOW,
                customerMinutes = 0L,
                expertMinutes = expMins,
                overlapMinutes = 0L,
                minimumRequiredMinutes = requiredMins,
                requiresAdminReview = true,
                summary = "Customer no-show: Expert joined and waited ${expMins}m; customer absent."
            )
        }

        // Scenario E: Both joined, overlap < required threshold
        if (custMins > 0L && expMins > 0L) {
            return MeetAttendanceEvaluation(
                status = AttendanceStatus.MANUAL_REVIEW,
                verificationSource = VerificationSource.GOOGLE_MEET_REST_API,
                scenario = AttendanceScenario.SCENARIO_E_INSUFFICIENT_DURATION,
                customerMinutes = custMins,
                expertMinutes = expMins,
                overlapMinutes = overlapMins,
                minimumRequiredMinutes = requiredMins,
                requiresAdminReview = true,
                summary = "Short session: Overlap ${overlapMins}m is below required ${requiredMins}m threshold. Flagged for admin review."
            )
        }

        // Scenario F: Neither joined
        return MeetAttendanceEvaluation(
            status = AttendanceStatus.FAILED,
            verificationSource = VerificationSource.GOOGLE_MEET_REST_API,
            scenario = AttendanceScenario.SCENARIO_F_BOTH_ABSENT,
            customerMinutes = custMins,
            expertMinutes = expMins,
            overlapMinutes = overlapMins,
            minimumRequiredMinutes = requiredMins,
            requiresAdminReview = true,
            summary = "Neither verified participant recorded attendance in Google Meet."
        )
    }

    /**
     * Explicit fallback evaluator when Meet REST API conference records are unavailable
     * (e.g., consumer Gmail organizer without Google Workspace Meet API access).
     * NEVER pretends to be Google Meet attendance verification!
     */
    fun evaluateCheckInFallback(
        customerCheckInAtMs: Long,
        expertCheckInAtMs: Long,
        customerConfirmedDone: Boolean,
        expertConfirmedDone: Boolean
    ): MeetAttendanceEvaluation {
        val bothCheckedIn = customerCheckInAtMs > 0L && expertCheckInAtMs > 0L
        return MeetAttendanceEvaluation(
            status = if (bothCheckedIn && customerConfirmedDone && expertConfirmedDone) {
                AttendanceStatus.PARTIALLY_VERIFIED
            } else {
                AttendanceStatus.MANUAL_REVIEW
            },
            verificationSource = VerificationSource.SUPPLEMENTARY_CHECKIN_FALLBACK,
            scenario = AttendanceScenario.SCENARIO_G_FALLBACK_CHECKIN_ONLY,
            customerMinutes = 0L,
            expertMinutes = 0L,
            overlapMinutes = 0L,
            minimumRequiredMinutes = 0L,
            requiresAdminReview = !bothCheckedIn,
            summary = if (bothCheckedIn) {
                "Supplementary check-in recorded by both parties (not Google Meet API verification)."
            } else {
                "Google Meet API unavailable; awaiting both parties' check-in or admin review."
            }
        )
    }

    private fun clipInterval(interval: MeetSessionInterval, minMs: Long, maxMs: Long): MeetSessionInterval? {
        val s = max(interval.startEpochMs, minMs)
        val e = min(interval.endEpochMs, maxMs)
        return if (e > s) MeetSessionInterval(s, e) else null
    }

    fun mergeIntervals(intervals: List<MeetSessionInterval>): List<MeetSessionInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.startEpochMs }
        val result = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val prev = result.last()
            val cur = sorted[i]
            if (cur.startEpochMs <= prev.endEpochMs) {
                result[result.lastIndex] = MeetSessionInterval(prev.startEpochMs, max(prev.endEpochMs, cur.endEpochMs))
            } else {
                result.add(cur)
            }
        }
        return result
    }

    fun computeIntersection(a: List<MeetSessionInterval>, b: List<MeetSessionInterval>): List<MeetSessionInterval> {
        val out = mutableListOf<MeetSessionInterval>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val start = max(a[i].startEpochMs, b[j].startEpochMs)
            val end = min(a[i].endEpochMs, b[j].endEpochMs)
            if (end > start) {
                out.add(MeetSessionInterval(start, end))
            }
            if (a[i].endEpochMs < b[j].endEpochMs) i++ else j++
        }
        return out
    }

    private fun totalMinutes(intervals: List<MeetSessionInterval>): Long =
        intervals.sumOf { (it.endEpochMs - it.startEpochMs).coerceAtLeast(0L) } / 60_000L
}
