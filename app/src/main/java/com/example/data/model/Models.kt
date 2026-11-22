package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

inline fun <reified T : Enum<T>> safeEnumValueOf(raw: String?, default: T): T {
    if (raw.isNullOrBlank()) return default
    return enumValues<T>().firstOrNull { it.name == raw } ?: default
}

enum class UserRole { CUSTOMER, EXPERT }
enum class AccountStatus { ACTIVE, SUSPENDED }
enum class VerificationStatus { PENDING, APPROVED, REJECTED }
enum class ReferralType { PLATFORM, EXPERT_REFERRAL }

enum class BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    SCHEDULED,
    IN_PROGRESS,
    ATTENDANCE_PENDING,
    COMPLETION_PENDING,
    COMPLETED,
    CANCELLED,
    DISPUTED,
    REFUNDED
}

enum class PaymentStatus {
    CREATED,
    PENDING,
    SUCCESS,
    FAILED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED
}

enum class AttendanceStatus {
    NOT_STARTED,
    IN_PROGRESS,
    VERIFIED,
    PARTIALLY_VERIFIED,
    MISMATCH,
    MANUAL_REVIEW,
    FAILED
}

enum class PayoutEligibilityStatus {
    NOT_ELIGIBLE,
    ON_HOLD,
    ELIGIBLE,
    PROCESSING,
    PAID,
    FAILED,
    REVERSED
}

enum class ProviderSettlementStatus {
    PENDING_CAPTURE,
    CAPTURED_UNSETTLED,
    SETTLED,
    REFUNDED,
    SANDBOX_SETTLED
}

enum class DisputeStatus {
    NONE,
    OPEN,
    UNDER_REVIEW,
    RESOLVED_REFUND_FULL,
    RESOLVED_REFUND_PARTIAL,
    RESOLVED_RELEASE_EXPERT,
    CLOSED
}

enum class CalendarSyncStatus {
    NOT_SYNCED,
    PENDING_BACKEND_SYNC,
    SYNCED,
    UPDATED,
    CANCELLED,
    SYNC_FAILED
}

enum class VerificationSource {
    NONE,
    GOOGLE_MEET_REST_API,
    SUPPLEMENTARY_CHECKIN_FALLBACK,
    ADMIN_MANUAL_REVIEW
}

enum class PayoutStatus { PENDING, APPROVED, PROCESSING, PAID, FAILED, REJECTED }
enum class ModerationStatus { VISIBLE, HIDDEN }
enum class LedgerDirection { CREDIT, DEBIT }
enum class LedgerEntryStatus { PENDING, SETTLED, REVERSED, FAILED }

enum class LedgerEntryType {
    PAYMENT_CAPTURED,
    PLATFORM_COMMISSION,
    EXPERT_EARNING,
    GATEWAY_FEE,
    TAX_DEDUCTION,
    CANCELLATION_ADJUSTMENT,
    DISPUTE_HOLD,
    DISPUTE_RELEASE,
    REFUND_INITIATED,
    REFUND_COMPLETED,
    COMMISSION_REVERSAL,
    PAYOUT_INITIATED,
    PAYOUT_COMPLETED,
    PAYOUT_FAILED,
    PAYOUT_DISBURSED,
    RECONCILIATION_ADJUSTMENT
}

