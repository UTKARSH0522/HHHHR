const crypto = require("node:crypto");
const { FieldValue } = require("firebase-admin/firestore");
const { calculateFinancialSplit, computeProportionalRefundSplit } = require("./paymentService");
const { buildCalendarEventPayload } = require("./googleWorkspaceService");
const { dispatchNotificationsWithPush } = require("./notificationService");

class HttpAppError extends Error {
  constructor(statusCode, message) {
    super(message);
    this.statusCode = statusCode;
  }
}

const VALID_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;

function assertValidId(value, label = "id") {
  if (typeof value !== "string" || !VALID_ID_REGEX.test(value)) {
    throw new HttpAppError(400, `Invalid ${label}`);
  }
  return value;
}

function sanitizeDocId(raw) {
  const cleaned = String(raw || "").replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 128);
  if (cleaned.length >= 1 && VALID_ID_REGEX.test(cleaned)) {
    return cleaned;
  }
  return crypto.createHash("sha256").update(String(raw || "")).digest("hex").slice(0, 64);
}

function slotEpochMs(dateIso, startMinute, offset = "+05:30") {
  const hrs = String(Math.floor(Number(startMinute) / 60)).padStart(2, "0");
  const mins = String(Number(startMinute) % 60).padStart(2, "0");
  return Date.parse(`${dateIso}T${hrs}:${mins}:00${offset}`);
}

function isoDayOfWeek1To7(dateIso) {
  const dt = new Date(`${dateIso}T00:00:00Z`);
  const d = dt.getUTCDay(); // 0=Sun..6=Sat
  return d === 0 ? 7 : d;
}

function isLockBlocking(lock, nowMs = Date.now()) {
  if (!lock) return false;
  const st = String(lock.bookingStatus || "CANCELLED");
  if (["CONFIRMED", "SCHEDULED", "IN_PROGRESS", "ATTENDANCE_PENDING", "COMPLETION_PENDING", "COMPLETED", "DISPUTED"].includes(st)) {
    return true;
  }
  if (st === "PENDING_PAYMENT") {
    return nowMs <= Number(lock.holdExpiresAtEpochMs || 0);
  }
  return false;
}

function checkAvailabilityAndLocks({
  expertId,
  dateIso,
  startMinute,
  durationMinutes,
  ruleData,
  exceptionData,
  lockDocs,
  nowMs,
  ignoreBookingId = null,
}) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateIso)) {
    throw new HttpAppError(400, "dateIso must be YYYY-MM-DD");
  }
  const start = Number(startMinute);
  const dur = Number(durationMinutes);
  const end = start + dur;
  if (!Number.isInteger(start) || start < 0 || start >= 1440 || end > 1440) {
    throw new HttpAppError(400, "Invalid slot startMinute or duration");
  }
  const scheduledStartMs = slotEpochMs(dateIso, start);
  if (!Number.isFinite(scheduledStartMs) || scheduledStartMs <= nowMs) {
    throw new HttpAppError(400, "Selected consultation slot must be in the future");
  }
  if (exceptionData && exceptionData.unavailable === true) {
    throw new HttpAppError(409, `Expert is unavailable on ${dateIso}: ${exceptionData.reason || "Holiday"}`);
  }
  if (ruleData) {
    if (ruleData.enabled === false) {
      throw new HttpAppError(409, "Expert is not available on this day of the week");
    }
    const rStart = Number(ruleData.startMinute ?? 0);
    const rEnd = Number(ruleData.endMinute ?? 1440);
    if (start < rStart || end > rEnd) {
      throw new HttpAppError(409, "Selected slot is outside expert working hours");
    }
    const bStart = Number(ruleData.breakStartMinute ?? -1);
    const bEnd = Number(ruleData.breakEndMinute ?? -1);
    if (bStart >= 0 && bEnd > bStart && start < bEnd && end > bStart) {
      throw new HttpAppError(409, "Selected slot overlaps expert break window");
    }
  }
  for (const lock of lockDocs) {
    if (!lock) continue;
    if (ignoreBookingId && lock.bookingId === ignoreBookingId) continue;
    if (isLockBlocking(lock, nowMs) && start < Number(lock.endMinute) && end > Number(lock.startMinute)) {
      throw new HttpAppError(409, "Selected slot overlaps an existing active reservation");
    }
  }
  return { start, end, scheduledStartMs };
}

