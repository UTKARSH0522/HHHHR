const crypto = require("node:crypto");
const { FieldValue } = require("firebase-admin/firestore");
const { evaluateBookingEligibilityForServer } = require("./paymentService");
const { HttpAppError, assertValidId, sanitizeDocId, VALID_ID_REGEX_OVERRIDE } = (() => {
  const br = require("./bookingRepository");
  return {
    HttpAppError: br.HttpAppError,
    assertValidId: br.assertValidId,
    sanitizeDocId: br.sanitizeDocId,
  };
})();
const { dispatchNotificationsWithPush } = require("./notificationService");

const VALID_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;

const ALLOWED_PAYOUT_TRANSITIONS = {
  PENDING: new Set(["APPROVED", "PROCESSING", "PAID", "REJECTED"]),
  APPROVED: new Set(["PROCESSING", "PAID", "REJECTED"]),
  PROCESSING: new Set(["PAID", "FAILED"]),
  FAILED: new Set(["PROCESSING", "REJECTED"]),
};

function maskDestination(accountType, vpaAddress, bankAccountNumber, bankIfsc) {
  if (accountType === "UPI") {
    const clean = String(vpaAddress || "").trim();
    const parts = clean.split("@");
    const prefix = parts[0] || "";
    const maskedPrefix = prefix.length <= 2 ? `${prefix}*` : `${prefix.slice(0, 2)}***`;
    return `UPI:${maskedPrefix}@${parts[1] || "upi"}`;
  }
  const num = String(bankAccountNumber || "").trim();
  const last4 = num.slice(-4);
  return `BANK:XXXX${last4}(${String(bankIfsc || "").trim().toUpperCase()})`;
}

