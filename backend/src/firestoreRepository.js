const { FieldValue } = require("firebase-admin/firestore");
const {
  calculateFinancialSplit,
  computeProportionalRefundSplit,
  verifyRazorpayPaymentSignature,
} = require("./paymentService");
const { isPlaceholderValue } = require("./razorpayClient");
const {
  HttpAppError,
  assertValidId,
  sanitizeDocId,
  createBookingWithHoldTransaction,
  rescheduleBookingTransaction,
  cancelBookingTransaction,
  syncCalendarMetadata,
  syncMeetAttendanceSafe,
} = require("./bookingRepository");
const {
  saveExpertPayoutOnboarding,
  processPayoutTransaction,
  registerFcmToken,
} = require("./payoutRepository");
const { dispatchNotificationsWithPush } = require("./notificationService");

const VALID_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;

function writeCaptureLedgerEntriesInTx(
  tx,
  {
    bookingId,
    booking,
    gatewayFeePaise,
    taxDeductionPaise,
    capRef,
    capSnap,
    commRef,
    commSnap,
    earnRef,
    earnSnap,
    gwRef,
    gwSnap,
    taxRef,
    taxSnap,
    sourceLabel,
  }
) {
  const paymentDocId = `pay_${bookingId}`;
  if (!capSnap.exists) {
    tx.set(capRef, {
      entryType: "PAYMENT_CAPTURED",
      direction: "CREDIT",
      entryStatus: "SETTLED",
      bookingId,
      paymentId: paymentDocId,
      customerUid: booking.customerUid,
      expertUid: booking.expertUid,
      amountPaise: Number(booking.amountPaise),
      currency: "INR",
      idempotencyKey: `led_cap_${bookingId}`,
      description: `${sourceLabel} for ${booking.serviceTitle || bookingId}`.slice(0, 300),
      createdAt: FieldValue.serverTimestamp(),
    });
  }
  if (!commSnap.exists) {
    tx.set(commRef, {
      entryType: "PLATFORM_COMMISSION",
      direction: "CREDIT",
      entryStatus: "SETTLED",
      bookingId,
      paymentId: paymentDocId,
      customerUid: booking.customerUid,
      expertUid: booking.expertUid,
      amountPaise: Number(booking.commissionPaise),
      currency: "INR",
      idempotencyKey: `led_comm_${bookingId}`,
      description: `Platform commission (${Number(booking.commissionRateBps || 1500) / 100}%)`.slice(0, 300),
      createdAt: FieldValue.serverTimestamp(),
    });
  }
  if (!earnSnap.exists) {
    tx.set(earnRef, {
      entryType: "EXPERT_EARNING",
      direction: "CREDIT",
      entryStatus: "PENDING",
      bookingId,
      paymentId: paymentDocId,
      customerUid: booking.customerUid,
      expertUid: booking.expertUid,
      amountPaise: Number(booking.expertEarningPaise),
      currency: "INR",
      idempotencyKey: `led_earn_${bookingId}`,
      description: `Pending expert earning for ${booking.serviceTitle || bookingId}`.slice(0, 300),
      createdAt: FieldValue.serverTimestamp(),
    });
  }
  if (gatewayFeePaise > 0 && !gwSnap.exists) {
    tx.set(gwRef, {
      entryType: "GATEWAY_FEE",
      direction: "DEBIT",
      entryStatus: "SETTLED",
      bookingId,
      paymentId: paymentDocId,
      customerUid: booking.customerUid,
      expertUid: booking.expertUid,
      amountPaise: gatewayFeePaise,
      currency: "INR",
      idempotencyKey: `led_gw_${bookingId}`,
      description: "Payment gateway fee (platform side)",
      createdAt: FieldValue.serverTimestamp(),
    });
  }
  if (taxDeductionPaise > 0 && !taxSnap.exists) {
    tx.set(taxRef, {
      entryType: "TAX_DEDUCTION",
      direction: "DEBIT",
      entryStatus: "SETTLED",
      bookingId,
      paymentId: paymentDocId,
      customerUid: booking.customerUid,
      expertUid: booking.expertUid,
      amountPaise: taxDeductionPaise,
      currency: "INR",
      idempotencyKey: `led_tax_${bookingId}`,
      description: "TDS deduction withheld on expert fee",
      createdAt: FieldValue.serverTimestamp(),
    });
  }
}

