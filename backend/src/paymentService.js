const crypto = require("node:crypto");

function calculateFinancialSplit({
  amountPaise,
  commissionRateBps,
  gatewayFeeBps = 200,
  tdsBps = 0,
}) {
  if (!Number.isInteger(amountPaise) || amountPaise <= 0) {
    throw new Error("Invalid amountPaise");
  }
  if (!Number.isInteger(commissionRateBps) || commissionRateBps < 0 || commissionRateBps > 5000) {
    throw new Error("Invalid commissionRateBps");
  }
  if (!Number.isInteger(gatewayFeeBps) || gatewayFeeBps < 0 || gatewayFeeBps > 1000) {
    throw new Error("Invalid gatewayFeeBps");
  }
  if (!Number.isInteger(tdsBps) || tdsBps < 0 || tdsBps > 3000) {
    throw new Error("Invalid tdsBps");
  }

  const commissionPaise = Math.floor((amountPaise * commissionRateBps) / 10000);
  const grossExpertPayable = amountPaise - commissionPaise;
  const gatewayFeePaise = Math.floor((amountPaise * gatewayFeeBps) / 10000);
  const taxDeductionPaise = Math.floor((grossExpertPayable * tdsBps) / 10000);
  const expertEarningPaise = Math.max(0, grossExpertPayable - taxDeductionPaise);

  return {
    amountPaise,
    commissionRateBps,
    commissionPaise,
    gatewayFeePaise,
    taxDeductionPaise,
    expertEarningPaise,
  };
}

function computeProportionalRefundSplit(booking, refundPaise) {
  if (!booking || !Number.isInteger(refundPaise) || refundPaise <= 0) {
    throw new Error("Invalid refund amount");
  }
  const alreadyRefunded = Number(booking.refundedAmountPaise || 0);
  const totalAmount = Number(booking.amountPaise || 0);
  if (alreadyRefunded + refundPaise > totalAmount) {
    throw new Error("Refund amount exceeds remaining captured amount");
  }
  const newRefundTotal = alreadyRefunded + refundPaise;
  const isFullRefund = newRefundTotal >= totalAmount;
  const commissionReversalPaise = Math.floor((Number(booking.commissionPaise || 0) * refundPaise) / totalAmount);
  const expertReductionPaise = Math.floor((Number(booking.expertEarningPaise || 0) * refundPaise) / totalAmount);

  return {
    refundPaise,
    totalRefundedPaise: newRefundTotal,
    commissionReversalPaise,
    expertReductionPaise,
    isFullRefund,
  };
}

function timingSafeHexEquals(expectedHex, actualHex) {
  if (typeof expectedHex !== "string" || typeof actualHex !== "string") return false;
  const normExpected = expectedHex.trim().toLowerCase();
  const normActual = actualHex.trim().toLowerCase();
  if (!/^[0-9a-f]+$/.test(normExpected) || !/^[0-9a-f]+$/.test(normActual)) return false;
  if (normExpected.length !== normActual.length || normExpected.length === 0) return false;
  return crypto.timingSafeEqual(Buffer.from(normExpected, "hex"), Buffer.from(normActual, "hex"));
}

function computeRazorpayPaymentSignature({ orderId, paymentId, keySecret }) {
  if (!orderId || !paymentId || !keySecret) {
    throw new Error("orderId, paymentId, and keySecret are required");
  }
  return crypto
    .createHmac("sha256", keySecret)
    .update(`${orderId}|${paymentId}`)
    .digest("hex");
}

function verifyRazorpayPaymentSignature({ orderId, paymentId, signature, keySecret }) {
  if (!orderId || !paymentId || !signature || !keySecret) return false;
  const expected = computeRazorpayPaymentSignature({ orderId, paymentId, keySecret });
  return timingSafeHexEquals(expected, signature);
}

function computeRazorpayWebhookSignature({ rawBody, webhookSecret }) {
  if (!webhookSecret) throw new Error("webhookSecret is required");
  const payload = Buffer.isBuffer(rawBody) ? rawBody : Buffer.from(String(rawBody || ""), "utf8");
  return crypto.createHmac("sha256", webhookSecret).update(payload).digest("hex");
}

function verifyRazorpayWebhookSignature({ rawBody, signatureHeader, webhookSecret }) {
  if (!rawBody || !signatureHeader || !webhookSecret) return false;
  const expected = computeRazorpayWebhookSignature({ rawBody, webhookSecret });
  return timingSafeHexEquals(expected, signatureHeader);
}