async function createBookingWithHoldTransaction({
  db,
  actor,
  expertId,
  serviceId,
  dateIso,
  startMinute,
  topic,
  referralCode = "",
  customerName = "",
  nowEpochMs = Date.now(),
}) {
  assertValidId(expertId, "expertId");
  assertValidId(serviceId, "serviceId");

  if (!actor.email || !actor.emailVerified) {
    throw new HttpAppError(400, "Verified Google account email is required to book consultations");
  }
  const cleanTopic = String(topic || "").trim();
  if (cleanTopic.length < 3 || cleanTopic.length > 1000) {
    throw new HttpAppError(400, "Consultation topic must be between 3 and 1000 characters");
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(dateIso || ""))) {
    throw new HttpAppError(400, "dateIso must use YYYY-MM-DD format");
  }

  const bookingRef = db.collection("bookings").doc();
  const bookingId = bookingRef.id;
  const expertRef = db.collection("experts").doc(expertId);
  const serviceRef = db.collection("services").doc(serviceId);
  const settingsRef = db.collection("platform_settings").doc("config");
  const dayOfWeek = isoDayOfWeek1To7(dateIso);
  const ruleRef = db.collection("availability_rules").doc(`${expertId}_day_${dayOfWeek}`);
  const exceptionRef = db.collection("availability_exceptions").doc(`${expertId}_ex_${dateIso}`);

  const candidateStarts = [];
  for (let delta = -75; delta <= 90; delta += 15) {
    const candidate = Number(startMinute) + delta;
    if (candidate >= 0 && candidate < 1440) {
      candidateStarts.push(candidate);
    }
  }
  const lockRefs = [...new Set(candidateStarts)].map((m) =>
    db.collection("slot_locks").doc(`${expertId}_${dateIso}_${m}`)
  );

  const checkInPin = String(crypto.randomInt(100000, 999999));

  return db.runTransaction(async (tx) => {
    const [expertSnap, serviceSnap, settingsSnap, ruleSnap, exceptionSnap, ...lockSnaps] = await Promise.all([
      tx.get(expertRef),
      tx.get(serviceRef),
      tx.get(settingsRef),
      tx.get(ruleRef),
      tx.get(exceptionRef),
      ...lockRefs.map((ref) => tx.get(ref)),
    ]);

    if (!expertSnap.exists) throw new HttpAppError(404, "Expert profile not found");
    if (!serviceSnap.exists) throw new HttpAppError(404, "Consultation service not found");

    const expert = expertSnap.data();
    const service = serviceSnap.data();
    const settings = settingsSnap.exists ? settingsSnap.data() : {};

    if (expert.verificationStatus !== "APPROVED" || expert.accountStatus !== "ACTIVE") {
      throw new HttpAppError(409, "Selected expert is not active or approved");
    }
    if (service.active === false || service.expertId !== expertId) {
      throw new HttpAppError(409, "Selected consultation service is inactive");
    }
    if (expert.expertUid === actor.uid) {
      throw new HttpAppError(403, "You cannot book your own consultation service");
    }

    const duration = Number(service.durationMinutes);
    const lockDocs = lockSnaps.map((s) => (s.exists ? s.data() : null));
    const { start, end } = checkAvailabilityAndLocks({
      expertId,
      dateIso,
      startMinute,
      durationMinutes: duration,
      ruleData: ruleSnap.exists ? ruleSnap.data() : null,
      exceptionData: exceptionSnap.exists ? exceptionSnap.data() : null,
      lockDocs,
      nowMs: Number(nowEpochMs),
    });

    const cleanRef = String(referralCode || "").trim().toUpperCase();
    const isExpertReferral = cleanRef.length > 0 && cleanRef === String(expert.referralCode || "").toUpperCase();
    const referralType = isExpertReferral ? "EXPERT_REFERRAL" : "PLATFORM";
    const commissionRateBps = isExpertReferral
      ? Number(settings.referralCommissionBps ?? 1000)
      : Number(settings.platformCommissionBps ?? 1500);

    const split = calculateFinancialSplit({
      amountPaise: Number(service.pricePaise),
      commissionRateBps,
      gatewayFeeBps: Number(settings.gatewayFeeBps ?? 200),
      tdsBps: Number(settings.tdsBps ?? 0),
    });

    const holdMinutes = Number(settings.paymentHoldMinutes ?? 10);
    const holdExpiresAtEpochMs = Number(nowEpochMs) + holdMinutes * 60_000;
    const slotKey = `${expertId}_${dateIso}_${start}`;
    const targetLockRef = db.collection("slot_locks").doc(slotKey);

    const bookingDoc = {
      customerUid: actor.uid,
      customerName: String(customerName || actor.email.split("@")[0] || "Customer").slice(0, 120),
      verifiedCustomerGoogleEmail: actor.email.toLowerCase(),
      expertId,
      expertUid: expert.expertUid,
      expertName: expert.fullName,
      verifiedExpertGoogleEmail: String(expert.verifiedGoogleEmail || "").toLowerCase(),
      serviceId,
      serviceTitle: service.title,
      durationMinutes: duration,
      dateIso,
      startMinute: start,
      endMinute: end,
      slotKey,
      timezone: expert.timezone || "Asia/Kolkata",
      topic: cleanTopic,
      referralType,
      amountPaise: split.amountPaise,
      commissionRateBps: split.commissionRateBps,
      commissionPaise: split.commissionPaise,
      gatewayFeePaise: split.gatewayFeePaise,
      taxDeductionPaise: split.taxDeductionPaise,
      expertEarningPaise: split.expertEarningPaise,
      refundedAmountPaise: 0,
      bookingStatus: "PENDING_PAYMENT",
      paymentStatus: "CREATED",
      providerSettlementStatus: "PENDING_CAPTURE",
      attendanceStatus: "NOT_STARTED",
      attendanceVerificationSource: "NONE",
      payoutEligibilityStatus: "NOT_ELIGIBLE",
      disputeStatus: "NONE",
      calendarSyncStatus: "NOT_SYNCED",
      conferenceRequestId: `conf_${bookingId}`,
      meetingAccessMode: "TRUSTED_INVITEES_ONLY",
      checkInPin,
      customerCheckInAtMs: 0,
      expertCheckInAtMs: 0,
      customerJoinedMinutes: 0,
      expertJoinedMinutes: 0,
      overlapMinutes: 0,
      customerCompletedConfirmed: false,
      expertCompletedConfirmed: false,
      completedAtEpochMs: 0,
      disputeWindowEndsAtEpochMs: 0,
      holdExpiresAtEpochMs,
      reviewed: false,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (service.preparationInstructions) {
      bookingDoc.preparationInstructions = String(service.preparationInstructions).slice(0, 1000);
    }
    if (isExpertReferral) {
      bookingDoc.referralCode = cleanRef;
    }

    tx.set(bookingRef, bookingDoc);
    tx.set(targetLockRef, {
      expertId,
      expertUid: expert.expertUid,
      holderUid: actor.uid,
      bookingId,
      dateIso,
      startMinute: start,
      endMinute: end,
      bookingStatus: "PENDING_PAYMENT",
      holdExpiresAtEpochMs,
      updatedAt: FieldValue.serverTimestamp(),
    });

    return { id: bookingId, ...bookingDoc };
  });
}