async function saveExpertPayoutOnboarding({
  db,
  actor,
  expertId = "",
  payoutAccountType,
  vpaAddress = "",
  bankAccountHolderName = "",
  bankIfsc = "",
  bankAccountNumber = "",
  razorpayClient = null,
}) {
  const targetExpertId = expertId ? assertValidId(expertId, "expertId") : `exp_${actor.uid}`;
  const acctType = String(payoutAccountType || "").trim().toUpperCase();
  if (!["UPI", "BANK_ACCOUNT"].includes(acctType)) {
    throw new HttpAppError(400, "payoutAccountType must be UPI or BANK_ACCOUNT");
  }

  let cleanVpa = "";
  let cleanHolder = "";
  let cleanIfsc = "";
  let cleanAcctNum = "";

  if (acctType === "UPI") {
    cleanVpa = String(vpaAddress || "").trim().toLowerCase();
    if (!/^[a-z0-9._-]{2,64}@[a-z0-9]{2,32}$/.test(cleanVpa)) {
      throw new HttpAppError(400, "Enter a valid UPI VPA (e.g., name@okaxis)");
    }
  } else {
    cleanHolder = String(bankAccountHolderName || "").trim();
    cleanIfsc = String(bankIfsc || "").trim().toUpperCase();
    cleanAcctNum = String(bankAccountNumber || "").replace(/\s+/g, "");
    if (cleanHolder.length < 2 || cleanHolder.length > 100) {
      throw new HttpAppError(400, "Valid bank account holder name is required");
    }
    if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(cleanIfsc)) {
      throw new HttpAppError(400, "Valid 11-character IFSC code is required (e.g., HDFC0001234)");
    }
    if (!/^\d{6,18}$/.test(cleanAcctNum)) {
      throw new HttpAppError(400, "Bank account number must be 6 to 18 digits");
    }
  }

  const expertRef = db.collection("experts").doc(targetExpertId);
  const expertSnap = await expertRef.get();
  if (!expertSnap.exists) {
    throw new HttpAppError(404, "Expert profile not found; submit your expert application first");
  }
  const expert = expertSnap.data();
  if (!actor.isAdmin && expert.expertUid !== actor.uid) {
    throw new HttpAppError(403, "Forbidden: you can only configure payout details for your own expert profile");
  }

  const masked = maskDestination(acctType, cleanVpa, cleanAcctNum, cleanIfsc);
  let contactId = expert.razorpayContactId || "";
  let fundAccountId = "";
  let payoutKycStatus = "PENDING_RAZORPAYX_LINK";

  if (razorpayClient?.isConfigured() && razorpayClient.getRazorpayXAccountNumber()) {
    try {
      if (!contactId) {
        const contactResp = await razorpayClient.createContact({
          name: cleanHolder || expert.fullName,
          email: expert.verifiedGoogleEmail || actor.email,
          referenceId: targetExpertId,
        });
        contactId = contactResp?.id || "";
      }
      if (contactId) {
        const faResp = await razorpayClient.createFundAccount({
          contactId,
          accountType: acctType === "UPI" ? "vpa" : "bank_account",
          vpaAddress: cleanVpa,
          bankAccount: {
            accountHolderName: cleanHolder,
            ifsc: cleanIfsc,
            accountNumber: cleanAcctNum,
          },
        });
        fundAccountId = faResp?.id || "";
        if (fundAccountId) {
          payoutKycStatus = "LINKED_READY";
        }
      }
    } catch (err) {
      throw new HttpAppError(502, `RazorpayX payout onboarding failed: ${err?.message || err}`);
    }
  }

  await db.runTransaction(async (tx) => {
    const publicUpdates = {
      payoutAccountType: acctType,
      payoutDestinationMasked: masked,
      payoutKycStatus,
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (contactId) publicUpdates.razorpayContactId = contactId;
    if (fundAccountId) publicUpdates.razorpayFundAccountId = fundAccountId;
    tx.update(expertRef, publicUpdates);

    tx.set(
      expertRef.collection("payout_secrets").doc("config"),
      {
        expertUid: expert.expertUid,
        payoutAccountType: acctType,
        payoutDestinationMasked: masked,
        vpaAddress: cleanVpa,
        bankAccountHolderName: cleanHolder,
        bankIfsc: cleanIfsc,
        bankAccountLast4: cleanAcctNum ? cleanAcctNum.slice(-4) : "",
        razorpayContactId: contactId,
        razorpayFundAccountId: fundAccountId,
        payoutKycStatus,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  });

  return {
    expertId: targetExpertId,
    payoutAccountType: acctType,
    payoutDestinationMasked: masked,
    payoutKycStatus,
    razorpayContactId: contactId || null,
    razorpayFundAccountId: fundAccountId || null,
  };
}

async function processPayoutTransaction({
  db,
  messaging = null,
  payoutRequestId,
  newStatus = "PROCESSING",
  payoutReference = "",
  adminNote = "",
  nowEpochMs = Date.now(),
  actor,
  razorpayClient = null,
  payoutMode = "manual_utr",
}) {
  if (!actor.isAdmin) {
    throw new HttpAppError(403, "Forbidden: admin privileges required to process payouts");
  }
  assertValidId(payoutRequestId, "payoutRequestId");

  const normalizedNext = String(newStatus || "PROCESSING").trim().toUpperCase();
  const cleanRef = String(payoutReference || "").trim().slice(0, 128);
  const cleanNote = String(adminNote || "").trim().slice(0, 500);

  if (normalizedNext === "PAID" && cleanRef.length < 3) {
    throw new HttpAppError(400, "payoutReference is required when marking payout PAID");
  }

  const payoutRef = db.collection("payout_requests").doc(payoutRequestId);
  const settingsRef = db.collection("platform_settings").doc("config");

  const txResult = await db.runTransaction(async (tx) => {
    const [payoutSnap, settingsSnap] = await Promise.all([tx.get(payoutRef), tx.get(settingsRef)]);
    if (!payoutSnap.exists) {
      throw new HttpAppError(404, "Payout request not found");
    }
    const payout = payoutSnap.data();
    const currentStatus = String(payout.status || "PENDING");

    if (currentStatus === "PAID" || currentStatus === "REJECTED") {
      throw new HttpAppError(409, `Duplicate payout blocked: payout is already ${currentStatus}`);
    }

    const allowedNext = ALLOWED_PAYOUT_TRANSITIONS[currentStatus];
    if (!allowedNext || !allowedNext.has(normalizedNext)) {
      throw new HttpAppError(409, `Invalid payout transition from ${currentStatus} to ${normalizedNext}`);
    }

    const bookingIds = Array.isArray(payout.eligibleBookingIds)
      ? [...new Set(payout.eligibleBookingIds.filter((x) => typeof x === "string" && VALID_ID_REGEX.test(x)))]
      : [];
    if (bookingIds.length === 0) {
      throw new HttpAppError(400, "Payout request has no valid eligibleBookingIds");
    }

    const settings = settingsSnap.exists ? settingsSnap.data() : {};
    const disputeWindowHours = Number(settings.disputeWindowHours ?? 24);
    const retryCount = Number(payout.retryCount || 0);
    const nextRetryCount = currentStatus === "FAILED" && normalizedNext === "PROCESSING" ? retryCount + 1 : retryCount;

    const expertPayoutsQuery = db.collection("payout_requests").where("expertUid", "==", payout.expertUid);
    const bookingRefs = bookingIds.map((bid) => db.collection("bookings").doc(bid));
    const expertRef = payout.expertId && VALID_ID_REGEX.test(payout.expertId)
      ? db.collection("experts").doc(payout.expertId)
      : db.collection("experts").doc(`exp_${payout.expertUid}`);
    const ledInitRef = db.collection("ledger_entries").doc(`led_payinit_${payoutRequestId}_${nextRetryCount}`);
    const ledFailRef = db.collection("ledger_entries").doc(`led_payfail_${payoutRequestId}_${retryCount}`);
    const ledPaidRef = db.collection("ledger_entries").doc(`led_payout_${payoutRequestId}`);

    const [expertPayoutsSnap, expertSnap, ledInitSnap, ledFailSnap, ledPaidSnap, ...bookingSnaps] = await Promise.all([
      tx.get(expertPayoutsQuery),
      tx.get(expertRef),
      tx.get(ledInitRef),
      tx.get(ledFailRef),
      tx.get(ledPaidRef),
      ...bookingRefs.map((ref) => tx.get(ref)),
    ]);

    const claimedElsewhere = new Set();
    for (const doc of expertPayoutsSnap.docs) {
      if (doc.id === payoutRequestId) continue;
      const other = doc.data();
      if (["PENDING", "APPROVED", "PROCESSING", "PAID"].includes(other.status)) {
        for (const bid of other.eligibleBookingIds || []) {
          claimedElsewhere.add(bid);
        }
      }
    }

    const shouldVerifyEligibility = ["APPROVED", "PROCESSING", "PAID"].includes(normalizedNext);
    let verifiedSum = 0;

    for (let i = 0; i < bookingIds.length; i++) {
      const bid = bookingIds[i];
      const snap = bookingSnaps[i];
      if (!snap.exists) {
        throw new HttpAppError(404, `Referenced booking ${bid} not found`);
      }
      if (claimedElsewhere.has(bid)) {
        throw new HttpAppError(409, `Booking ${bid} already claimed by another active or paid payout`);
      }
      const bk = snap.data();
      if (bk.expertUid !== payout.expertUid) {
        throw new HttpAppError(403, `Booking ${bid} does not belong to payout expert`);
      }
      if (shouldVerifyEligibility) {
        const allowCurrentProcessing = ["APPROVED", "PROCESSING"].includes(currentStatus);
        const check = evaluateBookingEligibilityForServer(bk, Number(nowEpochMs), disputeWindowHours, {
          allowCurrentProcessing,
        });
        if (!check.eligible) {
          throw new HttpAppError(400, `Booking ${bid} ineligible: ${check.reason}`);
        }
        verifiedSum += check.remainingPayablePaise;
      }
    }

    if (shouldVerifyEligibility && verifiedSum < Number(payout.amountPaise)) {
      throw new HttpAppError(
        400,
        `Verified eligible total (${verifiedSum}) < payout amount (${payout.amountPaise})`
      );
    }

    const expertData = expertSnap.exists ? expertSnap.data() : {};

    // In RazorpayX mode, verify fund account exists before putting payout in PROCESSING
    if (
      normalizedNext === "PROCESSING" &&
      payoutMode === "razorpayx" &&
      razorpayClient?.isConfigured() &&
      razorpayClient.getRazorpayXAccountNumber() &&
      !expertData.razorpayFundAccountId
    ) {
      throw new HttpAppError(
        409,
        "Expert has not completed RazorpayX payout account onboarding (missing razorpayFundAccountId)"
      );
    }

    const payoutUpdates = {
      status: normalizedNext,
      processedByUid: actor.uid,
      retryCount: nextRetryCount,
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (cleanRef) payoutUpdates.payoutReference = cleanRef;
    if (cleanNote) payoutUpdates.adminNote = cleanNote;
    if (normalizedNext === "FAILED") {
      payoutUpdates.failureReason = cleanNote || "Gateway payout transfer failed";
    }

    tx.update(payoutRef, payoutUpdates);

    const targetBookingElig =
      normalizedNext === "PROCESSING"
        ? "PROCESSING"
        : normalizedNext === "PAID"
          ? "PAID"
          : normalizedNext === "FAILED" || normalizedNext === "REJECTED"
            ? "ELIGIBLE"
            : null;

    if (targetBookingElig) {
      for (let i = 0; i < bookingIds.length; i++) {
        const bk = bookingSnaps[i].data();
        if (!["PAID", "REVERSED"].includes(bk.payoutEligibilityStatus)) {
          tx.update(bookingRefs[i], {
            payoutEligibilityStatus: targetBookingElig,
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
      }
    }

    if (normalizedNext === "PROCESSING" && !ledInitSnap.exists) {
      tx.set(ledInitRef, {
        entryType: "PAYOUT_INITIATED",
        direction: "DEBIT",
        entryStatus: "PENDING",
        payoutRequestId,
        customerUid: "",
        expertUid: payout.expertUid,
        amountPaise: Number(payout.amountPaise),
        currency: "INR",
        idempotencyKey: `led_payinit_${payoutRequestId}_${nextRetryCount}`,
        description: `Payout transfer initiated (${payout.payoutDestination || ""})`.slice(0, 300),
        createdAt: FieldValue.serverTimestamp(),
      });
    } else if (normalizedNext === "FAILED" && !ledFailSnap.exists) {
      tx.set(ledFailRef, {
        entryType: "PAYOUT_FAILED",
        direction: "CREDIT",
        entryStatus: "FAILED",
        payoutRequestId,
        customerUid: "",
        expertUid: payout.expertUid,
        amountPaise: Number(payout.amountPaise),
        currency: "INR",
        idempotencyKey: `led_payfail_${payoutRequestId}_${retryCount}`,
        description: "Payout failed; balance restored for safe retry",
        createdAt: FieldValue.serverTimestamp(),
      });
    } else if (normalizedNext === "PAID" && !ledPaidSnap.exists) {
      tx.set(ledPaidRef, {
        entryType: "PAYOUT_COMPLETED",
        direction: "DEBIT",
        entryStatus: "SETTLED",
        payoutRequestId,
        customerUid: "",
        expertUid: payout.expertUid,
        amountPaise: Number(payout.amountPaise),
        currency: "INR",
        idempotencyKey: `led_payout_${payoutRequestId}`,
        description: `Payout settled (Ref: ${cleanRef})`.slice(0, 300),
        createdAt: FieldValue.serverTimestamp(),
      });
    }

    tx.set(db.collection("audit_logs").doc(), {
      actorUid: actor.uid,
      actorEmail: actor.email || "",
      action: "PROCESS_PAYOUT",
      targetType: "payout_request",
      targetId: payoutRequestId,
      details: `${payout.expertName || payout.expertUid} -> ${normalizedNext} ref=${cleanRef}`.slice(0, 1000),
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      payoutId: payoutRequestId,
      payoutRequestId,
      status: normalizedNext,
      retryCount: nextRetryCount,
      verifiedEligibleSumPaise: verifiedSum,
      payout,
      expertData,
      bookingIds,
    };
  });

  // External RazorpayX dispatch after atomic lock transition to PROCESSING
  let providerTransferId = "";
  let resolvedPayoutReference = "";
  let finalStatus = txResult.status;

  if (
    txResult.status === "PROCESSING" &&
    payoutMode === "razorpayx" &&
    razorpayClient?.isConfigured() &&
    razorpayClient.getRazorpayXAccountNumber() &&
    txResult.expertData?.razorpayFundAccountId
  ) {
    try {
      const rzpPayout = await razorpayClient.createPayout({
        fundAccountId: txResult.expertData.razorpayFundAccountId,
        amountPaise: Number(txResult.payout.amountPaise),
        currency: "INR",
        mode: txResult.expertData.payoutAccountType === "UPI" ? "UPI" : "IMPS",
        referenceId: payoutRequestId,
        narration: `HRConnect ${payoutRequestId.slice(0, 12)}`,
        idempotencyKey: (
          txResult.payout.idempotencyKey
            ? `${txResult.payout.idempotencyKey}_${txResult.retryCount}`
            : `pout_${payoutRequestId}_${txResult.retryCount}`
        ).slice(0, 36),
        notes: {
          payoutRequestId,
          expertUid: txResult.payout.expertUid,
        },
      });
      providerTransferId = rzpPayout?.id || "";
      const immediatePaid = rzpPayout?.status === "processed";
      const ledPaidRef = db.collection("ledger_entries").doc(`led_payout_${payoutRequestId}`);
      const bookingRefs = txResult.bookingIds.map((bid) => db.collection("bookings").doc(bid));

      await db.runTransaction(async (tx) => {
        const [payoutSnap, ledPaidSnap, ...bookingSnaps] = await Promise.all([
          tx.get(payoutRef),
          tx.get(ledPaidRef),
          ...bookingRefs.map((ref) => tx.get(ref)),
        ]);
        if (!payoutSnap.exists) return;
        const cur = payoutSnap.data();
        const updates = {
          providerTransferId: providerTransferId || cur.providerTransferId || "",
          updatedAt: FieldValue.serverTimestamp(),
        };
        if (rzpPayout?.utr) {
          resolvedPayoutReference = String(rzpPayout.utr).slice(0, 128);
          updates.payoutReference = resolvedPayoutReference;
        }
        if (immediatePaid && cur.status !== "PAID") {
          finalStatus = "PAID";
          updates.status = "PAID";
          tx.update(payoutRef, updates);
          for (let i = 0; i < bookingRefs.length; i++) {
            const bk = bookingSnaps[i].exists ? bookingSnaps[i].data() : null;
            if (bk && bk.payoutEligibilityStatus !== "REVERSED") {
              tx.update(bookingRefs[i], {
                payoutEligibilityStatus: "PAID",
                updatedAt: FieldValue.serverTimestamp(),
              });
            }
          }
          if (!ledPaidSnap.exists) {
            tx.set(ledPaidRef, {
              entryType: "PAYOUT_COMPLETED",
              direction: "DEBIT",
              entryStatus: "SETTLED",
              payoutRequestId,
              customerUid: "",
              expertUid: txResult.payout.expertUid,
              amountPaise: Number(txResult.payout.amountPaise),
              currency: "INR",
              idempotencyKey: `led_payout_${payoutRequestId}`,
              description: `RazorpayX payout settled (${rzpPayout.utr || providerTransferId})`.slice(0, 300),
              createdAt: FieldValue.serverTimestamp(),
            });
          }
        } else {
          if (cur.status === "PAID") {
            finalStatus = "PAID";
          }
          tx.update(payoutRef, updates);
        }
      });
    } catch (err) {
      // Revert PROCESSING -> FAILED atomically only if still PROCESSING so concurrent webhook settlement is never overwritten
      await db.runTransaction(async (tx) => {
        const ledFailRef = db.collection("ledger_entries").doc(`led_payfail_${payoutRequestId}_${txResult.retryCount}`);
        const bookingRefs = txResult.bookingIds.map((bid) => db.collection("bookings").doc(bid));
        const [payoutSnap, ledFailSnap, ...bookingSnaps] = await Promise.all([
          tx.get(payoutRef),
          tx.get(ledFailRef),
          ...bookingRefs.map((ref) => tx.get(ref)),
        ]);
        if (!payoutSnap.exists || payoutSnap.data().status !== "PROCESSING") {
          return;
        }
        tx.update(payoutRef, {
          status: "FAILED",
          failureReason: `RazorpayX transfer failed: ${err?.message || err}`.slice(0, 300),
          updatedAt: FieldValue.serverTimestamp(),
        });
        for (let i = 0; i < bookingRefs.length; i++) {
          const bk = bookingSnaps[i].exists ? bookingSnaps[i].data() : null;
          if (bk && bk.payoutEligibilityStatus !== "REVERSED") {
            tx.update(bookingRefs[i], {
              payoutEligibilityStatus: "ELIGIBLE",
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        }
        if (!ledFailSnap.exists) {
          tx.set(ledFailRef, {
            entryType: "PAYOUT_FAILED",
            direction: "CREDIT",
            entryStatus: "FAILED",
            payoutRequestId,
            customerUid: "",
            expertUid: txResult.payout.expertUid,
            amountPaise: Number(txResult.payout.amountPaise),
            currency: "INR",
            idempotencyKey: `led_payfail_${payoutRequestId}_${txResult.retryCount}`,
            description: "RazorpayX transfer failed; balance restored for retry",
            createdAt: FieldValue.serverTimestamp(),
          });
        }
      });
      throw new HttpAppError(502, `RazorpayX payout transfer failed: ${err?.message || err}`);
    }
  }

  await dispatchNotificationsWithPush({
    db,
    messaging,
    items: [
      {
        recipientUid: txResult.payout.expertUid,
        title: `Payout ${finalStatus}`,
        message: `Your payout request status updated to ${finalStatus}.`,
        eventType: "PAYOUT_UPDATED",
        dedupKey: `payout_${payoutRequestId}_${finalStatus}_${txResult.retryCount}`,
      },
    ],
  });

  return {
    payoutId: payoutRequestId,
    payoutRequestId,
    status: finalStatus,
    retryCount: txResult.retryCount,
    verifiedEligibleSumPaise: txResult.verifiedEligibleSumPaise,
    providerTransferId: providerTransferId || undefined,
    payoutReference: resolvedPayoutReference || undefined,
  };
}

async function registerFcmToken({ db, actor, token, platform = "android", deviceId = "" }) {
  const cleanToken = String(token || "").trim();
  if (cleanToken.length < 20 || cleanToken.length > 512) {
    throw new HttpAppError(400, "Invalid FCM registration token");
  }
  const tokenHash = crypto.createHash("sha256").update(cleanToken).digest("hex").slice(0, 32);
  await db
    .collection("users")
    .doc(actor.uid)
    .collection("fcm_tokens")
    .doc(tokenHash)
    .set(
      {
        userId: actor.uid,
        token: cleanToken,
        platform: String(platform || "android").slice(0, 20),
        deviceId: sanitizeDocId(deviceId || tokenHash),
        enabled: true,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  return { registered: true, tokenId: tokenHash };
}

module.exports = {
  saveExpertPayoutOnboarding,
  processPayoutTransaction,
  registerFcmToken,
};