@Keep
@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val verifiedGoogleEmail: String = "",
    val emailVerified: Boolean = false,
    val role: String = UserRole.CUSTOMER.name,
    val accountStatus: String = AccountStatus.ACTIVE.name,
    val savedExpertIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun roleEnum(): UserRole = safeEnumValueOf(role, UserRole.CUSTOMER)
    fun statusEnum(): AccountStatus = safeEnumValueOf(accountStatus, AccountStatus.ACTIVE)

    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "displayName" to displayName,
        "verifiedGoogleEmail" to verifiedGoogleEmail,
        "emailVerified" to emailVerified,
        "role" to role,
        "accountStatus" to accountStatus,
        "savedExpertIds" to savedExpertIds,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class UserPrivateInfo(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val phone: String = "",
    val city: String = "",
    val notifyBookings: Boolean = true,
    val notifyPayments: Boolean = true,
    val notifyReviews: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toWriteMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "email" to email,
        "phone" to phone,
        "city" to city,
        "notifyBookings" to notifyBookings,
        "notifyPayments" to notifyPayments,
        "notifyReviews" to notifyReviews,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class Specialisation(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    val sortOrder: Long = 0L,
    val active: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ExpertProfile(
    @DocumentId val id: String = "",
    val expertUid: String = "",
    val fullName: String = "",
    val verifiedGoogleEmail: String = "",
    val googleEmailVerified: Boolean = false,
    val headline: String = "",
    val designation: String = "",
    val organization: String? = null,
    val yearsExperience: Long = 0L,
    val bio: String = "",
    val primarySpecialisation: String = "",
    val specialisations: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val industryExperience: String? = null,
    val linkedInUrl: String? = null,
    val introVideoUrl: String? = null,
    val verificationNotes: String? = null,
    val verificationStatus: String = VerificationStatus.PENDING.name,
    val rejectionReason: String? = null,
    val accountStatus: String = AccountStatus.ACTIVE.name,
    val demoProfile: Boolean = false,
    val startingPriceInr: Long = 0L,
    val referralCode: String = "",
    val timezone: String = "Asia/Kolkata",
    val payoutAccountType: String? = null,
    val payoutDestinationMasked: String? = null,
    val payoutKycStatus: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun verificationEnum(): VerificationStatus = safeEnumValueOf(verificationStatus, VerificationStatus.PENDING)
    fun accountStatusEnum(): AccountStatus = safeEnumValueOf(accountStatus, AccountStatus.ACTIVE)

    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(): Map<String, Any> = mapOf(
        "expertUid" to expertUid,
        "fullName" to fullName,
        "verifiedGoogleEmail" to verifiedGoogleEmail.ifBlank { null },
        "googleEmailVerified" to googleEmailVerified,
        "headline" to headline,
        "designation" to designation,
        "organization" to organization?.takeIf { it.isNotBlank() },
        "yearsExperience" to yearsExperience,
        "bio" to bio,
        "primarySpecialisation" to primarySpecialisation,
        "specialisations" to specialisations,
        "skills" to skills,
        "industryExperience" to industryExperience?.takeIf { it.isNotBlank() },
        "linkedInUrl" to linkedInUrl?.takeIf { it.isNotBlank() },
        "introVideoUrl" to introVideoUrl?.takeIf { it.isNotBlank() },
        "verificationNotes" to verificationNotes?.takeIf { it.isNotBlank() },
        "verificationStatus" to verificationStatus,
        "rejectionReason" to rejectionReason?.takeIf { it.isNotBlank() },
        "accountStatus" to accountStatus,
        "demoProfile" to demoProfile,
        "startingPriceInr" to startingPriceInr,
        "referralCode" to referralCode,
        "timezone" to timezone,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class ConsultationService(
    @DocumentId val id: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val expertName: String = "",
    val title: String = "",
    val description: String = "",
    val specialisation: String = "",
    val durationMinutes: Long = 30L,
    val priceInr: Long = 1000L,
    val pricePaise: Long = 100000L,
    val consultationFormat: String = "Video Consultation",
    val preparationInstructions: String? = null,
    val active: Boolean = true,
    val demoService: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(): Map<String, Any> = mapOf(
        "expertId" to expertId,
        "expertUid" to expertUid,
        "expertName" to expertName,
        "title" to title,
        "description" to description,
        "specialisation" to specialisation,
        "durationMinutes" to durationMinutes,
        "priceInr" to priceInr,
        "pricePaise" to (priceInr * 100L),
        "consultationFormat" to consultationFormat,
        "preparationInstructions" to preparationInstructions?.takeIf { it.isNotBlank() },
        "active" to active,
        "demoService" to demoService,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null } as Map<String, Any>
}

@Keep
@IgnoreExtraProperties
data class AvailabilityRule(
    @DocumentId val id: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val dayOfWeek: Long = 1L,
    val enabled: Boolean = true,
    val startMinute: Long = 600L,
    val endMinute: Long = 1080L,
    val breakStartMinute: Long = 780L,
    val breakEndMinute: Long = 840L,
    val timezone: String = "Asia/Kolkata",
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toWriteMap(): Map<String, Any> = mapOf(
        "expertId" to expertId,
        "expertUid" to expertUid,
        "dayOfWeek" to dayOfWeek,
        "enabled" to enabled,
        "startMinute" to startMinute,
        "endMinute" to endMinute,
        "breakStartMinute" to breakStartMinute,
        "breakEndMinute" to breakEndMinute,
        "timezone" to timezone,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class AvailabilityException(
    @DocumentId val id: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val dateIso: String = "",
    val unavailable: Boolean = true,
    val reason: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toWriteMap(): Map<String, Any> = mapOf(
        "expertId" to expertId,
        "expertUid" to expertUid,
        "dateIso" to dateIso,
        "unavailable" to unavailable,
        "reason" to reason,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class Booking(
    @DocumentId val id: String = "",
    val customerUid: String = "",
    val customerName: String = "",
    val verifiedCustomerGoogleEmail: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val expertName: String = "",
    val verifiedExpertGoogleEmail: String = "",
    val serviceId: String = "",
    val serviceTitle: String = "",
    val durationMinutes: Long = 30L,
    val dateIso: String = "",
    val startMinute: Long = 0L,
    val endMinute: Long = 0L,
    val slotKey: String = "",
    val timezone: String = "Asia/Kolkata",
    val topic: String = "",
    val preparationInstructions: String? = null,
    val referralType: String = ReferralType.PLATFORM.name,
    val referralCode: String? = null,
    val amountPaise: Long = 0L,
    val commissionRateBps: Long = 1500L,
    val commissionPaise: Long = 0L,
    val gatewayFeePaise: Long = 0L,
    val taxDeductionPaise: Long = 0L,
    val expertEarningPaise: Long = 0L,
    val refundedAmountPaise: Long = 0L,
    val bookingStatus: String = BookingStatus.PENDING_PAYMENT.name,
    val paymentStatus: String = PaymentStatus.CREATED.name,
    val providerSettlementStatus: String = ProviderSettlementStatus.PENDING_CAPTURE.name,
    val attendanceStatus: String = AttendanceStatus.NOT_STARTED.name,
    val attendanceVerificationSource: String = VerificationSource.NONE.name,
    val payoutEligibilityStatus: String = PayoutEligibilityStatus.NOT_ELIGIBLE.name,
    val disputeStatus: String = DisputeStatus.NONE.name,
    val calendarSyncStatus: String = CalendarSyncStatus.NOT_SYNCED.name,
    val holdExpiresAtEpochMs: Long = 0L,
    val meetingUrl: String? = null,
    val googleCalendarEventId: String? = null,
    val googleMeetSpaceId: String? = null,
    val googleMeetCode: String? = null,
    val conferenceRequestId: String? = null,
    val meetingAccessMode: String = "TRUSTED_INVITEES_ONLY",
    val checkInPin: String? = null,
    val customerCheckInAtMs: Long = 0L,
    val expertCheckInAtMs: Long = 0L,
    val customerJoinedMinutes: Long = 0L,
    val expertJoinedMinutes: Long = 0L,
    val overlapMinutes: Long = 0L,
    val attendanceNotes: String? = null,
    val customerCompletedConfirmed: Boolean = false,
    val expertCompletedConfirmed: Boolean = false,
    val completedAtEpochMs: Long = 0L,
    val disputeWindowEndsAtEpochMs: Long = 0L,
    val disputeReason: String? = null,
    val disputeResolution: String? = null,
    val cancellationReason: String? = null,
    val cancelledByUid: String? = null,
    val rescheduleCount: Long = 0L,
    val rescheduledAtEpochMs: Long = 0L,
    val reviewed: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun bookingStatusEnum(): BookingStatus = safeEnumValueOf(bookingStatus, BookingStatus.PENDING_PAYMENT)
    fun paymentStatusEnum(): PaymentStatus = safeEnumValueOf(paymentStatus, PaymentStatus.CREATED)
    fun referralTypeEnum(): ReferralType = safeEnumValueOf(referralType, ReferralType.PLATFORM)
    fun attendanceStatusEnum(): AttendanceStatus = safeEnumValueOf(attendanceStatus, AttendanceStatus.NOT_STARTED)
    fun attendanceSourceEnum(): VerificationSource = safeEnumValueOf(attendanceVerificationSource, VerificationSource.NONE)
    fun payoutEligibilityEnum(): PayoutEligibilityStatus = safeEnumValueOf(payoutEligibilityStatus, PayoutEligibilityStatus.NOT_ELIGIBLE)
    fun providerSettlementEnum(): ProviderSettlementStatus = safeEnumValueOf(providerSettlementStatus, ProviderSettlementStatus.PENDING_CAPTURE)
    fun disputeStatusEnum(): DisputeStatus = safeEnumValueOf(disputeStatus, DisputeStatus.NONE)
    fun calendarSyncEnum(): CalendarSyncStatus = safeEnumValueOf(calendarSyncStatus, CalendarSyncStatus.NOT_SYNCED)

    fun isHoldExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        bookingStatusEnum() == BookingStatus.PENDING_PAYMENT && nowMs > holdExpiresAtEpochMs

    fun isDisputeWindowOpen(nowMs: Long = System.currentTimeMillis()): Boolean =
        disputeWindowEndsAtEpochMs > 0L && nowMs <= disputeWindowEndsAtEpochMs
}

@Keep
@IgnoreExtraProperties
data class SlotLock(
    @DocumentId val id: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val holderUid: String = "",
    val bookingId: String = "",
    val dateIso: String = "",
    val startMinute: Long = 0L,
    val endMinute: Long = 0L,
    val bookingStatus: String = BookingStatus.PENDING_PAYMENT.name,
    val holdExpiresAtEpochMs: Long = 0L,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isBlocking(nowMs: Long = System.currentTimeMillis()): Boolean {
        val status = safeEnumValueOf(bookingStatus, BookingStatus.CANCELLED)
        return when (status) {
            BookingStatus.CONFIRMED,
            BookingStatus.SCHEDULED,
            BookingStatus.IN_PROGRESS,
            BookingStatus.ATTENDANCE_PENDING,
            BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED,
            BookingStatus.DISPUTED -> true
            BookingStatus.PENDING_PAYMENT -> nowMs <= holdExpiresAtEpochMs
            BookingStatus.CANCELLED, BookingStatus.REFUNDED -> false
        }
    }
}

@Keep
@IgnoreExtraProperties
data class PaymentRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerUid: String = "",
    val expertUid: String = "",
    val razorpayOrderId: String = "",
    val razorpayPaymentId: String? = null,
    val razorpaySignature: String? = null,
    val paymentMethod: String = "UPI",
    val currency: String = "INR",
    val amountPaise: Long = 0L,
    val commissionPaise: Long = 0L,
    val gatewayFeePaise: Long = 0L,
    val taxDeductionPaise: Long = 0L,
    val expertEarningPaise: Long = 0L,
    val refundedAmountPaise: Long = 0L,
    val paymentStatus: String = PaymentStatus.CREATED.name,
    val providerSettlementStatus: String = ProviderSettlementStatus.PENDING_CAPTURE.name,
    val serverVerified: Boolean = false,
    val webhookEventId: String? = null,
    val idempotencyKey: String = "",
    val testMode: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): PaymentStatus = safeEnumValueOf(paymentStatus, PaymentStatus.CREATED)
}

@Keep
@IgnoreExtraProperties
data class LedgerEntry(
    @DocumentId val id: String = "",
    val entryType: String = LedgerEntryType.PAYMENT_CAPTURED.name,
    val direction: String = LedgerDirection.CREDIT.name,
    val entryStatus: String = LedgerEntryStatus.SETTLED.name,
    val bookingId: String? = null,
    val paymentId: String? = null,
    val payoutRequestId: String? = null,
    val customerUid: String = "",
    val expertUid: String = "",
    val amountPaise: Long = 0L,
    val currency: String = "INR",
    val idempotencyKey: String = "",
    val description: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun typeEnum(): LedgerEntryType = safeEnumValueOf(entryType, LedgerEntryType.PAYMENT_CAPTURED)
    fun directionEnum(): LedgerDirection = safeEnumValueOf(direction, LedgerDirection.CREDIT)
    fun statusEnum(): LedgerEntryStatus = safeEnumValueOf(entryStatus, LedgerEntryStatus.SETTLED)
}

@Keep
@IgnoreExtraProperties
data class AttendanceRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerUid: String = "",
    val expertUid: String = "",
    val expectedCustomerEmail: String = "",
    val expectedExpertEmail: String = "",
    val conferenceRecordId: String? = null,
    val googleMeetSpaceId: String? = null,
    val verificationSource: String = VerificationSource.NONE.name,
    val attendanceStatus: String = AttendanceStatus.NOT_STARTED.name,
    val customerJoinedAtEpochMs: Long = 0L,
    val customerLeftAtEpochMs: Long = 0L,
    val customerDurationMinutes: Long = 0L,
    val expertJoinedAtEpochMs: Long = 0L,
    val expertLeftAtEpochMs: Long = 0L,
    val expertDurationMinutes: Long = 0L,
    val overlapMinutes: Long = 0L,
    val minimumRequiredMinutes: Long = 10L,
    val unmatchedParticipantDetails: String? = null,
    val evaluatorSummary: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DisputeRecord(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val customerUid: String = "",
    val expertUid: String = "",
    val raisedByUid: String = "",
    val reason: String = "",
    val evidenceSummary: String = "",
    val status: String = DisputeStatus.OPEN.name,
    val requestedRefundPaise: Long = 0L,
    val approvedRefundPaise: Long = 0L,
    val resolutionNote: String? = null,
    val resolvedByUid: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): DisputeStatus = safeEnumValueOf(status, DisputeStatus.OPEN)
}

@Keep
@IgnoreExtraProperties
data class PayoutRequest(
    @DocumentId val id: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val expertName: String = "",
    val amountPaise: Long = 0L,
    val payoutDestination: String = "",
    val eligibleBookingIds: List<String> = emptyList(),
    val idempotencyKey: String = "",
    val status: String = PayoutStatus.PENDING.name,
    val payoutReference: String? = null,
    val providerTransferId: String? = null,
    val failureReason: String? = null,
    val retryCount: Long = 0L,
    val adminNote: String? = null,
    val processedByUid: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): PayoutStatus = safeEnumValueOf(status, PayoutStatus.PENDING)
}

@Keep
@IgnoreExtraProperties
data class Review(
    @DocumentId val id: String = "",
    val bookingId: String = "",
    val expertId: String = "",
    val expertUid: String = "",
    val customerUid: String = "",
    val customerName: String = "",
    val rating: Long = 5L,
    val comment: String = "",
    val reported: Boolean = false,
    val reportReason: String? = null,
    val moderationStatus: String = ModerationStatus.VISIBLE.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AppNotification(
    @DocumentId val id: String = "",
    val recipientUid: String = "",
    val title: String = "",
    val message: String = "",
    val eventType: String = "",
    val relatedBookingId: String? = null,
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PlatformSettings(
    @DocumentId val id: String = "",
    val brandName: String = "HRConnect",
    val platformCommissionBps: Long = 1500L,
    val referralCommissionBps: Long = 1000L,
    val gatewayFeeBps: Long = 200L,
    val gstOnCommissionBps: Long = 1800L,
    val tdsBps: Long = 0L,
    val paymentHoldMinutes: Long = 10L,
    val cancellationWindowHours: Long = 24L,
    val disputeWindowHours: Long = 24L,
    val minMeetAttendanceMinutes: Long = 10L,
    val minMeetAttendanceRatioBps: Long = 6500L,
    val defaultTimezone: String = "Asia/Kolkata",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val actorUid: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class TimeSlotOption(
    val dateIso: String,
    val startMinute: Int,
    val endMinute: Int,
    val label: String
)

data class ExpertRatingStats(
    val averageRating: Double,
    val reviewCount: Int,
    val distribution: Map<Int, Int>
)