function evaluateBookingEligibilityForServer(
  booking,
  nowEpochMs = Date.now(),
  disputeWindowHours = 24,
  options = {}
) {
  if (!booking) return { eligible: false, reason: "Booking not found", remainingPayablePaise: 0 };

  const allowCurrentProcessing = options.allowCurrentProcessing === true;
  if (booking.payoutEligibilityStatus === "PAID" || booking.payoutEligibilityStatus === "REVERSED") {
    return { eligible: false, reason: `Already ${booking.payoutEligibilityStatus}`, remainingPayablePaise: 0 };
  }
  if (booking.payoutEligibilityStatus === "PROCESSING" && !allowCurrentProcessing) {
    return { eligible: false, reason: "Already PROCESSING", remainingPayablePaise: 0 };
  }
  if (!["SUCCESS", "PARTIALLY_REFUNDED"].includes(booking.paymentStatus)) {
    return { eligible: false, reason: "Payment not settled", remainingPayablePaise: 0 };
  }
  if (!["SETTLED", "SANDBOX_SETTLED"].includes(booking.providerSettlementStatus)) {
    return { eligible: false, reason: "Provider settlement pending", remainingPayablePaise: 0 };
  }
  if (["DISPUTED", "CANCELLED", "REFUNDED"].includes(booking.bookingStatus)) {
    return { eligible: false, reason: `Blocked by bookingStatus=${booking.bookingStatus}`, remainingPayablePaise: 0 };
  }
  if (["OPEN", "UNDER_REVIEW"].includes(booking.disputeStatus)) {
    return { eligible: false, reason: "Active dispute hold", remainingPayablePaise: 0 };
  }
  const refunded = Number(booking.refundedAmountPaise || 0);
  const totalAmount = Number(booking.amountPaise || 0);
  if (totalAmount > 0 && refunded >= totalAmount) {
    return { eligible: false, reason: "Booking fully refunded", remainingPayablePaise: 0 };
  }

  const isAdminApproved =
    booking.attendanceVerificationSource === "ADMIN_MANUAL_REVIEW" &&
    typeof booking.attendanceNotes === "string" &&
    booking.attendanceNotes.toLowerCase().includes("admin_approved");
  const attendancePassed =
    ["VERIFIED", "PARTIALLY_VERIFIED"].includes(booking.attendanceStatus) ||
    (booking.attendanceStatus === "MANUAL_REVIEW" && isAdminApproved);
  if (!attendancePassed) {
    return { eligible: false, reason: `Attendance not verified (${booking.attendanceStatus})`, remainingPayablePaise: 0 };
  }

  const dualCompleted = Boolean(booking.customerCompletedConfirmed && booking.expertCompletedConfirmed);
  const adminOverrideCompletion =
    booking.bookingStatus === "COMPLETED" && isAdminApproved && attendancePassed;
  if (!dualCompleted && !adminOverrideCompletion) {
    return { eligible: false, reason: "Dual completion required", remainingPayablePaise: 0 };
  }

  const windowEnd =
    Number(booking.disputeWindowEndsAtEpochMs || 0) ||
    (Number(booking.completedAtEpochMs || 0) > 0
      ? Number(booking.completedAtEpochMs) + disputeWindowHours * 3600 * 1000
      : 0);
  if (!windowEnd || nowEpochMs < windowEnd) {
    return { eligible: false, reason: "24h dispute window active", remainingPayablePaise: 0 };
  }

  const expertEarning = Number(booking.expertEarningPaise || 0);
  const propReduction = totalAmount > 0 && refunded > 0 ? Math.floor((expertEarning * refunded) / totalAmount) : 0;
  const remainingPayablePaise = Math.max(0, expertEarning - propReduction);

  return {
    eligible: remainingPayablePaise > 0,
    reason: remainingPayablePaise > 0 ? "OK" : "Zero payable after refund",
    remainingPayablePaise,
  };
}

module.exports = {
  calculateFinancialSplit,
  computeProportionalRefundSplit,
  computeRazorpayPaymentSignature,
  verifyRazorpayPaymentSignature,
  computeRazorpayWebhookSignature,
  verifyRazorpayWebhookSignature,
  evaluateBookingEligibilityForServer,
};
