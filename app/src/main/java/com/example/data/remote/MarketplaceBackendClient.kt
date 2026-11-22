package com.example.data.remote

import com.example.core.BrandConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface BackendCallResult<out T> {
    data class Success<T>(val data: T) : BackendCallResult<T>
    data class Error(val message: String, val code: Int = -1) : BackendCallResult<Nothing>
    data object NotConfigured : BackendCallResult<Nothing>
}

data class BackendOrderResponse(
    val orderId: String,
    val amountPaise: Long,
    val currency: String,
    val commissionRateBps: Long,
    val commissionPaise: Long,
    val expertEarningPaise: Long,
    val testMode: Boolean,
    val razorpayKeyId: String = BrandConfig.razorpayKeyId,
    val requiresSdkCheckout: Boolean = !BrandConfig.isPlaceholderRazorpayKey(razorpayKeyId)
)

data class BackendPaymentVerifyResponse(
    val verified: Boolean,
    val paymentId: String,
    val gatewayFeePaise: Long,
    val taxDeductionPaise: Long,
    val settlementStatus: String,
    val googleCalendarEventId: String?,
    val meetingUrl: String?,
    val googleMeetSpaceId: String?
)

data class BackendCalendarSyncResponse(
    val googleCalendarEventId: String,
    val meetingUrl: String,
    val googleMeetSpaceId: String?,
    val googleMeetCode: String?,
    val syncStatus: String
)

data class BackendPayoutOnboardingResponse(
    val expertId: String,
    val payoutAccountType: String,
    val payoutDestinationMasked: String,
    val payoutKycStatus: String
)

/**
 * Production backend HTTP bridge for server-verified Razorpay orders, payment signature/webhook verification,
 * atomic booking holds/reschedules/cancellations, RazorpayX expert payout onboarding & transfers,
 * Google Calendar event management, Google Meet REST API v2 attendance verification, and FCM push tokens.
 *
 * Returns [BackendCallResult.NotConfigured] cleanly whenever HRCONNECT_BACKEND_BASE_URL is blank.
 */