async function createOrderTransaction({
  db,
  bookingId,
  actor,
  isTestMode,
  razorpayClient = null,
  enableRazorpayApi = true,
  requireLiveRazorpay = false,
}) {
  assertValidId(bookingId, "bookingId");

  const bookingRef = db.collection("bookings").doc(bookingId);
  const paymentRef = db.collection("payments").doc(`pay_${bookingId}`);
  const settingsRef = db.collection("platform_settings").doc("config");

  const preflight = await db.runTransaction(async (tx) => {
    const [bookingSnap, paymentSnap, settingsSnap] = await Promise.all([
      tx.get(bookingRef),
      tx.get(paymentRef),
      tx.get(settingsRef),
    ]);

    if (!bookingSnap.exists) {
      throw new HttpAppError(404, "Booking not found");
    }
    const booking = bookingSnap.data();

    if (booking.customerUid !== actor.uid) {
      throw new HttpAppError(403, "Forbidden: only the booking customer can create a payment order");
    }
    if (booking.bookingStatus !== "PENDING_PAYMENT") {
      throw new HttpAppError(409, `Booking status ${booking.bookingStatus} is not awaiting payment`);
    }
    if (booking.holdExpiresAtEpochMs && Date.now() > Number(booking.holdExpiresAtEpochMs)) {
      throw new HttpAppError(409, "Payment hold has expired");
    }

    if (paymentSnap.exists) {
      const existingPay = paymentSnap.data();
      if (existingPay.paymentStatus === "SUCCESS") {
        throw new HttpAppError(409, "Payment already captured for this booking");
      }
      if (existingPay.paymentStatus === "CREATED" && existingPay.razorpayOrderId) {
        return {
          replayResponse: {
            orderId: existingPay.razorpayOrderId,
            amountPaise: Number(booking.amountPaise),
            currency: "INR",
            commissionRateBps: Number(booking.commissionRateBps),
            commissionPaise: Number(booking.commissionPaise),
            expertEarningPaise: Number(booking.expertEarningPaise),
            gatewayFeePaise: Number(booking.gatewayFeePaise || 0),
            taxDeductionPaise: Number(booking.taxDeductionPaise || 0),
            testMode: Boolean(existingPay.testMode ?? isTestMode),
            idempotentReplay: true,
          },
        };
      }
    }

    const serviceRef = db.collection("services").doc(assertValidId(booking.serviceId, "serviceId"));
    const serviceSnap = await tx.get(serviceRef);
    const settings = settingsSnap.exists ? settingsSnap.data() : {};

    let authoritativeAmount = Number(booking.amountPaise);
    if (serviceSnap.exists) {
      const svc = serviceSnap.data();
      if (svc.active === false) {
        throw new HttpAppError(409, "Consultation service is no longer active");
      }
      if (Number(svc.pricePaise) !== authoritativeAmount) {
        throw new HttpAppError(409, "Booking amount does not match authoritative service price");
      }
      authoritativeAmount = Number(svc.pricePaise);
    }

    const expectedRateBps =
      booking.referralType === "EXPERT_REFERRAL"
        ? Number(settings.referralCommissionBps ?? 1000)
        : Number(settings.platformCommissionBps ?? 1500);
    const commissionRateBps = Number(booking.commissionRateBps);
    if (commissionRateBps !== expectedRateBps) {
      throw new HttpAppError(409, "Booking commission rate does not match authoritative platform settings");
    }
    const gatewayFeeBps = Number(settings.gatewayFeeBps ?? 200);
    const tdsBps = Number(settings.tdsBps ?? 0);

    const split = calculateFinancialSplit({
      amountPaise: authoritativeAmount,
      commissionRateBps,
      gatewayFeeBps,
      tdsBps,
    });
    if (
      Number(booking.commissionPaise) !== split.commissionPaise ||
      Number(booking.expertEarningPaise) !== split.expertEarningPaise
    ) {
      throw new HttpAppError(409, "Booking financial split does not match authoritative calculation");
    }

    return { replayResponse: null, booking, split };
  });

  if (preflight.replayResponse) {
    return preflight.replayResponse;
  }

  let orderId;
  const shouldUseLiveRazorpay = enableRazorpayApi && razorpayClient?.isConfigured();
  if (shouldUseLiveRazorpay) {
    const rzpOrder = await razorpayClient.createOrder({
      amountPaise: preflight.split.amountPaise,
      currency: "INR",
      receipt: `rcpt_${bookingId.slice(0, 32)}`,
      notes: {
        bookingId,
        customerUid: preflight.booking.customerUid,
        expertUid: preflight.booking.expertUid,
        referralType: preflight.booking.referralType || "PLATFORM",
      },
    });
    if (!rzpOrder?.id || Number(rzpOrder.amount) !== preflight.split.amountPaise || rzpOrder.currency !== "INR") {
      throw new HttpAppError(502, "Razorpay order creation returned invalid order metadata");
    }
    orderId = rzpOrder.id;
  } else {
    if (requireLiveRazorpay) {
      throw new HttpAppError(503, "Live Razorpay credentials are required in production to create orders");
    }
    orderId = `order_${bookingId.slice(0, 12)}_${Date.now()}`;
  }

  return db.runTransaction(async (tx) => {
    const paymentSnap = await tx.get(paymentRef);
    if (paymentSnap.exists) {
      const existingPay = paymentSnap.data();
      if (existingPay.paymentStatus === "SUCCESS") {
        throw new HttpAppError(409, "Payment already captured for this booking");
      }
      if (existingPay.paymentStatus === "CREATED" && existingPay.razorpayOrderId) {
        return {
          orderId: existingPay.razorpayOrderId,
          amountPaise: preflight.split.amountPaise,
          currency: "INR",
          commissionRateBps: preflight.split.commissionRateBps,
          commissionPaise: preflight.split.commissionPaise,
          expertEarningPaise: preflight.split.expertEarningPaise,
          gatewayFeePaise: preflight.split.gatewayFeePaise,
          taxDeductionPaise: preflight.split.taxDeductionPaise,
          testMode: Boolean(existingPay.testMode ?? isTestMode),
          idempotentReplay: true,
        };
      }
    }

    tx.set(
      paymentRef,
      {
        bookingId,
        customerUid: preflight.booking.customerUid,
        expertUid: preflight.booking.expertUid,
        razorpayOrderId: orderId,
        paymentMethod: "PENDING_CHECKOUT",
        currency: "INR",
        amountPaise: preflight.split.amountPaise,
        commissionPaise: preflight.split.commissionPaise,
        gatewayFeePaise: preflight.split.gatewayFeePaise,
        taxDeductionPaise: preflight.split.taxDeductionPaise,
        expertEarningPaise: preflight.split.expertEarningPaise,
        refundedAmountPaise: 0,
        paymentStatus: "CREATED",
        providerSettlementStatus: "PENDING_CAPTURE",
        serverVerified: false,
        idempotencyKey: `idem_${bookingId}`,
        testMode: Boolean(isTestMode),
        createdAt: paymentSnap.exists && paymentSnap.data().createdAt ? paymentSnap.data().createdAt : FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return {
      orderId,
      amountPaise: preflight.split.amountPaise,
      currency: "INR",
      commissionRateBps: preflight.split.commissionRateBps,
      commissionPaise: preflight.split.commissionPaise,
      expertEarningPaise: preflight.split.expertEarningPaise,
      gatewayFeePaise: preflight.split.gatewayFeePaise,
      taxDeductionPaise: preflight.split.taxDeductionPaise,
      testMode: Boolean(isTestMode),
    };
  });
}

async function verifyPaymentTransaction({
  db,
  messaging = null,
  bookingId,
  razorpayOrderId,
  razorpayPaymentId,
  razorpaySignature,
  paymentMethod = "RAZORPAY",
  actor,
  keySecret,
  isTestMode,
  razorpayClient = null,
  enableRazorpayApi = true,
  requireLiveRazorpay = false,
  payoutMode = "manual_utr",
}) {
  assertValidId(bookingId, "bookingId");
  if (typeof razorpayOrderId !== "string" || razorpayOrderId.trim().length < 5) {
    throw new HttpAppError(400, "Valid razorpayOrderId is required");
  }
  if (typeof razorpayPaymentId !== "string" || razorpayPaymentId.trim().length < 4) {
    throw new HttpAppError(400, "Valid razorpayPaymentId is required");
  }
  if (typeof razorpaySignature !== "string" || razorpaySignature.trim().length < 8) {
    throw new HttpAppError(400, "Valid razorpaySignature is required");
  }
  const orderIdClean = razorpayOrderId.trim();
  const paymentIdClean = razorpayPaymentId.trim();
  const signatureClean = razorpaySignature.trim();

  const isDevSandboxCallback =
    !requireLiveRazorpay &&
    Boolean(isTestMode) &&
    !razorpayClient?.isConfigured() &&
    isPlaceholderValue(keySecret) &&
    signatureClean === "server_sandbox_callback" &&
    paymentIdClean.startsWith("pay_srv_test_");

  if (!isDevSandboxCallback) {
    if (!keySecret || isPlaceholderValue(keySecret)) {
      throw new HttpAppError(503, "Razorpay key secret is not configured on backend");
    }
    if (
      !verifyRazorpayPaymentSignature({
        orderId: orderIdClean,
        paymentId: paymentIdClean,
        signature: signatureClean,
        keySecret,
      })
    ) {
      throw new HttpAppError(400, "Razorpay signature verification failed");
    }
  }

  const bookingRef = db.collection("bookings").doc(bookingId);
  const paymentRef = db.collection("payments").doc(`pay_${bookingId}`);

  // Preflight check before external Razorpay GET/capture
  const preflightSnap = await db.runTransaction(async (tx) => {
    const [bSnap, pSnap] = await Promise.all([tx.get(bookingRef), tx.get(paymentRef)]);
    if (!bSnap.exists) throw new HttpAppError(404, "Booking not found");
    const booking = bSnap.data();
    if (!actor.isAdmin && booking.customerUid !== actor.uid) {
      throw new HttpAppError(403, "Forbidden: only the booking customer can verify payment");
    }
    const existingPay = pSnap.exists ? pSnap.data() : null;
    if (existingPay && existingPay.razorpayOrderId && existingPay.razorpayOrderId !== orderIdClean) {
      throw new HttpAppError(400, "Order ID mismatch for booking");
    }
    if (existingPay && existingPay.paymentStatus === "SUCCESS") {
      if (existingPay.razorpayPaymentId === paymentIdClean) {
        return {
          alreadyVerified: true,
          response: {
            verified: true,
            serverVerified: true,
            alreadyVerified: true,
            razorpayPaymentId: paymentIdClean,
            paymentId: paymentIdClean,
            paymentStatus: "SUCCESS",
            gatewayFeePaise: Number(booking.gatewayFeePaise || 0),
            taxDeductionPaise: Number(booking.taxDeductionPaise || 0),
            settlementStatus: existingPay.providerSettlementStatus || booking.providerSettlementStatus || "CAPTURED_UNSETTLED",
          },
        };
      }
      throw new HttpAppError(409, "Booking payment has already been verified with a different payment ID");
    }
    if (booking.bookingStatus !== "PENDING_PAYMENT") {
      throw new HttpAppError(409, `Booking in state ${booking.bookingStatus} cannot be captured`);
    }
    if (booking.holdExpiresAtEpochMs && Date.now() > Number(booking.holdExpiresAtEpochMs)) {
      throw new HttpAppError(409, "Payment hold has expired");
    }
    return { alreadyVerified: false, booking, existingPay };
  });

  if (preflightSnap.alreadyVerified) {
    return preflightSnap.response;
  }

  let resolvedMethod = String(paymentMethod || "RAZORPAY").slice(0, 40);
  let capturedFeePaise = null;
  let capturedGatewayTaxPaise = 0;
  const shouldUseLiveRazorpay = enableRazorpayApi && razorpayClient?.isConfigured();
  if (shouldUseLiveRazorpay) {
    let rzpPay = await razorpayClient.fetchPayment(paymentIdClean);
    if (rzpPay.order_id && rzpPay.order_id !== orderIdClean) {
      throw new HttpAppError(409, "Razorpay payment order_id mismatch");
    }
    if (rzpPay.currency !== "INR" || Number(rzpPay.amount) !== Number(preflightSnap.booking.amountPaise)) {
      throw new HttpAppError(409, "Razorpay payment amount or currency mismatch");
    }
    if (rzpPay.status === "authorized") {
      rzpPay = await razorpayClient.capturePayment(
        paymentIdClean,
        Number(preflightSnap.booking.amountPaise),
        "INR"
      );
    }
    if (rzpPay.status !== "captured") {
      throw new HttpAppError(409, `Razorpay payment is in state ${rzpPay.status}, expected captured`);
    }
    if (rzpPay.method) {
      resolvedMethod = String(rzpPay.method).toUpperCase().slice(0, 40);
    }
    if (Number.isInteger(rzpPay.fee) && rzpPay.fee >= 0) {
      capturedFeePaise = Number(rzpPay.fee);
    }
    if (Number.isInteger(rzpPay.tax) && rzpPay.tax >= 0) {
      capturedGatewayTaxPaise = Number(rzpPay.tax);
    }
  } else if (requireLiveRazorpay) {
    throw new HttpAppError(503, "Live Razorpay credentials are required in production to verify payments");
  }

  const capRef = db.collection("ledger_entries").doc(`led_cap_${bookingId}`);
  const commRef = db.collection("ledger_entries").doc(`led_comm_${bookingId}`);
  const earnRef = db.collection("ledger_entries").doc(`led_earn_${bookingId}`);
  const gwRef = db.collection("ledger_entries").doc(`led_gw_${bookingId}`);
  const taxRef = db.collection("ledger_entries").doc(`led_tax_${bookingId}`);

  const txResult = await db.runTransaction(async (tx) => {
    const [bookingSnap, paymentSnap, capSnap, commSnap, earnSnap, gwSnap, taxSnap] = await Promise.all([
      tx.get(bookingRef),
      tx.get(paymentRef),
      tx.get(capRef),
      tx.get(commRef),
      tx.get(earnRef),
      tx.get(gwRef),
      tx.get(taxRef),
    ]);

    if (!bookingSnap.exists) {
      throw new HttpAppError(404, "Booking not found");
    }
    const booking = bookingSnap.data();

    const existingPay = paymentSnap.exists ? paymentSnap.data() : null;
    if (existingPay && existingPay.paymentStatus === "SUCCESS") {
      if (existingPay.razorpayPaymentId === paymentIdClean) {
        return {
          alreadyVerified: true,
          booking,
          response: {
            verified: true,
            serverVerified: true,
            alreadyVerified: true,
            razorpayPaymentId: paymentIdClean,
            paymentId: paymentIdClean,
            paymentStatus: "SUCCESS",
            gatewayFeePaise: Number(booking.gatewayFeePaise || 0),
            gatewayTaxPaise: Number(existingPay.gatewayTaxPaise || 0),
            taxDeductionPaise: Number(booking.taxDeductionPaise || 0),
            settlementStatus: existingPay.providerSettlementStatus || booking.providerSettlementStatus || "CAPTURED_UNSETTLED",
          },
        };
      }
      throw new HttpAppError(409, "Booking payment has already been verified with a different payment ID");
    }

    if (booking.bookingStatus !== "PENDING_PAYMENT") {
      throw new HttpAppError(409, `Booking in state ${booking.bookingStatus} cannot be captured`);
    }

    let lockSnap = null;
    let lockRef = null;
    if (booking.slotKey && VALID_ID_REGEX.test(booking.slotKey)) {
      lockRef = db.collection("slot_locks").doc(booking.slotKey);
      lockSnap = await tx.get(lockRef);
    }

    const settlementStatus = isTestMode || isDevSandboxCallback ? "SANDBOX_SETTLED" : "CAPTURED_UNSETTLED";
    const gatewayFeePaise = capturedFeePaise !== null ? capturedFeePaise : Number(booking.gatewayFeePaise || 0);
    const gatewayTaxPaise = capturedGatewayTaxPaise;
    const taxDeductionPaise = Number(booking.taxDeductionPaise || 0);
    const serverVerified = !isDevSandboxCallback;

    tx.update(bookingRef, {
      bookingStatus: "CONFIRMED",
      paymentStatus: "SUCCESS",
      gatewayFeePaise,
      gatewayTaxPaise,
      providerSettlementStatus: settlementStatus,
      payoutEligibilityStatus: "NOT_ELIGIBLE",
      calendarSyncStatus: "PENDING_BACKEND_SYNC",
      updatedAt: FieldValue.serverTimestamp(),
    });

    if (lockSnap && lockSnap.exists) {
      tx.update(lockRef, {
        bookingStatus: "CONFIRMED",
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    tx.set(
      paymentRef,
      {
        bookingId,
        customerUid: booking.customerUid,
        expertUid: booking.expertUid,
        razorpayOrderId: orderIdClean,
        razorpayPaymentId: paymentIdClean,
        razorpaySignature: signatureClean,
        paymentMethod: resolvedMethod,
        currency: "INR",
        amountPaise: Number(booking.amountPaise),
        commissionPaise: Number(booking.commissionPaise),
        gatewayFeePaise,
        gatewayTaxPaise,
        taxDeductionPaise,
        expertEarningPaise: Number(booking.expertEarningPaise),
        refundedAmountPaise: Number(booking.refundedAmountPaise || 0),
        paymentStatus: "SUCCESS",
        providerSettlementStatus: settlementStatus,
        serverVerified,
        idempotencyKey: `idem_${bookingId}`,
        testMode: Boolean(isTestMode || isDevSandboxCallback),
        createdAt: existingPay?.createdAt || FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    writeCaptureLedgerEntriesInTx(tx, {
      bookingId,
      booking,
      gatewayFeePaise,
      taxDeductionPaise,
      capRef,
      capSnap,
      commRef,
      commSnap,
      earnRef,
      earnSnap,
      gwRef,
      gwSnap,
      taxRef,
      taxSnap,
      sourceLabel: isDevSandboxCallback
        ? "Dev backend sandbox payment captured"
        : "Server-verified payment captured",
    });

    return {
      alreadyVerified: false,
      booking,
      response: {
        verified: true,
        serverVerified,
        razorpayPaymentId: paymentIdClean,
        paymentId: paymentIdClean,
        paymentStatus: "SUCCESS",
        gatewayFeePaise,
        gatewayTaxPaise,
        taxDeductionPaise,
        settlementStatus,
      },
    };
  });

  if (!txResult.alreadyVerified) {
    // Optional Razorpay Route transfer if configured & expert has a linked account
    if (shouldUseLiveRazorpay && payoutMode === "route" && txResult.booking.expertId) {
      try {
        const expSnap = await db.collection("experts").doc(txResult.booking.expertId).get();
        const linkedId = expSnap.data()?.razorpayLinkedAccountId;
        if (linkedId) {
          const transfer = await razorpayClient.createRouteTransfer(paymentIdClean, {
            linkedAccountId: linkedId,
            amountPaise: Number(txResult.booking.expertEarningPaise),
            currency: "INR",
            notes: { bookingId },
            onHold: true,
          });
          const transferId = transfer?.items?.[0]?.id || "";
          if (transferId) {
            await paymentRef.update({
              razorpayTransferId: transferId,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        }
      } catch {
        // Non-fatal: payout can still be manually settled after dispute window
      }
    }

    await dispatchNotificationsWithPush({
      db,
      messaging,
      items: [
        {
          recipientUid: txResult.booking.customerUid,
          title: "Consultation Confirmed",
          message: `${txResult.booking.serviceTitle} with ${txResult.booking.expertName} is confirmed.`,
          eventType: "BOOKING_CONFIRMED",
          relatedBookingId: bookingId,
          dedupKey: `paid_cust_${bookingId}`,
        },
        {
          recipientUid: txResult.booking.expertUid,
          title: "New Paid Booking",
          message: `${txResult.booking.customerName} booked ${txResult.booking.serviceTitle}.`,
          eventType: "NEW_BOOKING",
          relatedBookingId: bookingId,
          dedupKey: `paid_exp_${bookingId}`,
        },
      ],
    });
  }

  return txResult.response;
}

async function resolveWebhookTarget(db, { paymentEntity, refundEntity, payoutEntity, transferEntity }) {
  const fromNotes =
    paymentEntity?.notes?.bookingId ||
    refundEntity?.notes?.bookingId ||
    transferEntity?.notes?.bookingId;
  let bookingId = typeof fromNotes === "string" && VALID_ID_REGEX.test(fromNotes) ? fromNotes : null;

  if (!bookingId && paymentEntity?.order_id) {
    const paySnap = await db
      .collection("payments")
      .where("razorpayOrderId", "==", String(paymentEntity.order_id))
      .limit(1)
      .get();
    if (!paySnap.empty) bookingId = paySnap.docs[0].data().bookingId;
  }
  if (!bookingId && refundEntity?.payment_id) {
    const paySnap = await db
      .collection("payments")
      .where("razorpayPaymentId", "==", String(refundEntity.payment_id))
      .limit(1)
      .get();
    if (!paySnap.empty) bookingId = paySnap.docs[0].data().bookingId;
  }

  let payoutRequestId =
    payoutEntity?.notes?.payoutRequestId ||
    (typeof payoutEntity?.reference_id === "string" && VALID_ID_REGEX.test(payoutEntity.reference_id)
      ? payoutEntity.reference_id
      : null);

  if (!payoutRequestId && payoutEntity?.id) {
    const prSnap = await db
      .collection("payout_requests")
      .where("providerTransferId", "==", String(payoutEntity.id))
      .limit(1)
      .get();
    if (!prSnap.empty) payoutRequestId = prSnap.docs[0].id;
  }

  return { bookingId, payoutRequestId };
}

async function processWebhookTransaction({
  db,
  messaging = null,
  eventId,
  eventType,
  payload,
  razorpayClient = null,
  enableRazorpayApi = false,
}) {
  if (!eventId || !eventType) {
    return { status: "REJECTED", reason: "Missing eventId or eventType" };
  }

  const safeEventId = sanitizeDocId(eventId);
  const webhookRef = db.collection("webhook_events").doc(safeEventId);
  const paymentEntity = payload?.payload?.payment?.entity || null;
  const refundEntity = payload?.payload?.refund?.entity || null;
  const payoutEntity = payload?.payload?.payout?.entity || null;
  const transferEntity = payload?.payload?.transfer?.entity || null;

  // Fast pre-check so duplicate deliveries do not re-trigger external capture calls
  const existingWebhookPre = await webhookRef.get();
  if (existingWebhookPre.exists) {
    return { status: "DUPLICATE_IGNORED", eventId: safeEventId };
  }

  const { bookingId: resolvedBookingId, payoutRequestId: resolvedPayoutId } = await resolveWebhookTarget(db, {
    paymentEntity,
    refundEntity,
    payoutEntity,
    transferEntity,
  });

  // For payment.authorized on an unexpired/valid PENDING_PAYMENT hold, attempt live server-side capture
  // before entering the atomic Firestore transaction.
  let capturedAuthEntity = null;
  if (
    eventType === "payment.authorized" &&
    paymentEntity &&
    resolvedBookingId &&
    enableRazorpayApi &&
    razorpayClient?.isConfigured() &&
    paymentEntity.id
  ) {
    const preBookingSnap = await db.collection("bookings").doc(resolvedBookingId).get();
    if (preBookingSnap.exists) {
      const preBk = preBookingSnap.data();
      let preSlotValid = true;
      if (preBk.slotKey && VALID_ID_REGEX.test(preBk.slotKey)) {
        const lockSnapPre = await db.collection("slot_locks").doc(preBk.slotKey).get();
        if (lockSnapPre.exists && lockSnapPre.data().bookingId !== resolvedBookingId) {
          preSlotValid = false;
        }
      }
      if (
        preBk.bookingStatus === "PENDING_PAYMENT" &&
        preBk.paymentStatus !== "SUCCESS" &&
        preSlotValid &&
        paymentEntity.currency === "INR" &&
        Number(paymentEntity.amount) === Number(preBk.amountPaise)
      ) {
        try {
          const capResp = await razorpayClient.capturePayment(
            String(paymentEntity.id),
            Number(preBk.amountPaise),
            "INR"
          );
          if (capResp?.status === "captured") {
            capturedAuthEntity = capResp;
          }
        } catch {
          // Capture failed or already captured; fall through to transactional inspection
        }
      }
    }
  }

  const postCommitNotifications = [];

  const txResult = await db.runTransaction(async (tx) => {
    const webhookSnap = await tx.get(webhookRef);
    if (webhookSnap.exists) {
      return { status: "DUPLICATE_IGNORED", eventId: safeEventId };
    }

    const effectivePaymentEntity = capturedAuthEntity
      ? { ...paymentEntity, ...capturedAuthEntity }
      : paymentEntity;

    if (
      (eventType === "payment.captured" ||
        (eventType === "payment.authorized" && effectivePaymentEntity)) &&
      effectivePaymentEntity
    ) {
      if (!resolvedBookingId) {
        return { status: "IGNORED", reason: "Unknown booking" };
      }
      const bookingRef = db.collection("bookings").doc(resolvedBookingId);
      const paymentRef = db.collection("payments").doc(`pay_${resolvedBookingId}`);
      const capRef = db.collection("ledger_entries").doc(`led_cap_${resolvedBookingId}`);
      const commRef = db.collection("ledger_entries").doc(`led_comm_${resolvedBookingId}`);
      const earnRef = db.collection("ledger_entries").doc(`led_earn_${resolvedBookingId}`);
      const gwRef = db.collection("ledger_entries").doc(`led_gw_${resolvedBookingId}`);
      const taxRef = db.collection("ledger_entries").doc(`led_tax_${resolvedBookingId}`);

      const [bookingSnap, paymentSnap, capSnap, commSnap, earnSnap, gwSnap, taxSnap] = await Promise.all([
        tx.get(bookingRef),
        tx.get(paymentRef),
        tx.get(capRef),
        tx.get(commRef),
        tx.get(earnRef),
        tx.get(gwRef),
        tx.get(taxRef),
      ]);

      if (!bookingSnap.exists) {
        return { status: "IGNORED", reason: "Unknown booking" };
      }
      const booking = bookingSnap.data();

      if (effectivePaymentEntity.currency !== "INR" || Number(effectivePaymentEntity.amount) !== Number(booking.amountPaise)) {
        return { status: "TAMPER_REJECTED", reason: "Amount or currency mismatch" };
      }

      let lockSnap = null;
      let lockRef = null;
      if (booking.slotKey && VALID_ID_REGEX.test(booking.slotKey)) {
        lockRef = db.collection("slot_locks").doc(booking.slotKey);
        lockSnap = await tx.get(lockRef);
      }

      const gatewayFeePaise =
        Number.isInteger(effectivePaymentEntity.fee) && effectivePaymentEntity.fee >= 0
          ? Number(effectivePaymentEntity.fee)
          : Number(booking.gatewayFeePaise || 0);
      const gatewayTaxPaise =
        Number.isInteger(effectivePaymentEntity.tax) && effectivePaymentEntity.tax >= 0
          ? Number(effectivePaymentEntity.tax)
          : Number(booking.gatewayTaxPaise || 0);
      const taxDeductionPaise = Number(booking.taxDeductionPaise || 0);
      const paymentId = effectivePaymentEntity.id || paymentSnap.data()?.razorpayPaymentId || `wh_${safeEventId}`;
      const existingPay = paymentSnap.exists ? paymentSnap.data() : null;
      const settlementStatus = existingPay?.testMode ? "SANDBOX_SETTLED" : "CAPTURED_UNSETTLED";

      const isAuthorizedOnly =
        eventType === "payment.authorized" && effectivePaymentEntity.status !== "captured";

      if (isAuthorizedOnly) {
        if (booking.paymentStatus !== "SUCCESS") {
          if (booking.bookingStatus === "PENDING_PAYMENT") {
            tx.update(bookingRef, {
              paymentStatus: "PENDING",
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          tx.set(
            paymentRef,
            {
              bookingId: resolvedBookingId,
              customerUid: booking.customerUid,
              expertUid: booking.expertUid,
              razorpayOrderId: effectivePaymentEntity.order_id || existingPay?.razorpayOrderId || `order_${resolvedBookingId}`,
              razorpayPaymentId: String(paymentId),
              paymentMethod: String(effectivePaymentEntity.method || existingPay?.paymentMethod || "RAZORPAY").slice(0, 40),
              currency: "INR",
              amountPaise: Number(booking.amountPaise),
              commissionPaise: Number(booking.commissionPaise),
              gatewayFeePaise,
              gatewayTaxPaise,
              taxDeductionPaise,
              expertEarningPaise: Number(booking.expertEarningPaise),
              refundedAmountPaise: Number(booking.refundedAmountPaise || 0),
              paymentStatus: "PENDING",
              providerSettlementStatus: "PENDING_CAPTURE",
              serverVerified: Boolean(existingPay?.serverVerified),
              webhookEventId: safeEventId,
              idempotencyKey: `idem_${resolvedBookingId}`,
              testMode: Boolean(existingPay?.testMode ?? false),
              createdAt: existingPay?.createdAt || FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true }
          );
        }
        tx.set(webhookRef, {
          eventId: safeEventId,
          eventType,
          bookingId: resolvedBookingId,
          status: "PROCESSED",
          processedAt: FieldValue.serverTimestamp(),
        });
        return { status: "PROCESSED", bookingId: resolvedBookingId, eventId: safeEventId };
      }

      const slotHeldByThisBooking =
        !lockSnap || !lockSnap.exists || lockSnap.data().bookingId === resolvedBookingId;
      const canConfirmBooking = booking.bookingStatus === "PENDING_PAYMENT" && slotHeldByThisBooking;
      let isLateConflict = false;

      if (booking.paymentStatus !== "SUCCESS") {
        if (canConfirmBooking) {
          tx.update(bookingRef, {
            paymentStatus: "SUCCESS",
            bookingStatus: "CONFIRMED",
            gatewayFeePaise,
            gatewayTaxPaise,
            providerSettlementStatus: settlementStatus,
            payoutEligibilityStatus: "NOT_ELIGIBLE",
            calendarSyncStatus: booking.calendarSyncStatus || "PENDING_BACKEND_SYNC",
            updatedAt: FieldValue.serverTimestamp(),
          });
          if (lockSnap && lockSnap.exists) {
            tx.update(lockRef, {
              bookingStatus: "CONFIRMED",
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          postCommitNotifications.push(
            {
              recipientUid: booking.customerUid,
              title: "Consultation Confirmed",
              message: `${booking.serviceTitle} with ${booking.expertName} is confirmed.`,
              eventType: "BOOKING_CONFIRMED",
              relatedBookingId: resolvedBookingId,
              dedupKey: `paid_cust_${resolvedBookingId}`,
            },
            {
              recipientUid: booking.expertUid,
              title: "New Paid Booking",
              message: `${booking.customerName} booked ${booking.serviceTitle}.`,
              eventType: "NEW_BOOKING",
              relatedBookingId: resolvedBookingId,
              dedupKey: `paid_exp_${resolvedBookingId}`,
            }
          );
        } else {
          // Do NOT revive CANCELLED/REFUNDED bookings or overwrite another customer's slot_lock!
          isLateConflict = true;
          tx.update(bookingRef, {
            paymentStatus: "SUCCESS",
            gatewayFeePaise,
            gatewayTaxPaise,
            providerSettlementStatus: settlementStatus,
            payoutEligibilityStatus: "ON_HOLD",
            lateCaptureConflict: true,
            lateCapturePaymentId: String(paymentId),
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(db.collection("audit_logs").doc(), {
            actorUid: "razorpay_webhook",
            actorEmail: "webhook@razorpay",
            action: "LATE_CAPTURE_CONFLICT",
            targetType: "booking",
            targetId: resolvedBookingId,
            details: `Captured payment ${paymentId} on bookingStatus=${booking.bookingStatus} slotHeld=${slotHeldByThisBooking}; refund required without reviving cancelled/reclaimed slot`.slice(0, 1000),
            createdAt: FieldValue.serverTimestamp(),
          });
          postCommitNotifications.push({
            recipientUid: booking.customerUid,
            title: "Payment Received After Hold Expiry",
            message: `Your payment for ${booking.serviceTitle || resolvedBookingId} arrived after cancellation/hold expiry; refund review has been initiated.`,
            eventType: "REFUND_INITIATED",
            relatedBookingId: resolvedBookingId,
            dedupKey: `late_cap_${resolvedBookingId}`,
          });
        }
      }

      const paymentDocUpdates = {
        bookingId: resolvedBookingId,
        customerUid: booking.customerUid,
        expertUid: booking.expertUid,
        razorpayOrderId: effectivePaymentEntity.order_id || existingPay?.razorpayOrderId || `order_${resolvedBookingId}`,
        razorpayPaymentId: String(paymentId),
        paymentMethod: String(effectivePaymentEntity.method || existingPay?.paymentMethod || "RAZORPAY").slice(0, 40),
        currency: "INR",
        amountPaise: Number(booking.amountPaise),
        commissionPaise: Number(booking.commissionPaise),
        gatewayFeePaise,
        gatewayTaxPaise,
        taxDeductionPaise,
        expertEarningPaise: Number(booking.expertEarningPaise),
        refundedAmountPaise: Number(booking.refundedAmountPaise || 0),
        paymentStatus: "SUCCESS",
        providerSettlementStatus: settlementStatus,
        serverVerified: true,
        webhookEventId: safeEventId,
        idempotencyKey: `idem_${resolvedBookingId}`,
        testMode: Boolean(existingPay?.testMode ?? false),
        createdAt: existingPay?.createdAt || FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      };
      if (isLateConflict) {
        paymentDocUpdates.lateCaptureConflict = true;
      }
      tx.set(paymentRef, paymentDocUpdates, { merge: true });

      writeCaptureLedgerEntriesInTx(tx, {
        bookingId: resolvedBookingId,
        booking,
        gatewayFeePaise,
        taxDeductionPaise,
        capRef,
        capSnap,
        commRef,
        commSnap,
        earnRef,
        earnSnap,
        gwRef,
        gwSnap,
        taxRef,
        taxSnap,
        sourceLabel: isLateConflict
          ? "Webhook late payment captured (conflict flagged for refund)"
          : "Webhook payment captured",
      });

      const finalStatus = isLateConflict ? "LATE_CAPTURE_CONFLICT" : "PROCESSED";
      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        bookingId: resolvedBookingId,
        status: finalStatus,
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: finalStatus, bookingId: resolvedBookingId, eventId: safeEventId };
    }

    if (eventType === "payment.failed" && paymentEntity) {
      if (resolvedBookingId) {
        const bookingRef = db.collection("bookings").doc(resolvedBookingId);
        const paymentRef = db.collection("payments").doc(`pay_${resolvedBookingId}`);
        const [bookingSnap, paymentSnap] = await Promise.all([tx.get(bookingRef), tx.get(paymentRef)]);
        if (bookingSnap.exists && bookingSnap.data().bookingStatus === "PENDING_PAYMENT") {
          const bk = bookingSnap.data();
          tx.update(bookingRef, {
            paymentStatus: "FAILED",
            updatedAt: FieldValue.serverTimestamp(),
          });
          postCommitNotifications.push({
            recipientUid: bk.customerUid,
            title: "Payment Failed",
            message: `Your payment attempt for ${bk.serviceTitle || resolvedBookingId} failed. Please retry before hold expiry.`,
            eventType: "PAYMENT_FAILED",
            relatedBookingId: resolvedBookingId,
            dedupKey: `payfail_${safeEventId}`,
          });
        }
        if (paymentSnap.exists && paymentSnap.data().paymentStatus !== "SUCCESS") {
          tx.update(paymentRef, {
            paymentStatus: "FAILED",
            webhookEventId: safeEventId,
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
      }
      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        bookingId: resolvedBookingId || "",
        status: "PROCESSED",
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: "PROCESSED", bookingId: resolvedBookingId, eventId: safeEventId };
    }

    if (eventType === "refund.created" && refundEntity) {
      if (resolvedBookingId) {
        const bookingRef = db.collection("bookings").doc(resolvedBookingId);
        const paymentRef = db.collection("payments").doc(`pay_${resolvedBookingId}`);
        const [bookingSnap, paymentSnap] = await Promise.all([tx.get(bookingRef), tx.get(paymentRef)]);
        if (bookingSnap.exists && ["SUCCESS", "PARTIALLY_REFUNDED"].includes(bookingSnap.data().paymentStatus)) {
          tx.update(bookingRef, {
            paymentStatus: "REFUND_PENDING",
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
        if (paymentSnap.exists && !["REFUNDED"].includes(paymentSnap.data().paymentStatus)) {
          tx.update(paymentRef, {
            paymentStatus: "REFUND_PENDING",
            razorpayRefundId: refundEntity.id || paymentSnap.data().razorpayRefundId || "",
            webhookEventId: safeEventId,
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
      }
      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        bookingId: resolvedBookingId || "",
        status: "PROCESSED",
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: "PROCESSED", bookingId: resolvedBookingId, eventId: safeEventId };
    }

    if (eventType === "refund.processed" && refundEntity) {
      if (resolvedBookingId) {
        const refundLedgerKey = sanitizeDocId(refundEntity.id || safeEventId);
        const bookingRef = db.collection("bookings").doc(resolvedBookingId);
        const paymentRef = db.collection("payments").doc(`pay_${resolvedBookingId}`);
        const refLedRef = db.collection("ledger_entries").doc(`led_wh_ref_${resolvedBookingId}_${refundLedgerKey}`.slice(0, 128));
        const commRevRef = db.collection("ledger_entries").doc(`led_wh_comrev_${resolvedBookingId}_${refundLedgerKey}`.slice(0, 128));

        const [bookingSnap, paymentSnap, refLedSnap, commRevSnap] = await Promise.all([
          tx.get(bookingRef),
          tx.get(paymentRef),
          tx.get(refLedRef),
          tx.get(commRevRef),
        ]);

        if (bookingSnap.exists) {
          const booking = bookingSnap.data();
          const alreadyRefunded = Number(booking.refundedAmountPaise || 0);
          const remaining = Math.max(0, Number(booking.amountPaise) - alreadyRefunded);
          const refundPaise = Math.min(Math.max(0, Math.floor(Number(refundEntity.amount || 0))), remaining);

          if (refundPaise > 0) {
            const split = computeProportionalRefundSplit(booking, refundPaise);
            const nextPaymentStatus = split.isFullRefund ? "REFUNDED" : "PARTIALLY_REFUNDED";

            const bookingUpdates = {
              refundedAmountPaise: split.totalRefundedPaise,
              paymentStatus: nextPaymentStatus,
              updatedAt: FieldValue.serverTimestamp(),
            };
            if (split.isFullRefund) {
              bookingUpdates.bookingStatus = "REFUNDED";
              bookingUpdates.payoutEligibilityStatus = "REVERSED";
            }
            tx.update(bookingRef, bookingUpdates);

            if (paymentSnap.exists) {
              const payUpdates = {
                refundedAmountPaise: split.totalRefundedPaise,
                paymentStatus: nextPaymentStatus,
                razorpayRefundId: refundEntity.id || paymentSnap.data().razorpayRefundId || "",
                webhookEventId: safeEventId,
                updatedAt: FieldValue.serverTimestamp(),
              };
              if (split.isFullRefund) {
                payUpdates.providerSettlementStatus = "REFUNDED";
              }
              tx.update(paymentRef, payUpdates);
            }

            if (!refLedSnap.exists) {
              tx.set(refLedRef, {
                entryType: "REFUND_COMPLETED",
                direction: "DEBIT",
                entryStatus: "SETTLED",
                bookingId: resolvedBookingId,
                paymentId: `pay_${resolvedBookingId}`,
                customerUid: booking.customerUid,
                expertUid: booking.expertUid,
                amountPaise: refundPaise,
                currency: "INR",
                idempotencyKey: `led_wh_ref_${resolvedBookingId}_${refundLedgerKey}`,
                description: "Webhook refund processed",
                createdAt: FieldValue.serverTimestamp(),
              });
            }
            if (split.commissionReversalPaise > 0 && !commRevSnap.exists) {
              tx.set(commRevRef, {
                entryType: "COMMISSION_REVERSAL",
                direction: "DEBIT",
                entryStatus: "SETTLED",
                bookingId: resolvedBookingId,
                paymentId: `pay_${resolvedBookingId}`,
                customerUid: booking.customerUid,
                expertUid: booking.expertUid,
                amountPaise: split.commissionReversalPaise,
                currency: "INR",
                idempotencyKey: `led_wh_comrev_${resolvedBookingId}_${refundLedgerKey}`,
                description: "Proportional commission reversal (webhook)",
                createdAt: FieldValue.serverTimestamp(),
              });
            }

            postCommitNotifications.push({
              recipientUid: booking.customerUid,
              title: "Refund Settled",
              message: `Refund of ₹${(refundPaise / 100).toFixed(2)} completed for ${booking.serviceTitle || resolvedBookingId}.`,
              eventType: "REFUND_PROCESSED",
              relatedBookingId: resolvedBookingId,
              dedupKey: `ref_done_${resolvedBookingId}_${refundLedgerKey}`,
            });
          }
        }
      }

      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        bookingId: resolvedBookingId || "",
        status: "PROCESSED",
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: "PROCESSED", bookingId: resolvedBookingId, eventId: safeEventId };
    }

    if (eventType === "refund.failed" && refundEntity) {
      if (resolvedBookingId) {
        const bookingRef = db.collection("bookings").doc(resolvedBookingId);
        const paymentRef = db.collection("payments").doc(`pay_${resolvedBookingId}`);
        const [bookingSnap, paymentSnap] = await Promise.all([tx.get(bookingRef), tx.get(paymentRef)]);
        if (bookingSnap.exists && bookingSnap.data().paymentStatus === "REFUND_PENDING") {
          const bk = bookingSnap.data();
          const revertedStatus = Number(bk.refundedAmountPaise || 0) > 0 ? "PARTIALLY_REFUNDED" : "SUCCESS";
          tx.update(bookingRef, {
            paymentStatus: revertedStatus,
            updatedAt: FieldValue.serverTimestamp(),
          });
          if (paymentSnap.exists) {
            tx.update(paymentRef, {
              paymentStatus: revertedStatus,
              webhookEventId: safeEventId,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          postCommitNotifications.push({
            recipientUid: bk.customerUid,
            title: "Refund Attention Needed",
            message: `Refund could not be completed by the bank for ${bk.serviceTitle || resolvedBookingId}; support has been alerted.`,
            eventType: "REFUND_FAILED",
            relatedBookingId: resolvedBookingId,
            dedupKey: `ref_fail_${safeEventId}`,
          });
        }
      }
      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        bookingId: resolvedBookingId || "",
        status: "PROCESSED",
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: "PROCESSED", bookingId: resolvedBookingId, eventId: safeEventId };
    }

    if (
      ["payout.processed", "payout.failed", "payout.reversed", "payout.rejected", "payout.queued", "payout.initiated"].includes(eventType) &&
      payoutEntity &&
      resolvedPayoutId
    ) {
      const payoutRef = db.collection("payout_requests").doc(resolvedPayoutId);
      const payoutSnap = await tx.get(payoutRef);
      if (payoutSnap.exists) {
        const payout = payoutSnap.data();
        const bookingIds = Array.isArray(payout.eligibleBookingIds)
          ? payout.eligibleBookingIds.filter((x) => typeof x === "string" && VALID_ID_REGEX.test(x))
          : [];
        const retryCount = Number(payout.retryCount || 0);
        const ledPaidRef = db.collection("ledger_entries").doc(`led_payout_${resolvedPayoutId}`);
        const ledFailRef = db.collection("ledger_entries").doc(`led_payfail_${resolvedPayoutId}_${retryCount}`);
        const bookingRefs = bookingIds.map((bid) => db.collection("bookings").doc(bid));

        const [ledPaidSnap, ledFailSnap, ...bookingSnaps] = await Promise.all([
          tx.get(ledPaidRef),
          tx.get(ledFailRef),
          ...bookingRefs.map((ref) => tx.get(ref)),
        ]);

        if (eventType === "payout.processed" && payout.status !== "PAID") {
          const utr = String(payoutEntity.utr || payoutEntity.id || safeEventId).slice(0, 128);
          tx.update(payoutRef, {
            status: "PAID",
            payoutReference: utr,
            providerTransferId: payoutEntity.id || payout.providerTransferId || "",
            providerPayoutStatus: "processed",
            updatedAt: FieldValue.serverTimestamp(),
          });
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
              payoutRequestId: resolvedPayoutId,
              customerUid: "",
              expertUid: payout.expertUid,
              amountPaise: Number(payout.amountPaise),
              currency: "INR",
              idempotencyKey: `led_payout_${resolvedPayoutId}`,
              description: `RazorpayX payout processed (UTR: ${utr})`.slice(0, 300),
              createdAt: FieldValue.serverTimestamp(),
            });
          }
          postCommitNotifications.push({
            recipientUid: payout.expertUid,
            title: "Payout Settled",
            message: `Your payout of ₹${(Number(payout.amountPaise) / 100).toFixed(2)} has been settled.`,
            eventType: "PAYOUT_UPDATED",
            dedupKey: `pout_wh_paid_${resolvedPayoutId}`,
          });
        } else if (
          ["payout.failed", "payout.reversed", "payout.rejected"].includes(eventType) &&
          !["FAILED", "REJECTED", "PAID"].includes(payout.status)
        ) {
          tx.update(payoutRef, {
            status: "FAILED",
            failureReason: String(
              payoutEntity.failure_reason || payoutEntity.status_details?.description || eventType
            ).slice(0, 300),
            providerTransferId: payoutEntity.id || payout.providerTransferId || "",
            providerPayoutStatus: String(payoutEntity.status || eventType.replace("payout.", "")).slice(0, 40),
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
              payoutRequestId: resolvedPayoutId,
              customerUid: "",
              expertUid: payout.expertUid,
              amountPaise: Number(payout.amountPaise),
              currency: "INR",
              idempotencyKey: `led_payfail_${resolvedPayoutId}_${retryCount}`,
              description: `${eventType}: payout balance restored for retry`.slice(0, 300),
              createdAt: FieldValue.serverTimestamp(),
            });
          }
          postCommitNotifications.push({
            recipientUid: payout.expertUid,
            title: eventType === "payout.rejected" ? "Payout Rejected" : "Payout Failed",
            message: "Your payout transfer could not complete and balance was restored for retry.",
            eventType: "PAYOUT_UPDATED",
            dedupKey: `pout_wh_fail_${safeEventId}`,
          });
        } else if (["payout.queued", "payout.initiated"].includes(eventType)) {
          tx.update(payoutRef, {
            providerTransferId: payoutEntity.id || payout.providerTransferId || "",
            providerPayoutStatus: String(payoutEntity.status || eventType.replace("payout.", "")).slice(0, 40),
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
      }
      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        payoutRequestId: resolvedPayoutId,
        status: "PROCESSED",
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: "PROCESSED", payoutRequestId: resolvedPayoutId, eventId: safeEventId };
    }

    if (["transfer.processed", "transfer.failed"].includes(eventType) && transferEntity && resolvedBookingId) {
      const paymentRef = db.collection("payments").doc(`pay_${resolvedBookingId}`);
      const paymentSnap = await tx.get(paymentRef);
      if (paymentSnap.exists) {
        tx.update(paymentRef, {
          razorpayTransferId: transferEntity.id || paymentSnap.data().razorpayTransferId || "",
          routeTransferStatus: eventType === "transfer.processed" ? "SETTLED" : "FAILED",
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      tx.set(webhookRef, {
        eventId: safeEventId,
        eventType,
        bookingId: resolvedBookingId,
        status: "PROCESSED",
        processedAt: FieldValue.serverTimestamp(),
      });
      return { status: "PROCESSED", bookingId: resolvedBookingId, eventId: safeEventId };
    }

    tx.set(webhookRef, {
      eventId: safeEventId,
      eventType,
      status: "PROCESSED_NOOP",
      processedAt: FieldValue.serverTimestamp(),
    });
    return { status: "PROCESSED_NOOP", eventType, eventId: safeEventId };
  });

  if (postCommitNotifications.length > 0) {
    await dispatchNotificationsWithPush({
      db,
      messaging,
      items: postCommitNotifications,
    }).catch(() => {});
  }

  return txResult;
}

async function initiateRefundTransaction({
  db,
  messaging = null,
  bookingId,
  refundAmountPaise,
  reason,
  actor,
  razorpayClient = null,
  enableRazorpayApi = true,
  requireLiveRazorpay = false,
}) {
  if (!actor.isAdmin) {
    throw new HttpAppError(403, "Forbidden: admin privileges required to initiate refunds");
  }
  assertValidId(bookingId, "bookingId");

  const refundPaise = Number(refundAmountPaise);
  if (!Number.isInteger(refundPaise) || refundPaise <= 0) {
    throw new HttpAppError(400, "refundAmountPaise must be a positive integer");
  }

  const cleanReason = String(reason || "Admin refund").trim().slice(0, 300) || "Admin refund";
  const bookingRef = db.collection("bookings").doc(bookingId);
  const paymentRef = db.collection("payments").doc(`pay_${bookingId}`);
  const disputeRef = db.collection("disputes").doc(bookingId);

  const preflight = await db.runTransaction(async (tx) => {
    const [bookingSnap, paymentSnap] = await Promise.all([tx.get(bookingRef), tx.get(paymentRef)]);
    if (!bookingSnap.exists) {
      throw new HttpAppError(404, "Booking not found");
    }
    const booking = bookingSnap.data();

    if (!["SUCCESS", "PARTIALLY_REFUNDED"].includes(booking.paymentStatus)) {
      throw new HttpAppError(409, "Only captured payments can be refunded");
    }
    if (booking.payoutEligibilityStatus === "PROCESSING") {
      throw new HttpAppError(409, "Cannot refund booking while an expert payout is PROCESSING; settle or fail the payout first");
    }

    const alreadyRefunded = Number(booking.refundedAmountPaise || 0);
    if (alreadyRefunded + refundPaise > Number(booking.amountPaise)) {
      throw new HttpAppError(400, "Refund exceeds remaining captured payment amount");
    }

    return {
      booking,
      payment: paymentSnap.exists ? paymentSnap.data() : null,
      alreadyRefunded,
    };
  });

  const shouldUseLiveRazorpay =
    enableRazorpayApi && razorpayClient?.isConfigured() && Boolean(preflight.payment?.razorpayPaymentId);

  let gatewayRefund = null;
  if (shouldUseLiveRazorpay) {
    gatewayRefund = await razorpayClient.createRefund(preflight.payment.razorpayPaymentId, {
      amountPaise: refundPaise,
      notes: { bookingId, reason: cleanReason, actorUid: actor.uid },
      receipt: `rfnd_${bookingId.slice(0, 24)}_${preflight.alreadyRefunded}`,
    });
  } else if (requireLiveRazorpay && preflight.payment?.razorpayPaymentId) {
    throw new HttpAppError(503, "Live Razorpay credentials are required in production to process refunds");
  }

  const isAsyncPending = gatewayRefund && gatewayRefund.status && gatewayRefund.status !== "processed";
  const gatewayRefundId = gatewayRefund?.id || "";

  const txOutcome = await db.runTransaction(async (tx) => {
    const [bookingSnap, paymentSnap, disputeSnap] = await Promise.all([
      tx.get(bookingRef),
      tx.get(paymentRef),
      tx.get(disputeRef),
    ]);

    if (!bookingSnap.exists) throw new HttpAppError(404, "Booking not found");
    const booking = bookingSnap.data();

    const alreadyRefunded = Number(booking.refundedAmountPaise || 0);
    if (alreadyRefunded + refundPaise > Number(booking.amountPaise)) {
      throw new HttpAppError(400, "Refund exceeds remaining captured payment amount");
    }

    const split = computeProportionalRefundSplit(booking, refundPaise);

    if (isAsyncPending) {
      tx.update(bookingRef, {
        paymentStatus: "REFUND_PENDING",
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (paymentSnap.exists) {
        tx.update(paymentRef, {
          paymentStatus: "REFUND_PENDING",
          razorpayRefundId: gatewayRefundId,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      tx.set(db.collection("ledger_entries").doc(`led_refinit_${bookingId}_${alreadyRefunded}_${refundPaise}`.slice(0, 128)), {
        entryType: "REFUND_INITIATED",
        direction: "DEBIT",
        entryStatus: "PENDING",
        bookingId,
        paymentId: `pay_${bookingId}`,
        customerUid: booking.customerUid,
        expertUid: booking.expertUid,
        amountPaise: refundPaise,
        currency: "INR",
        idempotencyKey: `led_refinit_${bookingId}_${alreadyRefunded}_${refundPaise}`,
        description: `Refund initiated via Razorpay (${gatewayRefundId})`.slice(0, 300),
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.set(db.collection("audit_logs").doc(), {
        actorUid: actor.uid,
        actorEmail: actor.email || "",
        action: "INITIATE_REFUND",
        targetType: "booking",
        targetId: bookingId,
        details: `status=PENDING refundPaise=${refundPaise} gatewayRefundId=${gatewayRefundId} reason=${cleanReason}`.slice(0, 1000),
        createdAt: FieldValue.serverTimestamp(),
      });
      return {
        booking,
        result: {
          bookingId,
          refundPaise,
          totalRefundedPaise: alreadyRefunded,
          commissionReversalPaise: split.commissionReversalPaise,
          expertReductionPaise: split.expertReductionPaise,
          isFullRefund: split.isFullRefund,
          refundStatus: "REFUND_PENDING",
          gatewayRefundId,
        },
      };
    }

    let lockRef = null;
    let lockSnap = null;
    if (booking.slotKey && VALID_ID_REGEX.test(booking.slotKey)) {
      lockRef = db.collection("slot_locks").doc(booking.slotKey);
      lockSnap = await tx.get(lockRef);
    }

    const nextPaymentStatus = split.isFullRefund ? "REFUNDED" : "PARTIALLY_REFUNDED";
    const payoutAlreadyPaid = booking.payoutEligibilityStatus === "PAID";

    const bookingUpdates = {
      refundedAmountPaise: split.totalRefundedPaise,
      paymentStatus: nextPaymentStatus,
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (payoutAlreadyPaid) {
      bookingUpdates.payoutClawbackRequired = true;
    }
    if (split.isFullRefund) {
      bookingUpdates.bookingStatus = "REFUNDED";
      bookingUpdates.payoutEligibilityStatus = "REVERSED";
      bookingUpdates.disputeStatus = "RESOLVED_REFUND_FULL";
    } else if (["OPEN", "UNDER_REVIEW"].includes(booking.disputeStatus)) {
      bookingUpdates.disputeStatus = "RESOLVED_REFUND_PARTIAL";
    }

    tx.update(bookingRef, bookingUpdates);

    if (split.isFullRefund && lockSnap && lockSnap.exists) {
      tx.update(lockRef, {
        bookingStatus: "REFUNDED",
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    if (paymentSnap.exists) {
      const payUpdates = {
        refundedAmountPaise: split.totalRefundedPaise,
        paymentStatus: nextPaymentStatus,
        updatedAt: FieldValue.serverTimestamp(),
      };
      if (gatewayRefundId) payUpdates.razorpayRefundId = gatewayRefundId;
      if (split.isFullRefund) {
        payUpdates.providerSettlementStatus = "REFUNDED";
      }
      tx.update(paymentRef, payUpdates);
    }

    if (disputeSnap.exists && ["OPEN", "UNDER_REVIEW"].includes(disputeSnap.data().status)) {
      tx.update(disputeRef, {
        status: split.isFullRefund ? "RESOLVED_REFUND_FULL" : "RESOLVED_REFUND_PARTIAL",
        approvedRefundPaise: split.totalRefundedPaise,
        resolutionNote: cleanReason,
        resolvedByUid: actor.uid,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    const ledgerSuffix = `${alreadyRefunded}_${refundPaise}`;
    tx.set(db.collection("ledger_entries").doc(`led_refund_${bookingId}_${ledgerSuffix}`.slice(0, 128)), {
      entryType: "REFUND_COMPLETED",
      direction: "DEBIT",
      entryStatus: "SETTLED",
      bookingId,
      paymentId: `pay_${bookingId}`,
      customerUid: booking.customerUid,
      expertUid: booking.expertUid,
      amountPaise: refundPaise,
      currency: "INR",
      idempotencyKey: `led_refund_${bookingId}_${ledgerSuffix}`,
      description: `Refund (${cleanReason})`.slice(0, 300),
      createdAt: FieldValue.serverTimestamp(),
    });

    if (split.commissionReversalPaise > 0) {
      tx.set(db.collection("ledger_entries").doc(`led_commrev_${bookingId}_${ledgerSuffix}`.slice(0, 128)), {
        entryType: "COMMISSION_REVERSAL",
        direction: "DEBIT",
        entryStatus: "SETTLED",
        bookingId,
        paymentId: `pay_${bookingId}`,
        customerUid: booking.customerUid,
        expertUid: booking.expertUid,
        amountPaise: split.commissionReversalPaise,
        currency: "INR",
        idempotencyKey: `led_commrev_${bookingId}_${ledgerSuffix}`,
        description: "Proportional commission reversal",
        createdAt: FieldValue.serverTimestamp(),
      });
    }

    if (payoutAlreadyPaid && split.expertReductionPaise > 0) {
      tx.set(db.collection("ledger_entries").doc(`led_clawback_${bookingId}_${ledgerSuffix}`.slice(0, 128)), {
        entryType: "RECONCILIATION_ADJUSTMENT",
        direction: "DEBIT",
        entryStatus: "PENDING",
        bookingId,
        paymentId: `pay_${bookingId}`,
        customerUid: booking.customerUid,
        expertUid: booking.expertUid,
        amountPaise: split.expertReductionPaise,
        currency: "INR",
        idempotencyKey: `led_clawback_${bookingId}_${ledgerSuffix}`,
        description: "Post-payout refund clawback owed by expert",
        createdAt: FieldValue.serverTimestamp(),
      });
    }

    tx.set(db.collection("audit_logs").doc(), {
      actorUid: actor.uid,
      actorEmail: actor.email || "",
      action: "INITIATE_REFUND",
      targetType: "booking",
      targetId: bookingId,
      details: `refundPaise=${refundPaise} totalRefunded=${split.totalRefundedPaise} reason=${cleanReason}`.slice(0, 1000),
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      booking,
      result: {
        bookingId,
        refundPaise,
        totalRefundedPaise: split.totalRefundedPaise,
        commissionReversalPaise: split.commissionReversalPaise,
        expertReductionPaise: split.expertReductionPaise,
        isFullRefund: split.isFullRefund,
        refundStatus: nextPaymentStatus,
        gatewayRefundId: gatewayRefundId || undefined,
      },
    };
  });

  await dispatchNotificationsWithPush({
    db,
    messaging,
    items: [
      {
        recipientUid: txOutcome.booking.customerUid,
        title: isAsyncPending ? "Refund Initiated" : "Refund Processed",
        message: `Refund of ₹${(refundPaise / 100).toFixed(2)} ${isAsyncPending ? "initiated" : "processed"} for ${txOutcome.booking.serviceTitle || bookingId}.`,
        eventType: isAsyncPending ? "REFUND_INITIATED" : "REFUND_PROCESSED",
        relatedBookingId: bookingId,
        dedupKey: `refund_init_${bookingId}_${preflight.alreadyRefunded}_${refundPaise}`,
      },
    ],
  });

  return txOutcome.result;
}

module.exports = {
  HttpAppError,
  assertValidId,
  sanitizeDocId,
  createBookingWithHoldTransaction,
  rescheduleBookingTransaction,
  cancelBookingTransaction,
  createOrderTransaction,
  verifyPaymentTransaction,
  processWebhookTransaction,
  syncCalendarMetadata,
  syncMeetAttendanceSafe,
  initiateRefundTransaction,
  saveExpertPayoutOnboarding,
  processPayoutTransaction,
  registerFcmToken,
};
