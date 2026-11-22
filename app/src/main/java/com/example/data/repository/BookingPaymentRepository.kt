package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.AttendanceVerificationEngine
import com.example.core.BrandConfig
import com.example.core.ConsultationLifecycleEngine
import com.example.core.CurrencyUtils
import com.example.core.FinancialLedgerEngine
import com.example.core.OperationType
import com.example.core.TimeSlotUtils
import com.example.core.handleFirestoreError
import com.example.data.model.AttendanceStatus
import com.example.data.model.Booking
import com.example.data.model.BookingStatus
import com.example.data.model.CalendarSyncStatus
import com.example.data.model.ConsultationService
import com.example.data.model.DisputeStatus
import com.example.data.model.ExpertProfile
import com.example.data.model.LedgerDirection
import com.example.data.model.LedgerEntryStatus
import com.example.data.model.LedgerEntryType
import com.example.data.model.PaymentStatus
import com.example.data.model.PayoutEligibilityStatus
import com.example.data.model.PlatformSettings
import com.example.data.model.ProviderSettlementStatus
import com.example.data.model.ReferralType
import com.example.data.model.SlotLock
import com.example.data.model.VerificationSource
import com.example.data.remote.BackendCallResult
import com.example.data.remote.BackendOrderResponse
import com.example.data.remote.BackendPaymentVerifyResponse
import com.example.data.remote.MarketplaceBackendClient
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

class BookingPaymentRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val backendClient: MarketplaceBackendClient = MarketplaceBackendClient(auth)
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before booking.")

    private fun requireVerifiedGoogleEmail(): String {
        val user = auth.currentUser
            ?: throw IllegalStateException("User must be signed in with Google.")
        val email = (user.email ?: "").trim().lowercase()
        require(email.isNotBlank() && user.isEmailVerified) {
            "Verified Google account email is required to book or attend Google Meet consultations."
        }
        return email
    }

    suspend fun createBookingWithSlotHold(
        customerName: String,
        expertId: String,
        serviceId: String,
        dateIso: String,
        startMinute: Int,
        topic: String,
        referralCodeInput: String
    ): Result<Booking> {
        val bookingDoc = db.collection("bookings").document()
        return try {
            val uid = requireUserId()
            val verifiedCustomerEmail = requireVerifiedGoogleEmail()
            val cleanTopic = topic.trim()
            require(cleanTopic.length in 3..1000) { "Consultation topic must be between 3 and 1000 characters." }
            require(TimeSlotUtils.isFutureSlot(dateIso, startMinute)) {
                "Selected consultation slot must be in the future."
            }

            if (BrandConfig.isBackendConfigured) {
                return when (
                    val res = backendClient.createBookingHold(
                        expertId = expertId,
                        serviceId = serviceId,
                        dateIso = dateIso,
                        startMinute = startMinute,
                        topic = cleanTopic,
                        referralCode = referralCodeInput.trim(),
                        customerName = customerName.ifBlank { "Customer" }
                    )
                ) {
                    is BackendCallResult.Success -> {
                        val d = res.data
                        val createdId = d.optString("id")
                        if (createdId.isBlank()) {
                            return Result.failure(IllegalStateException("Backend did not return a booking id."))
                        }
                        val snap = db.collection("bookings").document(createdId).get().await()
                        val createdBooking = snap.toObject(
                            Booking::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: Booking(
                            id = createdId,
                            customerUid = uid,
                            customerName = d.optString("customerName", customerName),
                            verifiedCustomerGoogleEmail = verifiedCustomerEmail,
                            expertId = expertId,
                            expertUid = d.optString("expertUid"),
                            expertName = d.optString("expertName"),
                            verifiedExpertGoogleEmail = d.optString("verifiedExpertGoogleEmail"),
                            serviceId = serviceId,
                            serviceTitle = d.optString("serviceTitle"),
                            durationMinutes = d.optLong("durationMinutes", 30L),
                            dateIso = d.optString("dateIso", dateIso),
                            startMinute = d.optLong("startMinute", startMinute.toLong()),
                            endMinute = d.optLong("endMinute", startMinute.toLong() + 30L),
                            slotKey = d.optString("slotKey"),
                            timezone = d.optString("timezone", BrandConfig.DEFAULT_TIMEZONE),
                            topic = cleanTopic,
                            amountPaise = d.optLong("amountPaise"),
                            commissionRateBps = d.optLong("commissionRateBps"),
                            commissionPaise = d.optLong("commissionPaise"),
                            gatewayFeePaise = d.optLong("gatewayFeePaise"),
                            taxDeductionPaise = d.optLong("taxDeductionPaise"),
                            expertEarningPaise = d.optLong("expertEarningPaise"),
                            holdExpiresAtEpochMs = d.optLong("holdExpiresAtEpochMs"),
                            checkInPin = d.optString("checkInPin").takeIf { it.isNotBlank() }
                        )
                        Result.success(createdBooking)
                    }
                    is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                    is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
                }
            }

            if (!BrandConfig.allowDebugSandboxFallback) {
                return Result.failure(
                    IllegalStateException("Production bookings require HRCONNECT_BACKEND_BASE_URL to be configured.")
                )
            }

            val checkInPin = (100000 + SecureRandom().nextInt(900000)).toString()

            val bookingResult = db.runTransaction { tx ->
                val expertRef = db.collection("experts").document(expertId)
                val serviceRef = db.collection("services").document(serviceId)
                val settingsRef = db.collection("platform_settings").document("config")

                val expertSnap = tx.get(expertRef)
                val serviceSnap = tx.get(serviceRef)
                val settingsSnap = tx.get(settingsRef)

                val expert = expertSnap.toObject(
                    ExpertProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: throw IllegalStateException("Expert profile not found.")

                val service = serviceSnap.toObject(
                    ConsultationService::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: throw IllegalStateException("Consultation service not found.")

                val settings = if (settingsSnap.exists()) {
                    settingsSnap.toObject(
                        PlatformSettings::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: PlatformSettings()
                } else {
                    PlatformSettings()
                }

                check(expert.verificationStatus == "APPROVED" && expert.accountStatus == "ACTIVE") {
                    "This expert is not currently active for bookings."
                }
                check(service.active && service.expertId == expertId) {
                    "This consultation service is currently inactive."
                }
                check(expert.expertUid != uid) {
                    "You cannot book your own consultation service."
                }

                val duration = service.durationMinutes.toInt()
                val endMinute = startMinute + duration
                val lockKey = "${expertId}_${dateIso}_${startMinute}"
                val lockRef = db.collection("slot_locks").document(lockKey)
                val nowMs = System.currentTimeMillis()

                // Read all adjacent 15-min boundary slot locks (including 90-min sessions starting up to 75m earlier)
                // before any transaction writes so concurrent overlapping durations cannot race.
                val candidateStartMinutes = (-75..75 step 15)
                    .map { startMinute + it }
                    .filter { it in 0 until 1440 }
                    .distinct()
                val lockRefsToCheck = candidateStartMinutes.map { min ->
                    db.collection("slot_locks").document("${expertId}_${dateIso}_${min}")
                }
                val existingLockSnaps = lockRefsToCheck.map { tx.get(it) }

                for (lockSnap in existingLockSnaps) {
                    if (lockSnap.exists()) {
                        val existingLock = lockSnap.toObject(
                            SlotLock::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        if (existingLock != null &&
                            existingLock.isBlocking(nowMs) &&
                            startMinute < existingLock.endMinute.toInt() &&
                            endMinute > existingLock.startMinute.toInt()
                        ) {
                            throw IllegalStateException("Selected slot overlaps another active reservation. Please pick another slot.")
                        }
                    }
                }

                // Authoritative price, commission, gateway fee & tax snapshot
                val amountPaise = service.pricePaise
                val cleanRef = referralCodeInput.trim().uppercase()
                val isExpertReferral = cleanRef.isNotEmpty() && cleanRef == expert.referralCode.uppercase()
                val referralType = if (isExpertReferral) ReferralType.EXPERT_REFERRAL else ReferralType.PLATFORM
                val commissionRateBps = if (isExpertReferral) {
                    settings.referralCommissionBps
                } else {
                    settings.platformCommissionBps
                }
                val split = FinancialLedgerEngine.calculateFinancialSplit(
                    amountPaise = amountPaise,
                    commissionRateBps = commissionRateBps,
                    gatewayFeeBps = settings.gatewayFeeBps,
                    tdsBps = settings.tdsBps
                )
                val holdExpiresAt = nowMs + settings.paymentHoldMinutes * 60_000L
                val verifiedExpertEmail = expert.verifiedGoogleEmail.ifBlank { "" }.take(200)

                val bookingPayload = mutableMapOf<String, Any>(
                    "customerUid" to uid,
                    "customerName" to customerName.ifBlank { "Customer" }.take(120),
                    "verifiedCustomerGoogleEmail" to verifiedCustomerEmail,
                    "expertId" to expertId,
                    "expertUid" to expert.expertUid,
                    "expertName" to expert.fullName,
                    "verifiedExpertGoogleEmail" to verifiedExpertEmail,
                    "serviceId" to serviceId,
                    "serviceTitle" to service.title,
                    "durationMinutes" to service.durationMinutes,
                    "dateIso" to dateIso,
                    "startMinute" to startMinute.toLong(),
                    "endMinute" to endMinute.toLong(),
                    "slotKey" to lockKey,
                    "timezone" to expert.timezone,
                    "topic" to cleanTopic,
                    "referralType" to referralType.name,
                    "amountPaise" to split.amountPaise,
                    "commissionRateBps" to split.commissionRateBps,
                    "commissionPaise" to split.commissionPaise,
                    "gatewayFeePaise" to split.gatewayFeePaise,
                    "taxDeductionPaise" to split.taxDeductionPaise,
                    "expertEarningPaise" to split.expertNetEarningPaise,
                    "refundedAmountPaise" to 0L,
                    "bookingStatus" to BookingStatus.PENDING_PAYMENT.name,
                    "paymentStatus" to PaymentStatus.CREATED.name,
                    "providerSettlementStatus" to ProviderSettlementStatus.PENDING_CAPTURE.name,
                    "attendanceStatus" to AttendanceStatus.NOT_STARTED.name,
                    "attendanceVerificationSource" to VerificationSource.NONE.name,
                    "payoutEligibilityStatus" to PayoutEligibilityStatus.NOT_ELIGIBLE.name,
                    "disputeStatus" to DisputeStatus.NONE.name,
                    "calendarSyncStatus" to CalendarSyncStatus.NOT_SYNCED.name,
                    "conferenceRequestId" to "conf_${bookingDoc.id}",
                    "meetingAccessMode" to "TRUSTED_INVITEES_ONLY",
                    "checkInPin" to checkInPin,
                    "customerCheckInAtMs" to 0L,
                    "expertCheckInAtMs" to 0L,
                    "customerJoinedMinutes" to 0L,
                    "expertJoinedMinutes" to 0L,
                    "overlapMinutes" to 0L,
                    "customerCompletedConfirmed" to false,
                    "expertCompletedConfirmed" to false,
                    "completedAtEpochMs" to 0L,
                    "disputeWindowEndsAtEpochMs" to 0L,
                    "holdExpiresAtEpochMs" to holdExpiresAt,
                    "reviewed" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                service.preparationInstructions?.takeIf { it.isNotBlank() }?.let {
                    bookingPayload["preparationInstructions"] = it
                }
                if (isExpertReferral) {
                    bookingPayload["referralCode"] = cleanRef
                }

                val lockPayload = mapOf(
                    "expertId" to expertId,
                    "expertUid" to expert.expertUid,
                    "holderUid" to uid,
                    "bookingId" to bookingDoc.id,
                    "dateIso" to dateIso,
                    "startMinute" to startMinute.toLong(),
                    "endMinute" to endMinute.toLong(),
                    "bookingStatus" to BookingStatus.PENDING_PAYMENT.name,
                    "holdExpiresAtEpochMs" to holdExpiresAt,
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                tx.set(bookingDoc, bookingPayload)
                tx.set(lockRef, lockPayload)

                Booking(
                    id = bookingDoc.id,
                    customerUid = uid,
                    customerName = customerName.ifBlank { "Customer" },
                    verifiedCustomerGoogleEmail = verifiedCustomerEmail,
                    expertId = expertId,
                    expertUid = expert.expertUid,
                    expertName = expert.fullName,
                    verifiedExpertGoogleEmail = verifiedExpertEmail,
                    serviceId = serviceId,
                    serviceTitle = service.title,
                    durationMinutes = service.durationMinutes,
                    dateIso = dateIso,
                    startMinute = startMinute.toLong(),
                    endMinute = endMinute.toLong(),
                    slotKey = lockKey,
                    timezone = expert.timezone,
                    topic = cleanTopic,
                    preparationInstructions = service.preparationInstructions,
                    referralType = referralType.name,
                    referralCode = if (isExpertReferral) cleanRef else null,
                    amountPaise = split.amountPaise,
                    commissionRateBps = split.commissionRateBps,
                    commissionPaise = split.commissionPaise,
                    gatewayFeePaise = split.gatewayFeePaise,
                    taxDeductionPaise = split.taxDeductionPaise,
                    expertEarningPaise = split.expertNetEarningPaise,
                    bookingStatus = BookingStatus.PENDING_PAYMENT.name,
                    paymentStatus = PaymentStatus.CREATED.name,
                    holdExpiresAtEpochMs = holdExpiresAt,
                    checkInPin = checkInPin,
                    conferenceRequestId = "conf_${bookingDoc.id}",
                    reviewed = false
                )
            }.await()

            Result.success(bookingResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, bookingDoc.path)
            Result.failure(e)
        }
    }

    /**
     * Releases expired PENDING_PAYMENT holds automatically so abandoned slots never stay stuck.
     */
    suspend fun expireStalePaymentHold(booking: Booking): Result<Unit> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            check(booking.customerUid == uid || booking.expertUid == uid)
            check(booking.isHoldExpired()) { "Payment hold has not expired yet." }
            val batch = db.batch()
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "bookingStatus" to BookingStatus.CANCELLED.name,
                    "cancellationReason" to "10-minute payment hold expired",
                    "cancelledByUid" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("slot_locks").document(booking.slotKey),
                mapOf(
                    "bookingStatus" to BookingStatus.CANCELLED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
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

    suspend fun createRazorpayOrderForCheckout(booking: Booking): Result<BackendOrderResponse> {
        return try {
            val uid = requireUserId()
            check(booking.customerUid == uid) { "Only the booking customer can initiate checkout." }
            check(booking.bookingStatusEnum() == BookingStatus.PENDING_PAYMENT) {
                "Booking is not awaiting payment."
            }
            check(System.currentTimeMillis() <= booking.holdExpiresAtEpochMs) {
                "Your payment hold has expired. Please select a fresh slot."
            }
            if (!BrandConfig.isBackendConfigured) {
                return Result.failure(
                    IllegalStateException("HRCONNECT_BACKEND_BASE_URL must be configured to create a Razorpay order.")
                )
            }
            when (val res = backendClient.createRazorpayOrder(booking.id)) {
                is BackendCallResult.Success -> Result.success(res.data)
                is BackendCallResult.Error -> Result.failure(IllegalStateException("Order creation failed: ${res.message}"))
                is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyRazorpaySdkPayment(
        bookingId: String,
        orderId: String,
        paymentId: String,
        signature: String,
        paymentMethod: String = "RAZORPAY"
    ): Result<BackendPaymentVerifyResponse> {
        return try {
            requireUserId()
            if (!BrandConfig.isBackendConfigured) {
                return Result.failure(
                    IllegalStateException("HRCONNECT_BACKEND_BASE_URL must be configured to verify payments server-side.")
                )
            }
            when (
                val res = backendClient.verifyRazorpayPayment(
                    bookingId = bookingId,
                    orderId = orderId,
                    paymentId = paymentId,
                    signature = signature,
                    paymentMethod = paymentMethod
                )
            ) {
                is BackendCallResult.Success -> Result.success(res.data)
                is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Payment execution boundary:
     * 1. When backend is configured: creates a server-side Razorpay order; if Razorpay live Checkout SDK is required
     *    (`!BrandConfig.allowDebugSandboxFallback || !order.testMode`), caller must complete via Razorpay SDK.
     * 2. Only DEBUG builds without a backend in Razorpay Test Mode may execute local SANDBOX writes.
     */
    suspend fun processPayment(
        booking: Booking,
        paymentMethod: String
    ): Result<String> {
        val paymentDocId = "pay_${booking.id}"
        return try {
            val uid = requireUserId()
            check(booking.customerUid == uid) { "Only the booking customer can complete payment." }
            check(booking.bookingStatusEnum() == BookingStatus.PENDING_PAYMENT) {
                "Booking is not awaiting payment."
            }
            val nowMs = System.currentTimeMillis()
            check(nowMs <= booking.holdExpiresAtEpochMs) {
                "Your payment hold has expired. Please select a fresh slot."
            }

            // Idempotency guard: prevent duplicate charges / ledger entries
            val existingPay = db.collection("payments").document(paymentDocId).get().await()
            if (existingPay.exists() && existingPay.getString("paymentStatus") == PaymentStatus.SUCCESS.name) {
                return Result.success(existingPay.getString("razorpayPaymentId") ?: "already_captured")
            }

            // Path A: Backend API Verification when configured
            if (BrandConfig.isBackendConfigured) {
                val orderRes = backendClient.createRazorpayOrder(booking.id)
                when (orderRes) {
                    is BackendCallResult.Error -> return Result.failure(IllegalStateException("Order creation failed: ${orderRes.message}"))
                    is BackendCallResult.NotConfigured -> Unit
                    is BackendCallResult.Success -> {
                        val order = orderRes.data
                        if (!order.testMode || !BrandConfig.allowDebugSandboxFallback || order.requiresSdkCheckout) {
                            return Result.failure(
                                IllegalStateException(
                                    "Razorpay Standard Checkout SDK signature required for Order ${order.orderId}."
                                )
                            )
                        }
                        val testPayId = "pay_srv_test_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                        val verifyRes = backendClient.verifyRazorpayPayment(
                            bookingId = booking.id,
                            orderId = order.orderId,
                            paymentId = testPayId,
                            signature = "server_sandbox_callback",
                            paymentMethod = paymentMethod
                        )
                        return when (verifyRes) {
                            is BackendCallResult.Success -> Result.success(verifyRes.data.paymentId)
                            is BackendCallResult.Error -> Result.failure(IllegalStateException(verifyRes.message))
                            is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
                        }
                    }
                }
            }

            // Block any sandbox fallback in release builds or live key mode
            if (!BrandConfig.allowDebugSandboxFallback || !BrandConfig.isRazorpayTestMode) {
                return Result.failure(
                    IllegalStateException("Production Razorpay payments require a deployed HTTPS backend (HRCONNECT_BACKEND_BASE_URL) and live Checkout SDK verification.")
                )
            }

            // Path B: Debug-Only Explicit Sandbox Test Mode (`testMode = true` strictly enforced by Firestore rules)
            val orderId = "order_rzp_sandbox_${booking.id.take(12)}"
            val paymentId = "pay_rzp_sandbox_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val batch = db.batch()
            val bookingRef = db.collection("bookings").document(booking.id)
            val lockRef = db.collection("slot_locks").document(booking.slotKey)
            val paymentRef = db.collection("payments").document(paymentDocId)

            batch.update(
                bookingRef,
                mapOf(
                    "bookingStatus" to BookingStatus.CONFIRMED.name,
                    "paymentStatus" to PaymentStatus.SUCCESS.name,
                    "providerSettlementStatus" to ProviderSettlementStatus.SANDBOX_SETTLED.name,
                    "payoutEligibilityStatus" to PayoutEligibilityStatus.NOT_ELIGIBLE.name,
                    "calendarSyncStatus" to CalendarSyncStatus.PENDING_BACKEND_SYNC.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                lockRef,
                mapOf(
                    "bookingStatus" to BookingStatus.CONFIRMED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                paymentRef,
                mapOf(
                    "bookingId" to booking.id,
                    "customerUid" to uid,
                    "expertUid" to booking.expertUid,
                    "razorpayOrderId" to orderId,
                    "razorpayPaymentId" to paymentId,
                    "paymentMethod" to paymentMethod,
                    "currency" to BrandConfig.CURRENCY_CODE,
                    "amountPaise" to booking.amountPaise,
                    "commissionPaise" to booking.commissionPaise,
                    "gatewayFeePaise" to booking.gatewayFeePaise,
                    "taxDeductionPaise" to booking.taxDeductionPaise,
                    "expertEarningPaise" to booking.expertEarningPaise,
                    "refundedAmountPaise" to 0L,
                    "paymentStatus" to PaymentStatus.SUCCESS.name,
                    "providerSettlementStatus" to ProviderSettlementStatus.SANDBOX_SETTLED.name,
                    "serverVerified" to false,
                    "idempotencyKey" to "idem_${booking.id}",
                    "testMode" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            // Double-entry / transparent immutable ledger records
            val capRef = db.collection("ledger_entries").document("led_cap_${booking.id}")
            batch.set(
                capRef,
                mapOf(
                    "entryType" to LedgerEntryType.PAYMENT_CAPTURED.name,
                    "direction" to LedgerDirection.CREDIT.name,
                    "entryStatus" to LedgerEntryStatus.SETTLED.name,
                    "bookingId" to booking.id,
                    "paymentId" to paymentDocId,
                    "customerUid" to uid,
                    "expertUid" to booking.expertUid,
                    "amountPaise" to booking.amountPaise,
                    "currency" to BrandConfig.CURRENCY_CODE,
                    "idempotencyKey" to "led_cap_${booking.id}",
                    "description" to "Sandbox payment captured for ${booking.serviceTitle}",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            val commRef = db.collection("ledger_entries").document("led_comm_${booking.id}")
            batch.set(
                commRef,
                mapOf(
                    "entryType" to LedgerEntryType.PLATFORM_COMMISSION.name,
                    "direction" to LedgerDirection.CREDIT.name,
                    "entryStatus" to LedgerEntryStatus.SETTLED.name,
                    "bookingId" to booking.id,
                    "paymentId" to paymentDocId,
                    "customerUid" to uid,
                    "expertUid" to booking.expertUid,
                    "amountPaise" to booking.commissionPaise,
                    "currency" to BrandConfig.CURRENCY_CODE,
                    "idempotencyKey" to "led_comm_${booking.id}",
                    "description" to "Platform commission (${CurrencyUtils.formatCommissionPercent(booking.commissionRateBps)})",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            if (booking.gatewayFeePaise > 0L) {
                val gwRef = db.collection("ledger_entries").document("led_gw_${booking.id}")
                batch.set(
                    gwRef,
                    mapOf(
                        "entryType" to LedgerEntryType.GATEWAY_FEE.name,
                        "direction" to LedgerDirection.DEBIT.name,
                        "entryStatus" to LedgerEntryStatus.SETTLED.name,
                        "bookingId" to booking.id,
                        "paymentId" to paymentDocId,
                        "customerUid" to uid,
                        "expertUid" to booking.expertUid,
                        "amountPaise" to booking.gatewayFeePaise,
                        "currency" to BrandConfig.CURRENCY_CODE,
                        "idempotencyKey" to "led_gw_${booking.id}",
                        "description" to "Estimated payment gateway fee (platform side)",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            if (booking.taxDeductionPaise > 0L) {
                val taxRef = db.collection("ledger_entries").document("led_tax_${booking.id}")
                batch.set(
                    taxRef,
                    mapOf(
                        "entryType" to LedgerEntryType.TAX_DEDUCTION.name,
                        "direction" to LedgerDirection.DEBIT.name,
                        "entryStatus" to LedgerEntryStatus.SETTLED.name,
                        "bookingId" to booking.id,
                        "paymentId" to paymentDocId,
                        "customerUid" to uid,
                        "expertUid" to booking.expertUid,
                        "amountPaise" to booking.taxDeductionPaise,
                        "currency" to BrandConfig.CURRENCY_CODE,
                        "idempotencyKey" to "led_tax_${booking.id}",
                        "description" to "TDS tax deduction withheld on expert fee",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            val earnRef = db.collection("ledger_entries").document("led_earn_${booking.id}")
            batch.set(
                earnRef,
                mapOf(
                    "entryType" to LedgerEntryType.EXPERT_EARNING.name,
                    "direction" to LedgerDirection.CREDIT.name,
                    "entryStatus" to LedgerEntryStatus.PENDING.name,
                    "bookingId" to booking.id,
                    "paymentId" to paymentDocId,
                    "customerUid" to uid,
                    "expertUid" to booking.expertUid,
                    "amountPaise" to booking.expertEarningPaise,
                    "currency" to BrandConfig.CURRENCY_CODE,
                    "idempotencyKey" to "led_earn_${booking.id}",
                    "description" to "Pending expert earning for ${booking.serviceTitle} (held until attendance & 24h dispute window clear)",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            // Persistent notifications for Customer and Expert
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to uid,
                    "title" to "Consultation Confirmed (Sandbox)",
                    "message" to "${booking.serviceTitle} with ${booking.expertName} confirmed. Join Google Meet using ${booking.verifiedCustomerGoogleEmail}. Check-In PIN: ${booking.checkInPin}",
                    "eventType" to "BOOKING_CONFIRMED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to booking.expertUid,
                    "title" to "New Paid Booking",
                    "message" to "${booking.customerName} (${booking.verifiedCustomerGoogleEmail}) booked ${booking.serviceTitle}. Check-In PIN: ${booking.checkInPin}",
                    "eventType" to "NEW_BOOKING",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            batch.commit().await()
            Result.success(paymentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "payments/$paymentDocId")
            Result.failure(e)
        }
    }

    suspend fun recordPaymentFailure(booking: Booking, paymentMethod: String): Result<Unit> {
        val paymentDocId = "pay_fail_${booking.id}_${System.currentTimeMillis()}"
        return try {
            val uid = requireUserId()
            check(booking.customerUid == uid) { "Only the booking customer can update payment." }
            if (!BrandConfig.allowDebugSandboxFallback) {
                return Result.failure(
                    IllegalStateException("Payment failure tracking is handled server-side in production.")
                )
            }
            val batch = db.batch()
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "paymentStatus" to PaymentStatus.FAILED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("payments").document(paymentDocId),
                mapOf(
                    "bookingId" to booking.id,
                    "customerUid" to uid,
                    "expertUid" to booking.expertUid,
                    "razorpayOrderId" to "order_rzp_sandbox_${booking.id.take(12)}",
                    "paymentMethod" to paymentMethod,
                    "currency" to BrandConfig.CURRENCY_CODE,
                    "amountPaise" to booking.amountPaise,
                    "commissionPaise" to booking.commissionPaise,
                    "gatewayFeePaise" to booking.gatewayFeePaise,
                    "taxDeductionPaise" to booking.taxDeductionPaise,
                    "expertEarningPaise" to booking.expertEarningPaise,
                    "refundedAmountPaise" to 0L,
                    "paymentStatus" to PaymentStatus.FAILED.name,
                    "providerSettlementStatus" to ProviderSettlementStatus.PENDING_CAPTURE.name,
                    "serverVerified" to false,
                    "idempotencyKey" to "idem_fail_${booking.id}_${System.currentTimeMillis()}",
                    "testMode" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to uid,
                    "title" to "Payment Failed",
                    "message" to "Payment failed for ${booking.serviceTitle}. Booking remains unconfirmed until paid within hold window.",
                    "eventType" to "PAYMENT_FAILED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "payments/$paymentDocId")
            Result.failure(e)
        }
    }

    suspend fun syncGoogleCalendarAndMeet(booking: Booking, isAdmin: Boolean = false): Result<String> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            check(isAdmin || booking.customerUid == uid || booking.expertUid == uid) {
                "Only booking participants or admin can trigger Calendar & Meet sync."
            }
            when (val res = backendClient.syncGoogleCalendarAndMeet(booking.id)) {
                is BackendCallResult.NotConfigured -> {
                    if (!isAdmin &&
                        booking.calendarSyncStatus != CalendarSyncStatus.PENDING_BACKEND_SYNC.name &&
                        booking.calendarSyncStatus != CalendarSyncStatus.SYNCED.name
                    ) {
                        db.collection("bookings").document(booking.id).update(
                            mapOf(
                                "calendarSyncStatus" to CalendarSyncStatus.PENDING_BACKEND_SYNC.name,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                    Result.failure(
                        IllegalStateException(
                            "Google Calendar & Meet API backend URL (HRCONNECT_BACKEND_BASE_URL) is not configured. Attach a Google Meet link manually for sandbox sessions."
                        )
                    )
                }
                is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                is BackendCallResult.Success -> {
                    val data = res.data
                    // Backend /api/bookings/sync-calendar already persists Calendar/Meet IDs and SYNCED/SCHEDULED status via Admin SDK;
                    // client must never write backend-protected fields (blocked by firestore.rules).
                    if (data.meetingUrl.isNotBlank() && data.googleCalendarEventId.isNotBlank()) {
                        Result.success(data.meetingUrl)
                    } else {
                        Result.failure(
                            IllegalStateException(
                                "Backend Calendar sync status is ${data.syncStatus}; Google Calendar & Meet event creation is pending live workspace credentials."
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun syncGoogleMeetAttendance(booking: Booking, isAdmin: Boolean = false): Result<String> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            check(isAdmin || booking.customerUid == uid || booking.expertUid == uid) {
                "Only booking participants or admin can inspect Meet attendance."
            }
            when (val res = backendClient.syncGoogleMeetAttendance(booking.id)) {
                is BackendCallResult.Success -> {
                    val summary = res.data.optString("summary", "Google Meet attendance synced.")
                    Result.success(summary)
                }
                is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                is BackendCallResult.NotConfigured -> {
                    if (!BrandConfig.allowDebugSandboxFallback) {
                        return Result.failure(
                            IllegalStateException(
                                "Google Meet REST API attendance verification requires the production backend (HRCONNECT_BACKEND_BASE_URL)."
                            )
                        )
                    }
                    val eval = AttendanceVerificationEngine.evaluateCheckInFallback(
                        customerCheckInAtMs = booking.customerCheckInAtMs,
                        expertCheckInAtMs = booking.expertCheckInAtMs,
                        customerConfirmedDone = booking.customerCompletedConfirmed,
                        expertConfirmedDone = booking.expertCompletedConfirmed
                    )
                    db.collection("bookings").document(booking.id).update(
                        mapOf(
                            "attendanceStatus" to eval.status.name,
                            "attendanceVerificationSource" to eval.verificationSource.name,
                            "attendanceNotes" to eval.summary,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    Result.success("Google Meet API backend unavailable; evaluated supplementary check-in fallback (${eval.status.name}).")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun rescheduleBooking(
        booking: Booking,
        newDateIso: String,
        newStartMinute: Int,
        reason: String
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            check(booking.customerUid == uid || booking.expertUid == uid) {
                "Only booking participants can reschedule."
            }
            require(TimeSlotUtils.isFutureSlot(newDateIso, newStartMinute)) {
                "New consultation slot must be in the future."
            }
            if (!BrandConfig.isBackendConfigured) {
                return Result.failure(
                    IllegalStateException("Booking rescheduling with slot lock & Calendar sync requires HRCONNECT_BACKEND_BASE_URL.")
                )
            }
            val cleanReason = reason.trim().ifBlank { "Participant requested reschedule" }.take(300)
            when (val res = backendClient.rescheduleBooking(booking.id, newDateIso, newStartMinute, cleanReason)) {
                is BackendCallResult.Success -> Result.success(Unit)
                is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelBooking(booking: Booking, reason: String): Result<Unit> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            val cleanReason = reason.trim().ifBlank { "Cancelled by participant" }.take(500)
            if (BrandConfig.isBackendConfigured) {
                return when (val res = backendClient.cancelBooking(booking.id, cleanReason)) {
                    is BackendCallResult.Success -> Result.success(Unit)
                    is BackendCallResult.Error -> Result.failure(IllegalStateException(res.message))
                    is BackendCallResult.NotConfigured -> Result.failure(IllegalStateException("Backend unavailable"))
                }
            }
            if (!BrandConfig.allowDebugSandboxFallback) {
                return Result.failure(
                    IllegalStateException("Booking cancellation with Calendar cleanup and Razorpay refund requires HRCONNECT_BACKEND_BASE_URL.")
                )
            }
            val isExpert = uid == booking.expertUid
            val decision = ConsultationLifecycleEngine.evaluateCancellationRefund(
                booking = booking,
                cancelledByExpert = isExpert
            )
            check(decision.allowed) { decision.policyNote }

            val batch = db.batch()
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "bookingStatus" to BookingStatus.CANCELLED.name,
                    "cancellationReason" to "$cleanReason (${decision.policyNote})".take(500),
                    "cancelledByUid" to uid,
                    "payoutEligibilityStatus" to if (decision.refundPaise > 0L) PayoutEligibilityStatus.ON_HOLD.name else PayoutEligibilityStatus.REVERSED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("slot_locks").document(booking.slotKey),
                mapOf(
                    "bookingStatus" to BookingStatus.CANCELLED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (booking.paymentStatusEnum() == PaymentStatus.SUCCESS && decision.refundPaise > 0L) {
                val expertDebitPaise = (booking.expertEarningPaise - decision.expertCompensationPaise)
                    .coerceAtLeast(0L)
                if (expertDebitPaise > 0L) {
                    batch.set(
                        db.collection("ledger_entries").document("led_cancel_${booking.id}"),
                        mapOf(
                            "entryType" to LedgerEntryType.CANCELLATION_ADJUSTMENT.name,
                            "direction" to LedgerDirection.DEBIT.name,
                            "entryStatus" to LedgerEntryStatus.PENDING.name,
                            "bookingId" to booking.id,
                            "customerUid" to booking.customerUid,
                            "expertUid" to booking.expertUid,
                            "amountPaise" to expertDebitPaise,
                            "currency" to BrandConfig.CURRENCY_CODE,
                            "idempotencyKey" to "led_cancel_${booking.id}",
                            "description" to "${decision.policyNote} ($cleanReason)".take(300),
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            val otherParty = if (isExpert) booking.customerUid else booking.expertUid
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to otherParty,
                    "title" to "Consultation Cancelled",
                    "message" to "${booking.serviceTitle} on ${TimeSlotUtils.formatDateDisplay(booking.dateIso)} was cancelled: $cleanReason",
                    "eventType" to "BOOKING_CANCELLED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
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

    suspend fun updateMeetingUrl(booking: Booking, meetingUrl: String): Result<Unit> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            check(booking.expertUid == uid) { "Only the assigned expert can attach a meeting link." }
            check(booking.bookingStatusEnum() in setOf(BookingStatus.CONFIRMED, BookingStatus.SCHEDULED)) {
                "Meeting links can only be attached to confirmed or scheduled consultations."
            }
            check(!(booking.calendarSyncStatus == CalendarSyncStatus.SYNCED.name && !booking.googleMeetSpaceId.isNullOrBlank())) {
                "This booking is synced with a tracked Google Calendar & Meet space; reschedule to issue a new invite."
            }
            val cleanUrl = meetingUrl.trim().take(500)
            require(cleanUrl.startsWith("https://meet.google.com/") || cleanUrl.startsWith("https://")) {
                "Please enter a valid https:// Google Meet link."
            }
            val batch = db.batch()
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "meetingUrl" to cleanUrl,
                    "bookingStatus" to BookingStatus.SCHEDULED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to booking.customerUid,
                    "title" to "Google Meet Link Ready",
                    "message" to "${booking.expertName} shared the meeting link for ${booking.serviceTitle}. Join using ${booking.verifiedCustomerGoogleEmail}.",
                    "eventType" to "MEETING_LINK_ADDED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
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

    /**
     * Supplementary Check-In PIN recording (explicitly distinct from Google Meet REST API attendance verification).
     */
    suspend fun submitCheckIn(booking: Booking, enteredPin: String): Result<Unit> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            val isCustomer = uid == booking.customerUid
            val isExpert = uid == booking.expertUid
            check(isCustomer || isExpert)
            check(booking.bookingStatusEnum() in setOf(
                BookingStatus.CONFIRMED, BookingStatus.SCHEDULED,
                BookingStatus.IN_PROGRESS, BookingStatus.ATTENDANCE_PENDING,
                BookingStatus.COMPLETION_PENDING
            )) { "Check-in is not active for status ${booking.bookingStatus}." }

            val expectedPin = booking.checkInPin
            require(expectedPin.isNullOrBlank() || enteredPin.trim() == expectedPin) {
                "Check-In PIN does not match this booking's verification PIN."
            }
            val nowMs = System.currentTimeMillis()
            val custMs = if (isCustomer) nowMs else booking.customerCheckInAtMs
            val expMs = if (isExpert) nowMs else booking.expertCheckInAtMs

            val eval = AttendanceVerificationEngine.evaluateCheckInFallback(
                customerCheckInAtMs = custMs,
                expertCheckInAtMs = expMs,
                customerConfirmedDone = booking.customerCompletedConfirmed,
                expertConfirmedDone = booking.expertCompletedConfirmed
            )

            val updates = mutableMapOf<String, Any>(
                "bookingStatus" to BookingStatus.IN_PROGRESS.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isCustomer) updates["customerCheckInAtMs"] = nowMs
            if (isExpert) updates["expertCheckInAtMs"] = nowMs
            if (booking.attendanceSourceEnum() != VerificationSource.GOOGLE_MEET_REST_API) {
                updates["attendanceVerificationSource"] = VerificationSource.SUPPLEMENTARY_CHECKIN_FALLBACK.name
                updates["attendanceStatus"] = eval.status.name
                updates["attendanceNotes"] = eval.summary
            }

            db.collection("bookings").document(booking.id).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    /**
     * Dual-party completion workflow.
     * NEVER immediately unlocks payout — starts the 24-hour customer dispute hold window once completed,
     * and requires verified/reviewed attendance before FinancialLedgerEngine treats funds as eligible.
     */
    suspend fun confirmBookingCompletion(booking: Booking, isExpert: Boolean): Result<Unit> {
        val path = "bookings/${booking.id}"
        return try {
            val uid = requireUserId()
            if (isExpert) {
                check(booking.expertUid == uid) { "Only the assigned expert can confirm completion." }
            } else {
                check(booking.customerUid == uid) { "Only the booking customer can confirm completion." }
            }
            check(booking.bookingStatusEnum() in setOf(
                BookingStatus.CONFIRMED, BookingStatus.SCHEDULED,
                BookingStatus.IN_PROGRESS, BookingStatus.ATTENDANCE_PENDING,
                BookingStatus.COMPLETION_PENDING
            )) { "Booking is not in an active session state." }

            val custDone = if (!isExpert) true else booking.customerCompletedConfirmed
            val expDone = if (isExpert) true else booking.expertCompletedConfirmed
            val nowMs = System.currentTimeMillis()
            val windowEndsAt = nowMs + BrandConfig.DEFAULT_DISPUTE_WINDOW_HOURS * 3_600_000L

            val bothDone = custDone && expDone
            val nextBookingStatus = if (bothDone) BookingStatus.COMPLETED else BookingStatus.COMPLETION_PENDING

            val batch = db.batch()
            val updates = mutableMapOf<String, Any>(
                "customerCompletedConfirmed" to custDone,
                "expertCompletedConfirmed" to expDone,
                "bookingStatus" to nextBookingStatus.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (bothDone) {
                updates["completedAtEpochMs"] = nowMs
                updates["disputeWindowEndsAtEpochMs"] = windowEndsAt
            }
            if (booking.attendanceStatusEnum() == AttendanceStatus.NOT_STARTED) {
                updates["attendanceStatus"] = AttendanceStatus.MANUAL_REVIEW.name
                updates["attendanceNotes"] = "Completed without Google Meet REST API conference logs; requires admin attendance check or dual check-in before payout."
            }

            batch.update(db.collection("bookings").document(booking.id), updates)
            batch.update(
                db.collection("slot_locks").document(booking.slotKey),
                mapOf(
                    "bookingStatus" to nextBookingStatus.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            val otherParty = if (isExpert) booking.customerUid else booking.expertUid
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to otherParty,
                    "title" to if (bothDone) "Consultation Completed" else "Completion Confirmation Requested",
                    "message" to if (bothDone) {
                        "Both parties confirmed '${booking.serviceTitle}'. 24h dispute protection window active; leave a verified review!"
                    } else {
                        "Your counterpart confirmed '${booking.serviceTitle}' is done. Please confirm completion or report any issue."
                    },
                    "eventType" to if (bothDone) "BOOKING_COMPLETED" else "COMPLETION_CONFIRMATION_PENDING",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
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

    suspend fun raiseDispute(booking: Booking, reason: String, evidenceSummary: String): Result<Unit> {
        val path = "disputes/${booking.id}"
        return try {
            val uid = requireUserId()
            check(booking.customerUid == uid || booking.expertUid == uid)
            val cleanReason = reason.trim()
            require(cleanReason.length >= 10) { "Please describe the issue in at least 10 characters." }
            check(booking.bookingStatusEnum() !in setOf(BookingStatus.PENDING_PAYMENT, BookingStatus.REFUNDED)) {
                "Dispute cannot be opened on an unpaid or already refunded booking."
            }

            val batch = db.batch()
            batch.set(
                db.collection("disputes").document(booking.id),
                mapOf(
                    "bookingId" to booking.id,
                    "customerUid" to booking.customerUid,
                    "expertUid" to booking.expertUid,
                    "raisedByUid" to uid,
                    "reason" to cleanReason.take(800),
                    "evidenceSummary" to evidenceSummary.trim().take(1000),
                    "status" to DisputeStatus.OPEN.name,
                    "requestedRefundPaise" to booking.amountPaise,
                    "approvedRefundPaise" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "bookingStatus" to BookingStatus.DISPUTED.name,
                    "disputeStatus" to DisputeStatus.OPEN.name,
                    "disputeReason" to cleanReason.take(500),
                    "payoutEligibilityStatus" to PayoutEligibilityStatus.ON_HOLD.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("ledger_entries").document("led_hold_${booking.id}"),
                mapOf(
                    "entryType" to LedgerEntryType.DISPUTE_HOLD.name,
                    "direction" to LedgerDirection.DEBIT.name,
                    "entryStatus" to LedgerEntryStatus.PENDING.name,
                    "bookingId" to booking.id,
                    "customerUid" to booking.customerUid,
                    "expertUid" to booking.expertUid,
                    "amountPaise" to booking.expertEarningPaise,
                    "currency" to BrandConfig.CURRENCY_CODE,
                    "idempotencyKey" to "led_hold_${booking.id}",
                    "description" to "Payout frozen due to active dispute: ${cleanReason.take(150)}",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            val otherParty = if (uid == booking.customerUid) booking.expertUid else booking.customerUid
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to otherParty,
                    "title" to "Dispute Opened — Payout Held",
                    "message" to "A dispute was filed on '${booking.serviceTitle}'. Funds are placed on hold pending admin review.",
                    "eventType" to "DISPUTE_OPENED",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun submitReview(
        booking: Booking,
        rating: Int,
        comment: String
    ): Result<Unit> {
        val path = "reviews/${booking.id}"
        return try {
            val uid = requireUserId()
            require(rating in 1..5) { "Rating must be between 1 and 5 stars." }
            check(booking.customerUid == uid && booking.expertUid != uid) {
                "Only the verified customer can review this consultation."
            }
            check(booking.bookingStatusEnum() == BookingStatus.COMPLETED) {
                "You can only review completed consultations."
            }
            check(!booking.reviewed) { "A review has already been submitted for this booking." }

            val batch = db.batch()
            batch.set(
                db.collection("reviews").document(booking.id),
                mapOf(
                    "bookingId" to booking.id,
                    "expertId" to booking.expertId,
                    "expertUid" to booking.expertUid,
                    "customerUid" to uid,
                    "customerName" to booking.customerName,
                    "rating" to rating.toLong(),
                    "comment" to comment.trim().take(1000),
                    "reported" to false,
                    "moderationStatus" to "VISIBLE",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("bookings").document(booking.id),
                mapOf(
                    "reviewed" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("notifications").document(),
                mapOf(
                    "recipientUid" to booking.expertUid,
                    "title" to "New Verified Review ($rating★)",
                    "message" to "${booking.customerName} left a $rating-star review for ${booking.serviceTitle}.",
                    "eventType" to "NEW_REVIEW",
                    "relatedBookingId" to booking.id,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun reportReview(reviewId: String, reason: String): Result<Unit> {
        val path = "reviews/$reviewId"
        return try {
            requireUserId()
            val cleanReason = reason.trim().ifBlank { "Inappropriate content" }.take(300)
            db.collection("reviews").document(reviewId).update(
                mapOf(
                    "reported" to true,
                    "reportReason" to cleanReason,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }
}