class MarketplaceBackendClient(
    private val auth: FirebaseAuth
) {
    private suspend fun authorizedPost(path: String, body: JSONObject): BackendCallResult<JSONObject> {
        val baseUrl = BrandConfig.backendBaseUrl
        if (!BrandConfig.isBackendConfigured) {
            return BackendCallResult.NotConfigured
        }
        val initialToken = auth.currentUser?.getIdToken(false)?.await()?.token
            ?: return BackendCallResult.Error("Authentication token unavailable", 401)

        val firstAttempt = executeAuthorizedPost(baseUrl, path, body, initialToken)
        if (firstAttempt is BackendCallResult.Error && firstAttempt.code == 401) {
            val refreshedToken = auth.currentUser?.getIdToken(true)?.await()?.token
            if (!refreshedToken.isNullOrBlank() && refreshedToken != initialToken) {
                return executeAuthorizedPost(baseUrl, path, body, refreshedToken)
            }
        }
        return firstAttempt
    }

    private suspend fun executeAuthorizedPost(
        baseUrl: String,
        path: String,
        body: JSONObject,
        token: String
    ): BackendCallResult<JSONObject> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl$path")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
            if (code in 200..299) {
                BackendCallResult.Success(json)
            } else {
                BackendCallResult.Error(json.optString("error", "Backend HTTP $code"), code)
            }
        } catch (e: Exception) {
            BackendCallResult.Error(e.message ?: "Network error contacting backend")
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun createBookingHold(
        expertId: String,
        serviceId: String,
        dateIso: String,
        startMinute: Int,
        topic: String,
        referralCode: String,
        customerName: String
    ): BackendCallResult<JSONObject> =
        authorizedPost(
            "/api/bookings/create",
            JSONObject()
                .put("expertId", expertId)
                .put("serviceId", serviceId)
                .put("dateIso", dateIso)
                .put("startMinute", startMinute)
                .put("topic", topic)
                .put("referralCode", referralCode)
                .put("customerName", customerName)
        )

    suspend fun rescheduleBooking(
        bookingId: String,
        newDateIso: String,
        newStartMinute: Int,
        reason: String
    ): BackendCallResult<JSONObject> =
        authorizedPost(
            "/api/bookings/reschedule",
            JSONObject()
                .put("bookingId", bookingId)
                .put("newDateIso", newDateIso)
                .put("newStartMinute", newStartMinute)
                .put("reason", reason)
        )

    suspend fun cancelBooking(
        bookingId: String,
        reason: String
    ): BackendCallResult<JSONObject> =
        authorizedPost(
            "/api/bookings/cancel",
            JSONObject()
                .put("bookingId", bookingId)
                .put("reason", reason)
        )

    suspend fun createRazorpayOrder(bookingId: String): BackendCallResult<BackendOrderResponse> {
        val res = authorizedPost(
            "/api/payments/create-order",
            JSONObject().put("bookingId", bookingId)
        )
        return when (res) {
            is BackendCallResult.NotConfigured -> BackendCallResult.NotConfigured
            is BackendCallResult.Error -> res
            is BackendCallResult.Success -> {
                val d = res.data
                val resolvedKeyId = d.optString("razorpayKeyId").ifBlank { BrandConfig.razorpayKeyId }
                BackendCallResult.Success(
                    BackendOrderResponse(
                        orderId = d.optString("orderId"),
                        amountPaise = d.optLong("amountPaise"),
                        currency = d.optString("currency", "INR"),
                        commissionRateBps = d.optLong("commissionRateBps"),
                        commissionPaise = d.optLong("commissionPaise"),
                        expertEarningPaise = d.optLong("expertEarningPaise"),
                        testMode = d.optBoolean("testMode", true),
                        razorpayKeyId = resolvedKeyId,
                        requiresSdkCheckout = d.optBoolean(
                            "requiresSdkCheckout",
                            !BrandConfig.isPlaceholderRazorpayKey(resolvedKeyId)
                        )
                    )
                )
            }
        }
    }

    suspend fun verifyRazorpayPayment(
        bookingId: String,
        orderId: String,
        paymentId: String,
        signature: String,
        paymentMethod: String = "RAZORPAY"
    ): BackendCallResult<BackendPaymentVerifyResponse> {
        val body = JSONObject()
            .put("bookingId", bookingId)
            .put("razorpayOrderId", orderId)
            .put("razorpayPaymentId", paymentId)
            .put("razorpaySignature", signature)
            .put("paymentMethod", paymentMethod)
        val res = authorizedPost("/api/payments/verify", body)
        return when (res) {
            is BackendCallResult.NotConfigured -> BackendCallResult.NotConfigured
            is BackendCallResult.Error -> res
            is BackendCallResult.Success -> {
                val d = res.data
                BackendCallResult.Success(
                    BackendPaymentVerifyResponse(
                        verified = d.optBoolean("verified", false),
                        paymentId = d.optString("razorpayPaymentId", paymentId),
                        gatewayFeePaise = d.optLong("gatewayFeePaise", 0L),
                        taxDeductionPaise = d.optLong("taxDeductionPaise", 0L),
                        settlementStatus = d.optString("settlementStatus", "CAPTURED_UNSETTLED"),
                        googleCalendarEventId = d.optString("googleCalendarEventId").takeIf { it.isNotBlank() },
                        meetingUrl = d.optString("meetingUrl").takeIf { it.isNotBlank() },
                        googleMeetSpaceId = d.optString("googleMeetSpaceId").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    suspend fun syncGoogleCalendarAndMeet(bookingId: String): BackendCallResult<BackendCalendarSyncResponse> {
        val res = authorizedPost(
            "/api/bookings/sync-calendar",
            JSONObject().put("bookingId", bookingId)
        )
        return when (res) {
            is BackendCallResult.NotConfigured -> BackendCallResult.NotConfigured
            is BackendCallResult.Error -> res
            is BackendCallResult.Success -> {
                val d = res.data
                BackendCallResult.Success(
                    BackendCalendarSyncResponse(
                        googleCalendarEventId = d.optString("googleCalendarEventId"),
                        meetingUrl = d.optString("meetingUrl"),
                        googleMeetSpaceId = d.optString("googleMeetSpaceId").takeIf { it.isNotBlank() },
                        googleMeetCode = d.optString("googleMeetCode").takeIf { it.isNotBlank() },
                        syncStatus = d.optString("syncStatus", "PENDING_BACKEND_SYNC")
                    )
                )
            }
        }
    }

    suspend fun syncGoogleMeetAttendance(bookingId: String): BackendCallResult<JSONObject> =
        authorizedPost("/api/bookings/sync-meet-attendance", JSONObject().put("bookingId", bookingId))

    suspend fun initiateBackendRefund(bookingId: String, refundAmountPaise: Long, reason: String): BackendCallResult<JSONObject> =
        authorizedPost(
            "/api/refunds/initiate",
            JSONObject()
                .put("bookingId", bookingId)
                .put("refundAmountPaise", refundAmountPaise)
                .put("reason", reason)
        )

    suspend fun saveExpertPayoutOnboarding(
        expertId: String,
        payoutAccountType: String,
        vpaAddress: String,
        bankAccountHolderName: String,
        bankIfsc: String,
        bankAccountNumber: String
    ): BackendCallResult<BackendPayoutOnboardingResponse> {
        val res = authorizedPost(
            "/api/experts/payout-onboarding",
            JSONObject()
                .put("expertId", expertId)
                .put("payoutAccountType", payoutAccountType)
                .put("vpaAddress", vpaAddress)
                .put("bankAccountHolderName", bankAccountHolderName)
                .put("bankIfsc", bankIfsc)
                .put("bankAccountNumber", bankAccountNumber)
        )
        return when (res) {
            is BackendCallResult.NotConfigured -> BackendCallResult.NotConfigured
            is BackendCallResult.Error -> res
            is BackendCallResult.Success -> {
                val d = res.data
                BackendCallResult.Success(
                    BackendPayoutOnboardingResponse(
                        expertId = d.optString("expertId", expertId),
                        payoutAccountType = d.optString("payoutAccountType", payoutAccountType),
                        payoutDestinationMasked = d.optString("payoutDestinationMasked"),
                        payoutKycStatus = d.optString("payoutKycStatus", "PENDING_RAZORPAYX_LINK")
                    )
                )
            }
        }
    }

    suspend fun triggerBackendPayout(
        payoutRequestId: String,
        newStatus: String = "PROCESSING",
        payoutReference: String = "",
        adminNote: String = ""
    ): BackendCallResult<JSONObject> =
        authorizedPost(
            "/api/payouts/process",
            JSONObject()
                .put("payoutRequestId", payoutRequestId)
                .put("newStatus", newStatus)
                .put("payoutReference", payoutReference)
                .put("adminNote", adminNote)
        )

    suspend fun registerFcmToken(
        fcmToken: String,
        deviceLabel: String = ""
    ): BackendCallResult<JSONObject> =
        authorizedPost(
            "/api/notifications/register-token",
            JSONObject()
                .put("fcmToken", fcmToken)
                .put("platform", "android")
                .put("deviceId", deviceLabel)
        )
}
