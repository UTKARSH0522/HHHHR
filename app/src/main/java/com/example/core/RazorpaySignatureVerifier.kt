package com.example.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PaymentVerificationRequest(
    val orderId: String,
    val paymentId: String,
    val signature: String,
    val expectedAmountPaise: Long,
    val actualAmountPaise: Long,
    val currency: String,
    val holdExpiresAtEpochMs: Long,
    val nowEpochMs: Long = System.currentTimeMillis()
)

sealed interface PaymentVerificationResult {
    data object Verified : PaymentVerificationResult
    data class Rejected(val reason: String) : PaymentVerificationResult
}

object RazorpaySignatureVerifier {

    fun computeHmacSha256Hex(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun verifyOrderSignature(
        orderId: String,
        paymentId: String,
        signature: String,
        secret: String
    ): Boolean {
        if (orderId.isBlank() || paymentId.isBlank() || signature.isBlank() || secret.isBlank()) {
            return false
        }
        val expected = computeHmacSha256Hex("$orderId|$paymentId", secret)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    fun verifyWebhookSignature(
        rawBody: String,
        webhookSignature: String,
        webhookSecret: String
    ): Boolean {
        if (rawBody.isBlank() || webhookSignature.isBlank() || webhookSecret.isBlank()) {
            return false
        }
        val expected = computeHmacSha256Hex(rawBody, webhookSecret)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            webhookSignature.lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    fun verifyPaymentConsistency(
        request: PaymentVerificationRequest,
        signingSecret: String
    ): PaymentVerificationResult {
        if (request.currency != BrandConfig.CURRENCY_CODE) {
            return PaymentVerificationResult.Rejected("Invalid currency: expected INR, got ${request.currency}")
        }
        if (request.actualAmountPaise <= 0L || request.actualAmountPaise != request.expectedAmountPaise) {
            return PaymentVerificationResult.Rejected("Amount mismatch: expected ${request.expectedAmountPaise} paise")
        }
        if (request.nowEpochMs > request.holdExpiresAtEpochMs) {
            return PaymentVerificationResult.Rejected("Payment hold expired. Please select a fresh time slot.")
        }
        if (!verifyOrderSignature(request.orderId, request.paymentId, request.signature, signingSecret)) {
            return PaymentVerificationResult.Rejected("Razorpay payment signature verification failed.")
        }
        return PaymentVerificationResult.Verified
    }
}
