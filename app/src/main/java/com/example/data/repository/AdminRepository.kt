package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.BrandConfig
import com.example.core.CurrencyUtils
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AccountStatus
import com.example.data.model.AttendanceRecord
import com.example.data.model.AttendanceStatus
import com.example.data.model.AuditLog
import com.example.data.model.AvailabilityRule
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.ConsultationService
import com.example.data.model.DisputeRecord
import com.example.data.model.DisputeStatus
import com.example.data.model.ExpertProfile
import com.example.data.model.LedgerDirection
import com.example.data.model.LedgerEntry
import com.example.data.model.LedgerEntryStatus
import com.example.data.model.LedgerEntryType
import com.example.data.model.ModerationStatus
import com.example.data.model.PaymentRecord
import com.example.data.model.PaymentStatus
import com.example.data.model.PayoutEligibilityStatus
import com.example.data.model.PayoutRequest
import com.example.data.model.PayoutStatus
import com.example.data.model.Review
import com.example.data.model.Specialisation
import com.example.data.model.UserAccount
import com.example.data.model.VerificationSource
import com.example.data.model.VerificationStatus
import com.example.data.remote.BackendCallResult
import com.example.data.remote.MarketplaceBackendClient
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class AdminRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val backendClient: MarketplaceBackendClient = MarketplaceBackendClient(auth)
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireAdminIdentity(): Pair<String, String> {
        val user = auth.currentUser
            ?: throw IllegalStateException("Admin must be signed in.")
        return user.uid to (user.email ?: "")
    }

    fun observeAllUsers(): Flow<List<UserAccount>> =
        observeAdminCollection("users") { snap ->
            snap.toObjects(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllExperts(): Flow<List<ExpertProfile>> =
        observeAdminCollection("experts") { snap ->
            snap.toObjects(ExpertProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllBookings(): Flow<List<Booking>> =
        observeAdminCollection("bookings") { snap ->
            snap.toObjects(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllPayments(): Flow<List<PaymentRecord>> =
        observeAdminCollection("payments") { snap ->
            snap.toObjects(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllLedgerEntries(): Flow<List<LedgerEntry>> =
        observeAdminCollection("ledger_entries") { snap ->
            snap.toObjects(LedgerEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllAttendanceRecords(): Flow<List<AttendanceRecord>> =
        observeAdminCollection("attendance_records") { snap ->
            snap.toObjects(AttendanceRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }

    fun observeAllDisputes(): Flow<List<DisputeRecord>> =
        observeAdminCollection("disputes") { snap ->
            snap.toObjects(DisputeRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllPayoutRequests(): Flow<List<PayoutRequest>> =
        observeAdminCollection("payout_requests") { snap ->
            snap.toObjects(PayoutRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllReviews(): Flow<List<Review>> =
        observeAdminCollection("reviews") { snap ->
            snap.toObjects(Review::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    fun observeAllSpecialisations(): Flow<List<Specialisation>> =
        observeAdminCollection("specialisations") { snap ->
            snap.toObjects(Specialisation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedBy { it.sortOrder }
        }

    fun observeAuditLogs(): Flow<List<AuditLog>> =
        observeAdminCollection("audit_logs") { snap ->
            snap.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    private fun <T> observeAdminCollection(
        collection: String,
        mapper: (com.google.firebase.firestore.QuerySnapshot) -> List<T>
    ): Flow<List<T>> {
        val uid = auth.currentUser?.uid ?: ""
        return db.collection(collection)
            .snapshots()
            .map(mapper)
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, collection)
                }
                throw error
            }
    }

    suspend fun reviewExpertApplication(
        expert: ExpertProfile,
        approve: Boolean,
        rejectionReason: String = ""
    ): Result<Unit> {
        val path = "experts/${expert.id}"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val newStatus = if (approve) VerificationStatus.APPROVED.name else VerificationStatus.REJECTED.name
            val batch = db.batch()
            val updates = mutableMapOf<String, Any>(
                "verificationStatus" to newStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!approve) {
                updates["rejectionReason"] = rejectionReason.trim().ifBlank {
                    "Please add more verified HR experience details."
                }.take(500)
            }
            batch.update(db.collection("experts").document(expert.id), updates)
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to expert.expertUid,
                    "title" to if (approve) "Expert Application Approved" else "Expert Application Update",
                    "message" to if (approve) {
                        "Welcome to HRConnect! You can now publish consultation services and set weekly availability."
                    } else {
                        "Your application needs updates: ${updates["rejectionReason"]}"
                    },
                    "eventType" to if (approve) "EXPERT_APPROVED" else "EXPERT_REJECTED",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(
                    adminUid, adminEmail,
                    if (approve) "APPROVE_EXPERT" else "REJECT_EXPERT",
                    "expert", expert.id,
                    "${expert.fullName} ($newStatus)"
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun toggleExpertAccountStatus(expert: ExpertProfile): Result<Unit> {
        val path = "experts/${expert.id}"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val next = if (expert.accountStatus == AccountStatus.ACTIVE.name) {
                AccountStatus.SUSPENDED.name
            } else {
                AccountStatus.ACTIVE.name
            }
            val batch = db.batch()
            batch.update(
                db.collection("experts").document(expert.id),
                mapOf("accountStatus" to next, "updatedAt" to FieldValue.serverTimestamp())
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(adminUid, adminEmail, "SET_EXPERT_STATUS", "expert", expert.id, "${expert.fullName} -> $next")
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun toggleCustomerAccountStatus(user: UserAccount): Result<Unit> {
        val path = "users/${user.userId}"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val next = if (user.accountStatus == AccountStatus.ACTIVE.name) {
                AccountStatus.SUSPENDED.name
            } else {
                AccountStatus.ACTIVE.name
            }
            val batch = db.batch()
            batch.update(
                db.collection("users").document(user.userId),
                mapOf("accountStatus" to next, "updatedAt" to FieldValue.serverTimestamp())
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(adminUid, adminEmail, "SET_USER_STATUS", "user", user.userId, "${user.displayName} -> $next")
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateCommissionAndSettings(
        platformCommissionPct: Int,
        referralCommissionPct: Int,
        holdMinutes: Int,
        cancellationWindowHours: Int
    ): Result<Unit> {
        val path = "platform_settings/config"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            require(platformCommissionPct in 0..50 && referralCommissionPct in 0..50) {
                "Commission percentage must be between 0% and 50%."
            }
            require(holdMinutes in 5..60) { "Hold duration must be between 5 and 60 minutes." }
            val batch = db.batch()
            batch.set(
                db.collection("platform_settings").document("config"),
                mapOf(
                    "brandName" to BrandConfig.APP_NAME,
                    "platformCommissionBps" to (platformCommissionPct * 100L),
                    "referralCommissionBps" to (referralCommissionPct * 100L),
                    "gatewayFeeBps" to BrandConfig.DEFAULT_GATEWAY_FEE_BPS,
                    "gstOnCommissionBps" to BrandConfig.DEFAULT_GST_ON_COMMISSION_BPS,
                    "tdsBps" to BrandConfig.DEFAULT_TDS_BPS,
                    "paymentHoldMinutes" to holdMinutes.toLong(),
                    "cancellationWindowHours" to cancellationWindowHours.toLong(),
                    "disputeWindowHours" to BrandConfig.DEFAULT_DISPUTE_WINDOW_HOURS,
                    "minMeetAttendanceMinutes" to BrandConfig.DEFAULT_MIN_ATTENDANCE_FLOOR_MINUTES,
                    "minMeetAttendanceRatioBps" to 6500L,
                    "defaultTimezone" to BrandConfig.DEFAULT_TIMEZONE,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(
                    adminUid, adminEmail, "UPDATE_COMMISSION_RULES", "platform_settings", "config",
                    "Platform=$platformCommissionPct%, Referral=$referralCommissionPct%, Hold=${holdMinutes}m"
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminManualAttendanceReview(
        booking: Booking,
        approveAttendance: Boolean,
        customerMinutes: Long,
        expertMinutes: Long,
        overlapMinutes: Long,
        adminNote: String
    ): Result<Unit> {
        val path = "bookings/${booking.id}"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val status = if (approveAttendance) AttendanceStatus.VERIFIED else AttendanceStatus.FAILED
            val notePrefix = if (approveAttendance) "ADMIN_APPROVED" else "ADMIN_REJECTED"
            val summary = "$notePrefix: ${adminNote.trim().ifBlank { "Reviewed by admin" }}".take(450)
            val nowMs = System.currentTimeMillis()
            val windowEndsAt = if (booking.disputeWindowEndsAtEpochMs > 0L) {
                booking.disputeWindowEndsAtEpochMs
            } else {
                nowMs + BrandConfig.DEFAULT_DISPUTE_WINDOW_HOURS * 3_600_000L
            }

            val batch = db.batch()
            val bookingUpdates = mutableMapOf<String, Any>(
                "attendanceStatus" to status.name,
                "attendanceVerificationSource" to VerificationSource.ADMIN_MANUAL_REVIEW.name,
                "customerJoinedMinutes" to customerMinutes.coerceAtLeast(0L),
                "expertJoinedMinutes" to expertMinutes.coerceAtLeast(0L),
                "overlapMinutes" to overlapMinutes.coerceAtLeast(0L),
                "attendanceNotes" to summary,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (approveAttendance && booking.bookingStatusEnum() != BookingStatus.COMPLETED) {
                bookingUpdates["bookingStatus"] = BookingStatus.COMPLETED.name
                bookingUpdates["completedAtEpochMs"] = nowMs
                bookingUpdates["disputeWindowEndsAtEpochMs"] = windowEndsAt
            }
            batch.update(db.collection("bookings").document(booking.id), bookingUpdates)
            batch.set(
                db.collection("attendance_records").document(booking.id),
                mapOf(
                    "bookingId" to booking.id,
                    "customerUid" to booking.customerUid,
                    "expertUid" to booking.expertUid,
                    "expectedCustomerEmail" to booking.verifiedCustomerGoogleEmail,
                    "expectedExpertEmail" to booking.verifiedExpertGoogleEmail,
                    "verificationSource" to VerificationSource.ADMIN_MANUAL_REVIEW.name,
                    "attendanceStatus" to status.name,
                    "customerDurationMinutes" to customerMinutes.coerceAtLeast(0L),
                    "expertDurationMinutes" to expertMinutes.coerceAtLeast(0L),
                    "overlapMinutes" to overlapMinutes.coerceAtLeast(0L),
                    "minimumRequiredMinutes" to BrandConfig.DEFAULT_MIN_ATTENDANCE_FLOOR_MINUTES,
                    "evaluatorSummary" to summary,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(adminUid, adminEmail, "MANUAL_ATTENDANCE_REVIEW", "booking", booking.id, summary)
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminResolveDisputeAndRefund(
        booking: Booking,
        resolutionType: DisputeStatus,
        refundAmountPaise: Long,
        adminNote: String
    ): Result<Unit> {
        val path = "disputes/${booking.id}"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val remainingRefundablePaise = (booking.amountPaise - booking.refundedAmountPaise).coerceAtLeast(0L)
            require(refundAmountPaise in 0L..remainingRefundablePaise) {
                "Refund amount exceeds remaining refundable payment (${CurrencyUtils.formatInrFromPaise(remainingRefundablePaise)})."
            }
            if (resolutionType == DisputeStatus.RESOLVED_REFUND_FULL || resolutionType == DisputeStatus.RESOLVED_REFUND_PARTIAL) {
                require(refundAmountPaise > 0L) { "Refund amount must be positive for refund resolution." }
            }

            if (refundAmountPaise > 0L && BrandConfig.isBackendConfigured) {
                return when (val res = backendClient.initiateBackendRefund(booking.id, refundAmountPaise, adminNote)) {
                    is BackendCallResult.Success -> Result.success(Unit)
                    is BackendCallResult.Error -> Result.failure(IllegalStateException("Backend refund failed: ${res.message}"))
                    is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
                }
            }
            if (refundAmountPaise > 0L && !BrandConfig.allowDebugSandboxFallback) {
                return Result.failure(
                    IllegalStateException("Production refunds require HRCONNECT_BACKEND_BASE_URL to execute securely via Razorpay API.")
                )
            }

            val isFullRefund = resolutionType == DisputeStatus.RESOLVED_REFUND_FULL || refundAmountPaise == booking.amountPaise
            val nextBookingStatus = when {
                isFullRefund -> BookingStatus.REFUNDED
                resolutionType == DisputeStatus.RESOLVED_RELEASE_EXPERT -> BookingStatus.COMPLETED
                else -> BookingStatus.COMPLETED
            }
            val nextPaymentStatus = when {
                isFullRefund -> PaymentStatus.REFUNDED
                refundAmountPaise > 0L -> PaymentStatus.PARTIALLY_REFUNDED
                else -> PaymentStatus.SUCCESS
            }
            val nextPayoutElig = when {
                isFullRefund -> PayoutEligibilityStatus.REVERSED
                else -> PayoutEligibilityStatus.ELIGIBLE
            }
            val cleanNote = adminNote.trim().ifBlank { "Resolved by admin (${resolutionType.name})" }.take(500)
            val nowMs = System.currentTimeMillis()

            val disputeRef = db.collection("disputes").document(booking.id)
            val disputeExists = disputeRef.get().await().exists()
            val batch = db.batch()
            if (disputeExists) {
                batch.update(
                    disputeRef,
                    mapOf(
                        "evidenceSummary" to cleanNote,
                        "status" to resolutionType.name,
                        "approvedRefundPaise" to refundAmountPaise,
                        "resolutionNote" to cleanNote,
                        "resolvedByUid" to adminUid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                batch.set(
                    disputeRef,
                    mapOf(
                        "bookingId" to booking.id,
                        "customerUid" to booking.customerUid,
                        "expertUid" to booking.expertUid,
                        "raisedByUid" to booking.customerUid,
                        "reason" to (booking.disputeReason ?: "Admin resolution"),
                        "evidenceSummary" to cleanNote,
                        "status" to resolutionType.name,
                        "requestedRefundPaise" to booking.amountPaise,
                        "approvedRefundPaise" to refundAmountPaise,
                        "resolutionNote" to cleanNote,
                        "resolvedByUid" to adminUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "bookingStatus" to nextBookingStatus.name,
                    "paymentStatus" to nextPaymentStatus.name,
                    "disputeStatus" to resolutionType.name,
                    "disputeResolution" to cleanNote,
                    "refundedAmountPaise" to refundAmountPaise,
                    "payoutEligibilityStatus" to nextPayoutElig.name,
                    "disputeWindowEndsAtEpochMs" to nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            val payDocRef = db.collection("payments").document("pay_${booking.id}")
            if (payDocRef.get().await().exists()) {
                batch.update(
                    payDocRef,
                    mapOf(
                        "paymentStatus" to nextPaymentStatus.name,
                        "refundedAmountPaise" to refundAmountPaise,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            if (refundAmountPaise > 0L) {
                batch.set(
                    db.collection("ledger_entries").document("led_refund_${booking.id}"),
                    mapOf(
                        "entryType" to LedgerEntryType.REFUND_COMPLETED.name,
                        "direction" to LedgerDirection.DEBIT.name,
                        "entryStatus" to LedgerEntryStatus.SETTLED.name,
                        "bookingId" to booking.id,
                        "paymentId" to "pay_${booking.id}",
                        "customerUid" to booking.customerUid,
                        "expertUid" to booking.expertUid,
                        "amountPaise" to refundAmountPaise,
                        "currency" to BrandConfig.CURRENCY_CODE,
                        "idempotencyKey" to "led_refund_${booking.id}",
                        "description" to "Customer refund (${resolutionType.name}): $cleanNote".take(300),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                val propCommissionReversal = if (booking.amountPaise > 0L) {
                    (booking.commissionPaise * refundAmountPaise) / booking.amountPaise
                } else 0L
                if (propCommissionReversal > 0L) {
                    batch.set(
                        db.collection("ledger_entries").document("led_commrev_${booking.id}"),
                        mapOf(
                            "entryType" to LedgerEntryType.COMMISSION_REVERSAL.name,
                            "direction" to LedgerDirection.DEBIT.name,
                            "entryStatus" to LedgerEntryStatus.SETTLED.name,
                            "bookingId" to booking.id,
                            "paymentId" to "pay_${booking.id}",
                            "customerUid" to booking.customerUid,
                            "expertUid" to booking.expertUid,
                            "amountPaise" to propCommissionReversal,
                            "currency" to BrandConfig.CURRENCY_CODE,
                            "idempotencyKey" to "led_commrev_${booking.id}",
                            "description" to "Commission reversal on refund".take(300),
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            } else {
                batch.set(
                    db.collection("ledger_entries").document("led_drel_${booking.id}"),
                    mapOf(
                        "entryType" to LedgerEntryType.DISPUTE_RELEASE.name,
                        "direction" to LedgerDirection.CREDIT.name,
                        "entryStatus" to LedgerEntryStatus.SETTLED.name,
                        "bookingId" to booking.id,
                        "customerUid" to booking.customerUid,
                        "expertUid" to booking.expertUid,
                        "amountPaise" to booking.expertEarningPaise,
                        "currency" to BrandConfig.CURRENCY_CODE,
                        "idempotencyKey" to "led_drel_${booking.id}",
                        "description" to "Dispute hold released to expert: $cleanNote".take(300),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to booking.customerUid,
                    "title" to "Dispute / Refund Update",
                    "message" to "Resolution for ${booking.serviceTitle}: ${resolutionType.name}. Refund: ${CurrencyUtils.formatInrFromPaise(refundAmountPaise)}",
                    "eventType" to "DISPUTE_RESOLVED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to booking.expertUid,
                    "title" to "Dispute Resolved",
                    "message" to "Admin resolved dispute on ${booking.serviceTitle}: ${resolutionType.name}",
                    "eventType" to "DISPUTE_RESOLVED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(
                    adminUid, adminEmail, "RESOLVE_DISPUTE", "booking", booking.id,
                    "${resolutionType.name} refund=${CurrencyUtils.formatInrFromPaise(refundAmountPaise)} note=$cleanNote"
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun processPayoutRequest(
        request: PayoutRequest,
        newStatus: PayoutStatus,
        payoutReference: String,
        adminNote: String
    ): Result<Unit> {
        val path = "payout_requests/${request.id}"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val curStatus = request.statusEnum()
            check(curStatus != PayoutStatus.PAID && curStatus != PayoutStatus.REJECTED) {
                "Duplicate payout guard: Payout request is already finalized ($curStatus)."
            }
            if (newStatus == PayoutStatus.PAID) {
                require(payoutReference.trim().length >= 3) {
                    "Enter a bank UTR / UPI transfer reference before marking Paid."
                }
            }

            if (BrandConfig.isBackendConfigured) {
                return when (
                    val res = backendClient.triggerBackendPayout(
                        payoutRequestId = request.id,
                        newStatus = newStatus.name,
                        payoutReference = payoutReference.trim().take(128),
                        adminNote = adminNote.trim().take(500)
                    )
                ) {
                    is BackendCallResult.Success -> Result.success(Unit)
                    is BackendCallResult.Error -> Result.failure(IllegalStateException("Backend payout failed: ${res.message}"))
                    is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
                }
            }
            if (!BrandConfig.allowDebugSandboxFallback) {
                return Result.failure(
                    IllegalStateException("Production payouts require HRCONNECT_BACKEND_BASE_URL to execute securely on the server.")
                )
            }

            val settingsSnap = db.collection("platform_settings").document("config").get().await()
            val settings = if (settingsSnap.exists()) {
                settingsSnap.toObject(com.example.data.model.PlatformSettings::class.java) ?: com.example.data.model.PlatformSettings()
            } else {
                com.example.data.model.PlatformSettings()
            }

            val loadedBookings = request.eligibleBookingIds.distinct().mapNotNull { bookingId ->
                val snap = db.collection("bookings").document(bookingId).get().await()
                snap.toObject(Booking::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }

            if (newStatus in setOf(PayoutStatus.APPROVED, PayoutStatus.PROCESSING, PayoutStatus.PAID)) {
                require(loadedBookings.isNotEmpty()) {
                    "Cannot approve/pay payout without verified eligible bookings."
                }
                val nowMs = System.currentTimeMillis()
                var verifiedEligibleSum = 0L
                for (bk in loadedBookings) {
                    check(bk.expertUid == request.expertUid) {
                        "Booking ${bk.id} does not belong to expert ${request.expertName}."
                    }
                    val curElig = bk.payoutEligibilityEnum()
                    // When transitioning PENDING -> PROCESSING -> PAID, the same request's bookings may already be PROCESSING
                    val effectiveBooking = if (curStatus in setOf(PayoutStatus.APPROVED, PayoutStatus.PROCESSING) && curElig == PayoutEligibilityStatus.PROCESSING) {
                        bk.copy(payoutEligibilityStatus = PayoutEligibilityStatus.ELIGIBLE.name)
                    } else {
                        bk
                    }
                    val checkRes = com.example.core.FinancialLedgerEngine.evaluateBookingPayoutEligibility(
                        booking = effectiveBooking,
                        settings = settings,
                        nowEpochMs = nowMs
                    )
                    check(checkRes.eligible) {
                        "Booking ${bk.id} failed payout eligibility check: ${checkRes.reasons.joinToString("; ")}"
                    }
                    val remainingPayable = (bk.expertEarningPaise - com.example.core.FinancialLedgerEngine.computeBookingProportionalRefundReduction(bk))
                        .coerceAtLeast(0L)
                    verifiedEligibleSum += remainingPayable
                }
                check(verifiedEligibleSum >= request.amountPaise) {
                    "Requested payout (${CurrencyUtils.formatInrFromPaise(request.amountPaise)}) exceeds verified eligible bookings total (${CurrencyUtils.formatInrFromPaise(verifiedEligibleSum)})."
                }
            }

            val batch = db.batch()
            val updates = mutableMapOf<String, Any>(
                "status" to newStatus.name,
                "processedByUid" to adminUid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (payoutReference.isNotBlank()) updates["payoutReference"] = payoutReference.trim().take(128)
            if (adminNote.isNotBlank()) updates["adminNote"] = adminNote.trim().take(500)
            if (newStatus == PayoutStatus.FAILED) {
                updates["failureReason"] = adminNote.trim().ifBlank { "Gateway payout transfer failed" }.take(300)
            }
            if (curStatus == PayoutStatus.FAILED && newStatus == PayoutStatus.PROCESSING) {
                updates["retryCount"] = request.retryCount + 1L
            }

            batch.update(db.collection("payout_requests").document(request.id), updates)

            val targetBookingElig = when (newStatus) {
                PayoutStatus.PROCESSING -> PayoutEligibilityStatus.PROCESSING.name
                PayoutStatus.PAID -> PayoutEligibilityStatus.PAID.name
                PayoutStatus.FAILED, PayoutStatus.REJECTED -> PayoutEligibilityStatus.ELIGIBLE.name
                else -> null
            }
            if (targetBookingElig != null) {
                for (bk in loadedBookings) {
                    if (bk.payoutEligibilityEnum() !in setOf(PayoutEligibilityStatus.PAID, PayoutEligibilityStatus.REVERSED)) {
                        batch.update(
                            db.collection("bookings").document(bk.id),
                            mapOf(
                                "payoutEligibilityStatus" to targetBookingElig,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
            }

            when (newStatus) {
                PayoutStatus.PROCESSING -> {
                    batch.set(
                        db.collection("ledger_entries").document("led_payinit_${request.id}_${request.retryCount}"),
                        mapOf(
                            "entryType" to LedgerEntryType.PAYOUT_INITIATED.name,
                            "direction" to LedgerDirection.DEBIT.name,
                            "entryStatus" to LedgerEntryStatus.PENDING.name,
                            "payoutRequestId" to request.id,
                            "customerUid" to "",
                            "expertUid" to request.expertUid,
                            "amountPaise" to request.amountPaise,
                            "currency" to BrandConfig.CURRENCY_CODE,
                            "idempotencyKey" to "led_payinit_${request.id}_${request.retryCount}",
                            "description" to "Payout transfer initiated (${request.payoutDestination})",
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                PayoutStatus.FAILED -> {
                    batch.set(
                        db.collection("ledger_entries").document("led_payfail_${request.id}_${request.retryCount}"),
                        mapOf(
                            "entryType" to LedgerEntryType.PAYOUT_FAILED.name,
                            "direction" to LedgerDirection.CREDIT.name,
                            "entryStatus" to LedgerEntryStatus.FAILED.name,
                            "payoutRequestId" to request.id,
                            "customerUid" to "",
                            "expertUid" to request.expertUid,
                            "amountPaise" to request.amountPaise,
                            "currency" to BrandConfig.CURRENCY_CODE,
                            "idempotencyKey" to "led_payfail_${request.id}_${request.retryCount}",
                            "description" to "Payout failed; balance restored for safe retry",
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                PayoutStatus.PAID -> {
                    batch.set(
                        db.collection("ledger_entries").document("led_payout_${request.id}"),
                        mapOf(
                            "entryType" to LedgerEntryType.PAYOUT_COMPLETED.name,
                            "direction" to LedgerDirection.DEBIT.name,
                            "entryStatus" to LedgerEntryStatus.SETTLED.name,
                            "payoutRequestId" to request.id,
                            "customerUid" to "",
                            "expertUid" to request.expertUid,
                            "amountPaise" to request.amountPaise,
                            "currency" to BrandConfig.CURRENCY_CODE,
                            "idempotencyKey" to "led_payout_${request.id}",
                            "description" to "Payout settled (Ref: ${payoutReference.trim()})",
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                else -> Unit
            }

            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to request.expertUid,
                    "title" to "Payout ${newStatus.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    "message" to "Your payout request of ${CurrencyUtils.formatInrFromPaise(request.amountPaise)} is now ${newStatus.name}.",
                    "eventType" to "PAYOUT_UPDATED",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(
                    adminUid, adminEmail, "PROCESS_PAYOUT", "payout_request", request.id,
                    "${request.expertName} -> ${newStatus.name} (${payoutReference.trim()})"
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun saveSpecialisation(
        existingId: String?,
        name: String,
        description: String,
        sortOrder: Long,
        active: Boolean
    ): Result<Unit> {
        val docRef = if (existingId.isNullOrBlank()) {
            db.collection("specialisations").document()
        } else {
            db.collection("specialisations").document(existingId)
        }
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            require(name.trim().length >= 2) { "Specialisation name is required." }
            if (existingId.isNullOrBlank()) {
                docRef.set(
                    mapOf(
                        "name" to name.trim().take(100),
                        "description" to description.trim().take(500),
                        "sortOrder" to sortOrder,
                        "active" to active,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "name" to name.trim().take(100),
                        "description" to description.trim().take(500),
                        "sortOrder" to sortOrder,
                        "active" to active,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            db.collection("audit_logs").document().set(
                auditPayload(adminUid, adminEmail, "SAVE_SPECIALISATION", "specialisation", docRef.id, name.trim())
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun moderateReview(reviewId: String, hide: Boolean): Result<Unit> {
        val path = "reviews/$reviewId"
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val status = if (hide) ModerationStatus.HIDDEN.name else ModerationStatus.VISIBLE.name
            val batch = db.batch()
            batch.update(
                db.collection("reviews").document(reviewId),
                mapOf(
                    "moderationStatus" to status,
                    "reported" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(adminUid, adminEmail, "MODERATE_REVIEW", "review", reviewId, "Status -> $status")
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun seedDemoMarketplace(): Result<Unit> {
        return try {
            val (adminUid, adminEmail) = requireAdminIdentity()
            val batch = db.batch()

            batch.set(
                db.collection("platform_settings").document("config"),
                mapOf(
                    "brandName" to BrandConfig.APP_NAME,
                    "platformCommissionBps" to BrandConfig.DEFAULT_PLATFORM_COMMISSION_BPS,
                    "referralCommissionBps" to BrandConfig.DEFAULT_REFERRAL_COMMISSION_BPS,
                    "gatewayFeeBps" to BrandConfig.DEFAULT_GATEWAY_FEE_BPS,
                    "gstOnCommissionBps" to BrandConfig.DEFAULT_GST_ON_COMMISSION_BPS,
                    "tdsBps" to BrandConfig.DEFAULT_TDS_BPS,
                    "paymentHoldMinutes" to BrandConfig.DEFAULT_PAYMENT_HOLD_MINUTES,
                    "cancellationWindowHours" to BrandConfig.DEFAULT_CANCELLATION_WINDOW_HOURS,
                    "disputeWindowHours" to BrandConfig.DEFAULT_DISPUTE_WINDOW_HOURS,
                    "minMeetAttendanceMinutes" to BrandConfig.DEFAULT_MIN_ATTENDANCE_FLOOR_MINUTES,
                    "minMeetAttendanceRatioBps" to 6500L,
                    "defaultTimezone" to BrandConfig.DEFAULT_TIMEZONE,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            BrandConfig.defaultSpecialisations.forEachIndexed { idx, (name, desc) ->
                val specRef = db.collection("specialisations").document("spec_${idx + 1}")
                batch.set(
                    specRef,
                    mapOf(
                        "name" to name,
                        "description" to desc,
                        "sortOrder" to idx.toLong(),
                        "active" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            val demoExperts = listOf(
                ExpertProfile(
                    id = "demo_expert_ananya",
                    expertUid = "demo_uid_ananya",
                    fullName = "Ananya Deshmukh [DEMO]",
                    verifiedGoogleEmail = "ananya.demo@hrconnect.example",
                    googleEmailVerified = true,
                    headline = "VP People & Startup HR Architect • Ex-FinTech",
                    designation = "VP - People & Culture (Demo)",
                    organization = "ScaleUp Advisory India",
                    yearsExperience = 14L,
                    bio = "Demo profile showcasing 0-to-1 startup HR setup, ESOP policy structuring, and leadership coaching across Bengaluru & Mumbai tech teams.",
                    primarySpecialisation = "Startup HR Setup",
                    specialisations = listOf("Startup HR Setup", "HR Strategy and Leadership", "HR Business Partnering"),
                    skills = listOf("ESOP Design", "HR Policies", "Org Scaling", "Executive Hiring"),
                    industryExperience = "FinTech, SaaS & Consumer Tech (India)",
                    verificationStatus = VerificationStatus.APPROVED.name,
                    accountStatus = AccountStatus.ACTIVE.name,
                    demoProfile = true,
                    startingPriceInr = 1500L,
                    referralCode = "ANANYA10",
                    timezone = BrandConfig.DEFAULT_TIMEZONE
                ),
                ExpertProfile(
                    id = "demo_expert_vikram",
                    expertUid = "demo_uid_vikram",
                    fullName = "Vikram Kulkarni [DEMO]",
                    verifiedGoogleEmail = "vikram.demo@hrconnect.example",
                    googleEmailVerified = true,
                    headline = "Labour Law, POSH & Compliance Specialist",
                    designation = "Principal HR Compliance Advisor (Demo)",
                    organization = "Kulkarni People Counsel",
                    yearsExperience = 16L,
                    bio = "Demo profile covering Indian Labour Codes, POSH IC committee structuring, statutory audits, and employee relations frameworks.",
                    primarySpecialisation = "Labour Law and Compliance",
                    specialisations = listOf("Labour Law and Compliance", "Employee Relations", "HR Operations"),
                    skills = listOf("POSH Compliance", "Indian Labour Codes", "Statutory Audits", "PIP Governance"),
                    industryExperience = "Manufacturing, IT Services & Retail",
                    verificationStatus = VerificationStatus.APPROVED.name,
                    accountStatus = AccountStatus.ACTIVE.name,
                    demoProfile = true,
                    startingPriceInr = 1200L,
                    referralCode = "VIKRAM10",
                    timezone = BrandConfig.DEFAULT_TIMEZONE
                ),
                ExpertProfile(
                    id = "demo_expert_meera",
                    expertUid = "demo_uid_meera",
                    fullName = "Meera Nair [DEMO]",
                    verifiedGoogleEmail = "meera.demo@hrconnect.example",
                    googleEmailVerified = true,
                    headline = "Talent Acquisition Lead & HR Career Mentor",
                    designation = "Director - Talent Acquisition (Demo)",
                    organization = "Meridian Talent Partners",
                    yearsExperience = 10L,
                    bio = "Demo profile focused on executive hiring playbooks, ATS-friendly HR resumes, LinkedIn positioning, and mock HRBP interview preparation.",
                    primarySpecialisation = "Talent Acquisition and Recruitment",
                    specialisations = listOf("Talent Acquisition and Recruitment", "Interview Preparation", "Resume and LinkedIn Guidance"),
                    skills = listOf("Executive Search", "Mock Interviews", "ATS Resume Review", "Employer Branding"),
                    industryExperience = "E-Commerce, SaaS & Global Capability Centres",
                    verificationStatus = VerificationStatus.APPROVED.name,
                    accountStatus = AccountStatus.ACTIVE.name,
                    demoProfile = true,
                    startingPriceInr = 800L,
                    referralCode = "MEERA10",
                    timezone = BrandConfig.DEFAULT_TIMEZONE
                ),
                ExpertProfile(
                    id = "demo_expert_rohan",
                    expertUid = "demo_uid_rohan",
                    fullName = "Rohan Malhotra [DEMO]",
                    verifiedGoogleEmail = "rohan.demo@hrconnect.example",
                    googleEmailVerified = true,
                    headline = "Total Rewards, Payroll & Performance Specialist",
                    designation = "Head of Compensation & Benefits (Demo)",
                    organization = "EquiPay Consulting",
                    yearsExperience = 12L,
                    bio = "Demo profile dedicated to Indian CTC benchmarking, variable pay design, statutory payroll accuracy, and OKR performance calibration.",
                    primarySpecialisation = "Payroll and Compensation",
                    specialisations = listOf("Payroll and Compensation", "Performance Management", "Career Guidance for HR Professionals"),
                    skills = listOf("CTC Benchmarking", "PF & Gratuity", "OKR Calibration", "Offer Negotiation"),
                    industryExperience = "GCCs, BFSI & Enterprise SaaS",
                    verificationStatus = VerificationStatus.APPROVED.name,
                    accountStatus = AccountStatus.ACTIVE.name,
                    demoProfile = true,
                    startingPriceInr = 1000L,
                    referralCode = "ROHAN10",
                    timezone = BrandConfig.DEFAULT_TIMEZONE
                )
            )

            demoExperts.forEach { expert ->
                batch.set(db.collection("experts").document(expert.id), expert.toCreateMap())
                for (day in 1L..6L) {
                    val rule = AvailabilityRule(
                        expertId = expert.id,
                        expertUid = expert.expertUid,
                        dayOfWeek = day,
                        enabled = true,
                        startMinute = 600L,
                        endMinute = 1200L,
                        breakStartMinute = 810L,
                        breakEndMinute = 870L,
                        timezone = BrandConfig.DEFAULT_TIMEZONE
                    )
                    batch.set(
                        db.collection("availability_rules").document("${expert.id}_day_$day"),
                        rule.toWriteMap()
                    )
                }
            }

            val demoServices = listOf(
                ConsultationService(
                    id = "demo_svc_ananya_1",
                    expertId = "demo_expert_ananya",
                    expertUid = "demo_uid_ananya",
                    expertName = "Ananya Deshmukh [DEMO]",
                    title = "Startup HR 0-to-1 Setup Advisory",
                    description = "Hands-on 60-minute roadmap covering offer letters, leave & hybrid policies, and founding team HR compliance.",
                    specialisation = "Startup HR Setup",
                    durationMinutes = 60L,
                    priceInr = 2500L,
                    pricePaise = 250000L,
                    consultationFormat = "Video Consultation",
                    preparationInstructions = "Share your current headcount and key policy priorities.",
                    active = true,
                    demoService = true
                ),
                ConsultationService(
                    id = "demo_svc_ananya_2",
                    expertId = "demo_expert_ananya",
                    expertUid = "demo_uid_ananya",
                    expertName = "Ananya Deshmukh [DEMO]",
                    title = "30-min HRBP Leadership Sounding Board",
                    description = "Focused 30-minute consultation on stakeholder management, org restructuring, or HR leadership transitions.",
                    specialisation = "HR Business Partnering",
                    durationMinutes = 30L,
                    priceInr = 1500L,
                    pricePaise = 150000L,
                    consultationFormat = "Video Consultation",
                    active = true,
                    demoService = true
                ),
                ConsultationService(
                    id = "demo_svc_vikram_1",
                    expertId = "demo_expert_vikram",
                    expertUid = "demo_uid_vikram",
                    expertName = "Vikram Kulkarni [DEMO]",
                    title = "POSH & Labour Compliance Diagnostic",
                    description = "45-minute review of POSH IC readiness, shop & establishment compliance, and employment contract clauses.",
                    specialisation = "Labour Law and Compliance",
                    durationMinutes = 45L,
                    priceInr = 1800L,
                    pricePaise = 180000L,
                    consultationFormat = "Video Consultation",
                    preparationInstructions = "Keep your state location(s) and employee count handy.",
                    active = true,
                    demoService = true
                ),
                ConsultationService(
                    id = "demo_svc_vikram_2",
                    expertId = "demo_expert_vikram",
                    expertUid = "demo_uid_vikram",
                    expertName = "Vikram Kulkarni [DEMO]",
                    title = "Employee Relations & PIP Strategy",
                    description = "30-minute structured guidance on handling sensitive workplace grievances or performance improvement plans.",
                    specialisation = "Employee Relations",
                    durationMinutes = 30L,
                    priceInr = 1200L,
                    pricePaise = 120000L,
                    consultationFormat = "Audio Call",
                    active = true,
                    demoService = true
                ),
                ConsultationService(
                    id = "demo_svc_meera_1",
                    expertId = "demo_expert_meera",
                    expertUid = "demo_uid_meera",
                    expertName = "Meera Nair [DEMO]",
                    title = "HR Resume & LinkedIn Positioning Review",
                    description = "30-minute line-by-line audit of your HR resume and LinkedIn profile for senior recruiter & HRBP roles.",
                    specialisation = "Resume and LinkedIn Guidance",
                    durationMinutes = 30L,
                    priceInr = 800L,
                    pricePaise = 80000L,
                    consultationFormat = "Video Consultation",
                    preparationInstructions = "Bring your latest resume and 2 target job descriptions.",
                    active = true,
                    demoService = true
                ),
                ConsultationService(
                    id = "demo_svc_meera_2",
                    expertId = "demo_expert_meera",
                    expertUid = "demo_uid_meera",
                    expertName = "Meera Nair [DEMO]",
                    title = "60-min Mock HR Interview & Feedback",
                    description = "Full behavioural + situational HR mock interview with structured scorecard feedback.",
                    specialisation = "Interview Preparation",
                    durationMinutes = 60L,
                    priceInr = 1600L,
                    pricePaise = 160000L,
                    consultationFormat = "Video Consultation",
                    active = true,
                    demoService = true
                ),
                ConsultationService(
                    id = "demo_svc_rohan_1",
                    expertId = "demo_expert_rohan",
                    expertUid = "demo_uid_rohan",
                    expertName = "Rohan Malhotra [DEMO]",
                    title = "CTC Structuring & Salary Negotiation",
                    description = "30-minute breakdown of fixed vs variable CTC, tax-efficient allowances, and ESOP valuation.",
                    specialisation = "Payroll and Compensation",
                    durationMinutes = 30L,
                    priceInr = 1000L,
                    pricePaise = 100000L,
                    consultationFormat = "Video Consultation",
                    active = true,
                    demoService = true
                )
            )
            demoServices.forEach { svc ->
                batch.set(db.collection("services").document(svc.id), svc.toCreateMap())
            }

            batch.set(
                db.collection("audit_logs").document(),
                auditPayload(adminUid, adminEmail, "SEED_DEMO_MARKETPLACE", "platform", "seed", "Seeded 15 categories and 4 demo HR experts")
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "seed_demo_marketplace")
            Result.failure(e)
        }
    }

    private fun auditPayload(
        actorUid: String,
        actorEmail: String,
        action: String,
        targetType: String,
        targetId: String,
        details: String
    ): Map<String, Any> = mapOf(
        "actorUid" to actorUid,
        "actorEmail" to actorEmail.take(200),
        "action" to action.take(100),
        "targetType" to targetType.take(60),
        "targetId" to targetId.take(128),
        "details" to details.take(1000),
        "createdAt" to FieldValue.serverTimestamp()
    )
}