async function rescheduleBookingTransaction({
  db,
  messaging = null,
  bookingId,
  newDateIso,
  newStartMinute,
  reason = "Participant requested reschedule",
  actor,
  googleWorkspaceClient = null,
  nowEpochMs = Date.now(),
}) {
  assertValidId(bookingId, "bookingId");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(newDateIso || ""))) {
    throw new HttpAppError(400, "newDateIso must use YYYY-MM-DD format");
  }

  const bookingRef = db.collection("bookings").doc(bookingId);
  const reschedResult = await db.runTransaction(async (tx) => {
    const snap = await tx.get(bookingRef);
    if (!snap.exists) throw new HttpAppError(404, "Booking not found");
    const booking = { id: snap.id, ...snap.data() };

    const isParticipant = booking.customerUid === actor.uid || booking.expertUid === actor.uid;
    if (!actor.isAdmin && !isParticipant) {
      throw new HttpAppError(403, "Forbidden: only booking participants or admin can reschedule");
    }
    if (!["CONFIRMED", "SCHEDULED"].includes(booking.bookingStatus)) {
      throw new HttpAppError(409, `Cannot reschedule booking in status ${booking.bookingStatus}`);
    }

    const oldStartMs = slotEpochMs(booking.dateIso, booking.startMinute);
    if (!actor.isAdmin && Number.isFinite(oldStartMs) && oldStartMs - Number(nowEpochMs) < 4 * 3_600_000) {
      throw new HttpAppError(409, "Rescheduling is closed within 4 hours of the consultation start time");
    }

    const dayOfWeek = isoDayOfWeek1To7(newDateIso);
    const ruleRef = db.collection("availability_rules").doc(`${booking.expertId}_day_${dayOfWeek}`);
    const exceptionRef = db.collection("availability_exceptions").doc(`${booking.expertId}_ex_${newDateIso}`);

    const candidateStarts = [];
    for (let delta = -75; delta <= 90; delta += 15) {
      const m = Number(newStartMinute) + delta;
      if (m >= 0 && m < 1440) candidateStarts.push(m);
    }
    const candidateRefs = [...new Set(candidateStarts)].map((m) =>
      db.collection("slot_locks").doc(`${booking.expertId}_${newDateIso}_${m}`)
    );
    const oldLockRef = booking.slotKey && VALID_ID_REGEX.test(booking.slotKey)
      ? db.collection("slot_locks").doc(booking.slotKey)
      : null;

    const reads = [tx.get(ruleRef), tx.get(exceptionRef), ...candidateRefs.map((r) => tx.get(r))];
    if (oldLockRef) reads.push(tx.get(oldLockRef));

    const results = await Promise.all(reads);
    const ruleSnap = results[0];
    const exceptionSnap = results[1];
    const lockSnaps = results.slice(2, 2 + candidateRefs.length);
    const oldLockSnap = oldLockRef ? results[results.length - 1] : null;

    const duration = Number(booking.durationMinutes || 30);
    const { start, end } = checkAvailabilityAndLocks({
      expertId: booking.expertId,
      dateIso: newDateIso,
      startMinute: newStartMinute,
      durationMinutes: duration,
      ruleData: ruleSnap.exists ? ruleSnap.data() : null,
      exceptionData: exceptionSnap.exists ? exceptionSnap.data() : null,
      lockDocs: lockSnaps.map((s) => (s.exists ? s.data() : null)),
      nowMs: Number(nowEpochMs),
      ignoreBookingId: bookingId,
    });

    const newSlotKey = `${booking.expertId}_${newDateIso}_${start}`;
    const newLockRef = db.collection("slot_locks").doc(newSlotKey);

    if (oldLockRef && oldLockSnap && oldLockSnap.exists && newSlotKey !== booking.slotKey) {
      tx.update(oldLockRef, {
        bookingStatus: "CANCELLED",
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    tx.set(newLockRef, {
      expertId: booking.expertId,
      expertUid: booking.expertUid,
      holderUid: booking.customerUid,
      bookingId,
      dateIso: newDateIso,
      startMinute: start,
      endMinute: end,
      bookingStatus: booking.bookingStatus,
      holdExpiresAtEpochMs: Number(booking.holdExpiresAtEpochMs || Number(nowEpochMs) + 600_000),
      updatedAt: FieldValue.serverTimestamp(),
    });

    const nextCount = Number(booking.rescheduleCount || 0) + 1;
    tx.update(bookingRef, {
      dateIso: newDateIso,
      startMinute: start,
      endMinute: end,
      slotKey: newSlotKey,
      rescheduleCount: nextCount,
      rescheduledAtEpochMs: Number(nowEpochMs),
      calendarSyncStatus: booking.googleCalendarEventId ? "PENDING_BACKEND_SYNC" : booking.calendarSyncStatus || "NOT_SYNCED",
      updatedAt: FieldValue.serverTimestamp(),
    });

    return {
      booking: {
        ...booking,
        dateIso: newDateIso,
        startMinute: start,
        endMinute: end,
        slotKey: newSlotKey,
        rescheduleCount: nextCount,
      },
      otherUid: actor.uid === booking.customerUid ? booking.expertUid : booking.customerUid,
    };
  });

  let calendarSyncStatus = reschedResult.booking.calendarSyncStatus || "NOT_SYNCED";
  if (googleWorkspaceClient?.isConfigured() && reschedResult.booking.googleCalendarEventId) {
    try {
      const patched = await googleWorkspaceClient.patchCalendarEvent(reschedResult.booking);
      calendarSyncStatus = patched.syncStatus || "UPDATED";
      await bookingRef.update({
        calendarSyncStatus,
        updatedAt: FieldValue.serverTimestamp(),
      });
    } catch {
      calendarSyncStatus = "SYNC_FAILED";
      await bookingRef.update({
        calendarSyncStatus,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  }

  await dispatchNotificationsWithPush({
    db,
    messaging,
    items: [
      {
        recipientUid: reschedResult.otherUid,
        title: "Consultation Rescheduled",
        message: `${reschedResult.booking.serviceTitle} moved to ${newDateIso}. Reason: ${String(reason).slice(0, 120)}`,
        eventType: "BOOKING_RESCHEDULED",
        relatedBookingId: bookingId,
        dedupKey: `resched_${bookingId}_${reschedResult.booking.rescheduleCount}`,
      },
    ],
  });

  return {
    bookingId,
    dateIso: newDateIso,
    startMinute: reschedResult.booking.startMinute,
    endMinute: reschedResult.booking.endMinute,
    slotKey: reschedResult.booking.slotKey,
    rescheduleCount: reschedResult.booking.rescheduleCount,
    calendarSyncStatus,
  };
}

async function cancelBookingTransaction({
  db,
  messaging = null,
  bookingId,
  reason = "Cancelled by participant",
  actor,
  razorpayClient = null,
  googleWorkspaceClient = null,
  nowEpochMs = Date.now(),
}) {
  assertValidId(bookingId, "bookingId");
  const cleanReason = String(reason || "Cancelled by participant").trim().slice(0, 300) || "Cancelled by participant";
  const bookingRef = db.collection("bookings").doc(bookingId);
  const paymentRef = db.collection("payments").doc(`pay_${bookingId}`);
  const settingsRef = db.collection("platform_settings").doc("config");

  const txOutcome = await db.runTransaction(async (tx) => {
    const [bookingSnap, paymentSnap, settingsSnap] = await Promise.all([
      tx.get(bookingRef),
      tx.get(paymentRef),
      tx.get(settingsRef),
    ]);
    if (!bookingSnap.exists) throw new HttpAppError(404, "Booking not found");
    const booking = { id: bookingSnap.id, ...bookingSnap.data() };

    const isCustomer = booking.customerUid === actor.uid;
    const isExpert = booking.expertUid === actor.uid;
    if (!actor.isAdmin && !isCustomer && !isExpert) {
      throw new HttpAppError(403, "Forbidden: only booking participants or admin can cancel");
    }
    if (!["PENDING_PAYMENT", "CONFIRMED", "SCHEDULED"].includes(booking.bookingStatus)) {
      throw new HttpAppError(409, `Booking in state ${booking.bookingStatus} cannot be cancelled`);
    }

    const lockRef =
      booking.slotKey && VALID_ID_REGEX.test(booking.slotKey)
        ? db.collection("slot_locks").doc(booking.slotKey)
        : null;
    const lockSnap = lockRef ? await tx.get(lockRef) : null;

    const settings = settingsSnap.exists ? settingsSnap.data() : {};
    const cancelWindowHours = Number(settings.cancellationWindowHours ?? 24);
    const startMs = slotEpochMs(booking.dateIso, booking.startMinute);
    const hoursUntil = Number.isFinite(startMs) ? (startMs - Number(nowEpochMs)) / 3_600_000 : 999;

    let refundPaise = 0;
    let policyNote = "Cancelled before payment capture";
    if (booking.paymentStatus === "SUCCESS") {
      if (isExpert || actor.isAdmin || hoursUntil >= cancelWindowHours) {
        refundPaise = Math.max(0, Number(booking.amountPaise) - Number(booking.refundedAmountPaise || 0));
        policyNote = isExpert
          ? "Full refund: expert initiated cancellation"
          : `Full refund: cancelled >= ${cancelWindowHours}h before session`;
      } else {
        refundPaise = 0;
        policyNote = `No refund: customer cancelled inside ${cancelWindowHours}h late window`;
      }
    }

    const bookingUpdates = {
      bookingStatus: "CANCELLED",
      cancellationReason: `${cleanReason} (${policyNote})`.slice(0, 500),
      cancelledByUid: actor.uid,
      payoutEligibilityStatus: refundPaise > 0 ? "ON_HOLD" : "REVERSED",
      calendarSyncStatus: booking.googleCalendarEventId ? "CANCELLED" : booking.calendarSyncStatus || "NOT_SYNCED",
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (refundPaise > 0) {
      bookingUpdates.paymentStatus = "REFUND_PENDING";
    }
    tx.update(bookingRef, bookingUpdates);

    if (lockRef && lockSnap && lockSnap.exists) {
      tx.update(lockRef, {
        bookingStatus: "CANCELLED",
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    const payment = paymentSnap.exists ? paymentSnap.data() : null;
    if (refundPaise > 0 && paymentSnap.exists) {
      tx.update(paymentRef, {
        paymentStatus: "REFUND_PENDING",
        updatedAt: FieldValue.serverTimestamp(),
      });
    }

    return {
      booking,
      payment,
      refundPaise,
      policyNote,
      otherUid: isCustomer ? booking.expertUid : booking.customerUid,
    };
  });

  let refundStatus = txOutcome.refundPaise > 0 ? "REFUND_PENDING" : "NONE";
  let gatewayRefundId = "";

  if (
    txOutcome.refundPaise > 0 &&
    txOutcome.payment?.razorpayPaymentId &&
    razorpayClient?.isConfigured() &&
    txOutcome.payment?.testMode !== true
  ) {
    try {
      const rzpRefund = await razorpayClient.createRefund(txOutcome.payment.razorpayPaymentId, {
        amountPaise: txOutcome.refundPaise,
        notes: { bookingId, reason: cleanReason },
        receipt: `ref_${bookingId.slice(0, 32)}`,
      });
      gatewayRefundId = rzpRefund.id || "";
      if (rzpRefund.status === "processed") {
        const split = computeProportionalRefundSplit(txOutcome.booking, txOutcome.refundPaise);
        await db.runTransaction(async (tx) => {
          tx.update(bookingRef, {
            refundedAmountPaise: split.totalRefundedPaise,
            paymentStatus: "REFUNDED",
            bookingStatus: "REFUNDED",
            payoutEligibilityStatus: "REVERSED",
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.update(paymentRef, {
            refundedAmountPaise: split.totalRefundedPaise,
            paymentStatus: "REFUNDED",
            providerSettlementStatus: "REFUNDED",
            razorpayRefundId: gatewayRefundId,
            updatedAt: FieldValue.serverTimestamp(),
          });
          tx.set(db.collection("ledger_entries").doc(`led_refund_${bookingId}_cancel`.slice(0, 128)), {
            entryType: "REFUND_COMPLETED",
            direction: "DEBIT",
            entryStatus: "SETTLED",
            bookingId,
            paymentId: `pay_${bookingId}`,
            customerUid: txOutcome.booking.customerUid,
            expertUid: txOutcome.booking.expertUid,
            amountPaise: txOutcome.refundPaise,
            currency: "INR",
            idempotencyKey: `led_refund_${bookingId}_cancel`,
            description: `Cancellation refund (${txOutcome.policyNote})`.slice(0, 300),
            createdAt: FieldValue.serverTimestamp(),
          });
        });
        refundStatus = "REFUNDED";
      } else {
        await paymentRef.update({
          razorpayRefundId: gatewayRefundId,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
    } catch {
      // Leave REFUND_PENDING so ops/webhook can safely retry without losing cancellation state
      refundStatus = "REFUND_PENDING";
    }
  } else if (txOutcome.refundPaise > 0 && txOutcome.payment?.testMode === true) {
    // Sandbox cancellation completes test reversal cleanly
    const split = computeProportionalRefundSplit(txOutcome.booking, txOutcome.refundPaise);
    await bookingRef.update({
      refundedAmountPaise: split.totalRefundedPaise,
      paymentStatus: "REFUNDED",
      bookingStatus: "REFUNDED",
      payoutEligibilityStatus: "REVERSED",
      updatedAt: FieldValue.serverTimestamp(),
    });
    if (txOutcome.payment) {
      await paymentRef.update({
        refundedAmountPaise: split.totalRefundedPaise,
        paymentStatus: "REFUNDED",
        providerSettlementStatus: "REFUNDED",
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
    refundStatus = "REFUNDED";
  }

  if (googleWorkspaceClient?.isConfigured() && txOutcome.booking.googleCalendarEventId) {
    await googleWorkspaceClient.cancelCalendarEvent(txOutcome.booking.googleCalendarEventId).catch(() => {});
  }

  await dispatchNotificationsWithPush({
    db,
    messaging,
    items: [
      {
        recipientUid: txOutcome.otherUid,
        title: "Consultation Cancelled",
        message: `${txOutcome.booking.serviceTitle} was cancelled. ${txOutcome.policyNote}.`,
        eventType: "BOOKING_CANCELLED",
        relatedBookingId: bookingId,
        dedupKey: `cancel_${bookingId}_${txOutcome.otherUid}`,
      },
    ],
  });

  return {
    bookingId,
    bookingStatus: refundStatus === "REFUNDED" ? "REFUNDED" : "CANCELLED",
    refundStatus,
    refundPaise: txOutcome.refundPaise,
    gatewayRefundId,
    policyNote: txOutcome.policyNote,
  };
}

async function syncCalendarMetadata({ db, bookingId, actor, googleWorkspaceClient = null }) {
  assertValidId(bookingId, "bookingId");
  const bookingRef = db.collection("bookings").doc(bookingId);

  const preflight = await db.runTransaction(async (tx) => {
    const snap = await tx.get(bookingRef);
    if (!snap.exists) {
      throw new HttpAppError(404, "Booking not found");
    }
    const booking = { id: snap.id, ...snap.data() };
    const isParticipant = booking.customerUid === actor.uid || booking.expertUid === actor.uid;
    if (!actor.isAdmin && !isParticipant) {
      throw new HttpAppError(403, "Forbidden: only booking participants or admin can access Calendar metadata");
    }
    if (!["CONFIRMED", "SCHEDULED"].includes(booking.bookingStatus)) {
      throw new HttpAppError(409, `Calendar sync requires CONFIRMED or SCHEDULED booking (current=${booking.bookingStatus})`);
    }
    if (!["SUCCESS", "PARTIALLY_REFUNDED"].includes(booking.paymentStatus)) {
      throw new HttpAppError(409, "Calendar sync requires captured payment");
    }

    if (booking.googleCalendarEventId && booking.meetingUrl && booking.calendarSyncStatus === "SYNCED") {
      return {
        alreadySynced: true,
        response: {
          syncStatus: "SYNCED",
          googleCalendarEventId: booking.googleCalendarEventId,
          meetingUrl: booking.meetingUrl,
          googleMeetSpaceId: booking.googleMeetSpaceId || "",
          googleMeetCode: booking.googleMeetCode || "",
          meetingAccessMode: booking.meetingAccessMode || "TRUSTED_INVITEES_ONLY",
        },
      };
    }

    const calendarPayload = buildCalendarEventPayload(booking);
    if (!googleWorkspaceClient || !googleWorkspaceClient.isConfigured()) {
      const nextSyncStatus = booking.googleCalendarEventId ? booking.calendarSyncStatus || "SYNCED" : "PENDING_BACKEND_SYNC";
      tx.update(bookingRef, {
        calendarSyncStatus: nextSyncStatus,
        conferenceRequestId: booking.conferenceRequestId || `conf_${bookingId}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
      return {
        alreadySynced: true,
        response: {
          syncStatus: nextSyncStatus,
          googleCalendarEventId: booking.googleCalendarEventId || "",
          meetingUrl: booking.meetingUrl || "",
          googleMeetSpaceId: booking.googleMeetSpaceId || "",
          googleMeetCode: booking.googleMeetCode || "",
          calendarPayload,
        },
      };
    }

    return { alreadySynced: false, booking, calendarPayload };
  });

  if (preflight.alreadySynced) {
    return preflight.response;
  }

  try {
    const liveEvent = await googleWorkspaceClient.createCalendarMeetEvent(preflight.booking);
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(bookingRef);
      if (!snap.exists) return;
      const cur = snap.data();
      if (!["CONFIRMED", "SCHEDULED"].includes(cur.bookingStatus)) return;
      tx.update(bookingRef, {
        googleCalendarEventId: liveEvent.googleCalendarEventId,
        meetingUrl: liveEvent.meetingUrl,
        googleMeetSpaceId: liveEvent.googleMeetSpaceId,
        googleMeetCode: liveEvent.googleMeetCode,
        meetingAccessMode: liveEvent.meetingAccessMode,
        calendarSyncStatus: "SYNCED",
        bookingStatus: "SCHEDULED",
        conferenceRequestId: preflight.booking.conferenceRequestId || `conf_${bookingId}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
    });

    return liveEvent;
  } catch (err) {
    await bookingRef.update({
      calendarSyncStatus: "SYNC_FAILED",
      updatedAt: FieldValue.serverTimestamp(),
    });
    throw new HttpAppError(502, `Google Calendar/Meet provisioning failed: ${err?.message || err}`);
  }
}

async function syncMeetAttendanceSafe({ db, bookingId, actor, googleWorkspaceClient = null }) {
  assertValidId(bookingId, "bookingId");
  const bookingRef = db.collection("bookings").doc(bookingId);
  const attendanceRef = db.collection("attendance_records").doc(bookingId);
  const settingsRef = db.collection("platform_settings").doc("config");

  const preflight = await db.runTransaction(async (tx) => {
    const [bookingSnap, settingsSnap] = await Promise.all([tx.get(bookingRef), tx.get(settingsRef)]);
    if (!bookingSnap.exists) {
      throw new HttpAppError(404, "Booking not found");
    }
    const booking = bookingSnap.data();
    const isParticipant = booking.customerUid === actor.uid || booking.expertUid === actor.uid;
    if (!actor.isAdmin && !isParticipant) {
      throw new HttpAppError(403, "Forbidden: only booking participants or admin can inspect Meet attendance");
    }
    if (!["CONFIRMED", "SCHEDULED", "IN_PROGRESS", "ATTENDANCE_PENDING", "COMPLETION_PENDING", "COMPLETED"].includes(booking.bookingStatus)) {
      throw new HttpAppError(409, `Attendance sync unavailable for booking status ${booking.bookingStatus}`);
    }

    if (booking.attendanceVerificationSource === "ADMIN_MANUAL_REVIEW" && ["VERIFIED", "FAILED"].includes(booking.attendanceStatus)) {
      return {
        done: true,
        result: {
          status: booking.attendanceStatus,
          verificationSource: booking.attendanceVerificationSource,
          customerMinutes: Number(booking.customerJoinedMinutes || 0),
          expertMinutes: Number(booking.expertJoinedMinutes || 0),
          overlapMinutes: Number(booking.overlapMinutes || 0),
          minimumRequiredMinutes: 10,
          payoutHoldRecommended: booking.attendanceStatus !== "VERIFIED",
          summary: booking.attendanceNotes || "Admin attendance decision preserved.",
        },
      };
    }

    const settings = settingsSnap.exists ? settingsSnap.data() : {};
    const minFloorMinutes = Number(settings.minMeetAttendanceMinutes ?? 10);
    const minRatio = Number(settings.minMeetAttendanceRatioBps ?? 6500) / 10000;

    return { done: false, booking, minFloorMinutes, minRatio };
  });

  if (preflight.done) {
    return preflight.result;
  }

  let liveOutcome = null;
  if (googleWorkspaceClient && googleWorkspaceClient.isConfigured()) {
    liveOutcome = await googleWorkspaceClient
      .fetchMeetConferenceAttendance({
        booking: preflight.booking,
        minFloorMinutes: preflight.minFloorMinutes,
        minRatio: preflight.minRatio,
      })
      .catch((err) => ({
        available: false,
        reason: `Google Meet REST API v2 lookup failed: ${err?.message || err}`,
      }));
  }

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(bookingRef);
    if (!snap.exists) throw new HttpAppError(404, "Booking not found");
    const bk = snap.data();

    if (liveOutcome && liveOutcome.available && liveOutcome.evaluation) {
      const ev = liveOutcome.evaluation;
      tx.update(bookingRef, {
        attendanceStatus: ev.status,
        attendanceVerificationSource: ev.verificationSource,
        customerJoinedMinutes: ev.customerMinutes,
        expertJoinedMinutes: ev.expertMinutes,
        overlapMinutes: ev.overlapMinutes,
        attendanceNotes: ev.summary.slice(0, 500),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(
        attendanceRef,
        {
          bookingId,
          customerUid: bk.customerUid,
          expertUid: bk.expertUid,
          expectedCustomerEmail: bk.verifiedCustomerGoogleEmail || "",
          expectedExpertEmail: bk.verifiedExpertGoogleEmail || "",
          conferenceRecordId: liveOutcome.conferenceRecordId || "",
          googleMeetSpaceId: bk.googleMeetSpaceId || "",
          verificationSource: ev.verificationSource,
          attendanceStatus: ev.status,
          customerDurationMinutes: ev.customerMinutes,
          expertDurationMinutes: ev.expertMinutes,
          overlapMinutes: ev.overlapMinutes,
          minimumRequiredMinutes: ev.minimumRequiredMinutes,
          unmatchedParticipantDetails: ev.unmatchedParticipantDetails || "",
          evaluatorSummary: ev.summary.slice(0, 500),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
      return ev;
    }

    const summary =
      liveOutcome?.reason ||
      "Google Meet REST API v2 retrieval not yet enabled on backend; client session evidence ignored. Marked for manual review.";

    tx.update(bookingRef, {
      attendanceStatus: "MANUAL_REVIEW",
      attendanceVerificationSource: "NONE",
      attendanceNotes: summary.slice(0, 500),
      updatedAt: FieldValue.serverTimestamp(),
    });
    tx.set(
      attendanceRef,
      {
        bookingId,
        customerUid: bk.customerUid,
        expertUid: bk.expertUid,
        expectedCustomerEmail: bk.verifiedCustomerGoogleEmail || "",
        expectedExpertEmail: bk.verifiedExpertGoogleEmail || "",
        verificationSource: "NONE",
        attendanceStatus: "MANUAL_REVIEW",
        customerDurationMinutes: 0,
        expertDurationMinutes: 0,
        overlapMinutes: 0,
        minimumRequiredMinutes: preflight.minFloorMinutes,
        evaluatorSummary: summary.slice(0, 500),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return {
      status: "MANUAL_REVIEW",
      verificationSource: "NONE",
      customerMinutes: 0,
      expertMinutes: 0,
      overlapMinutes: 0,
      minimumRequiredMinutes: preflight.minFloorMinutes,
      payoutHoldRecommended: true,
      summary,
    };
  });
}

module.exports = {
  HttpAppError,
  assertValidId,
  sanitizeDocId,
  createBookingWithHoldTransaction,
  rescheduleBookingTransaction,
  cancelBookingTransaction,
  syncCalendarMetadata,
  syncMeetAttendanceSafe,
};
