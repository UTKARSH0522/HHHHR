const crypto = require("node:crypto");
const { FieldValue } = require("firebase-admin/firestore");

const BOOKING_EVENT_TYPES = new Set([
  "BOOKING_CONFIRMED",
  "NEW_BOOKING",
  "BOOKING_RESCHEDULED",
  "BOOKING_CANCELLED",
  "MEETING_LINK_ADDED",
  "BOOKING_COMPLETED",
  "COMPLETION_CONFIRMATION_PENDING",
]);

const PAYMENT_EVENT_TYPES = new Set([
  "PAYMENT_FAILED",
  "REFUND_INITIATED",
  "REFUND_PROCESSED",
  "REFUND_FAILED",
  "DISPUTE_OPENED",
  "DISPUTE_RESOLVED",
  "PAYOUT_UPDATED",
]);

const REVIEW_EVENT_TYPES = new Set(["NEW_REVIEW"]);

function sanitizeDocId(raw) {
  const cleaned = String(raw || "").replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 128);
  if (cleaned.length >= 1 && /^[a-zA-Z0-9_-]{1,128}$/.test(cleaned)) {
    return cleaned;
  }
  return crypto.createHash("sha256").update(String(raw || "")).digest("hex").slice(0, 64);
}

const LOCKSCREEN_SAFE_TEMPLATES = {
  BOOKING_CONFIRMED: {
    title: "Consultation Confirmed",
    body: "Your HRConnect consultation has been confirmed. Open the app for session details.",
  },
  NEW_BOOKING: {
    title: "New Consultation Booking",
    body: "You have a new confirmed HRConnect consultation. Open the app to view schedule details.",
  },
  BOOKING_RESCHEDULED: {
    title: "Consultation Rescheduled",
    body: "A scheduled HRConnect consultation time was updated. Open the app to confirm.",
  },
  BOOKING_CANCELLED: {
    title: "Consultation Cancelled",
    body: "An HRConnect consultation was cancelled. Open the app for refund status.",
  },
  MEETING_LINK_ADDED: {
    title: "Meeting Link Ready",
    body: "Your Google Meet consultation link is ready in HRConnect.",
  },
  BOOKING_COMPLETED: {
    title: "Consultation Completed",
    body: "Your HRConnect consultation is marked completed. Open the app to leave a review.",
  },
  COMPLETION_CONFIRMATION_PENDING: {
    title: "Confirm Consultation Completion",
    body: "Please confirm session completion inside HRConnect.",
  },
  PAYMENT_FAILED: {
    title: "Payment Update",
    body: "Your recent HRConnect payment attempt needs attention. Open the app to retry.",
  },
  REFUND_INITIATED: {
    title: "Refund Initiated",
    body: "A refund update is available for your HRConnect booking.",
  },
  REFUND_PROCESSED: {
    title: "Refund Processed",
    body: "Your HRConnect refund has been processed. Open the app for reference details.",
  },
  REFUND_FAILED: {
    title: "Refund Update",
    body: "There is a refund status update on your HRConnect account.",
  },
  PAYOUT_UPDATED: {
    title: "Payout Update",
    body: "Your HRConnect expert payout status was updated. Open the app for details.",
  },
};

/**
 * Uses lock-screen-safe generic templates for push notifications and strips emails/IDs
 * so private topics, emails, and transaction/bank identifiers never appear on OS lock screens.
 */
function buildSafePushPayload({ title, message, eventType, relatedBookingId }) {
  const evt = String(eventType || "GENERAL");
  const template = LOCKSCREEN_SAFE_TEMPLATES[evt];
  const cleanTitle = String(template?.title || title || "HRConnect Update").slice(0, 80);
  const rawBody = template?.body || String(message || "You have a new update in HRConnect.");
  const cleanBody = rawBody
    .replace(/\b(pay|order|rfnd|pout|trf)_[A-Za-z0-9]+\b/g, "[ref]")
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[account]")
    .slice(0, 160);

  return {
    notification: {
      title: cleanTitle,
      body: cleanBody,
    },
    data: {
      eventType: evt,
      relatedBookingId: String(relatedBookingId || ""),
    },
  };
}

async function shouldSendPushForUser(db, recipientUid, eventType) {
  try {
    const privSnap = await db
      .collection("users")
      .doc(recipientUid)
      .collection("private")
      .doc("info")
      .get();
    if (!privSnap.exists) return true;
    const prefs = privSnap.data() || {};
    if (BOOKING_EVENT_TYPES.has(eventType)) return prefs.notifyBookings !== false;
    if (PAYMENT_EVENT_TYPES.has(eventType)) return prefs.notifyPayments !== false;
    if (REVIEW_EVENT_TYPES.has(eventType)) return prefs.notifyReviews !== false;
    return true;
  } catch {
    return true;
  }
}

async function dispatchNotificationsWithPush({ db, messaging = null, items = [] }) {
  const results = [];
  for (const item of items) {
    if (!item || !item.recipientUid || !item.title || !item.message || !item.eventType) continue;

    // 1. Deduplicated in-app Firestore notification write
    const notifRef = item.dedupKey
      ? db.collection("notifications").doc(`notif_${sanitizeDocId(item.dedupKey)}`.slice(0, 128))
      : db.collection("notifications").doc();

    if (item.dedupKey) {
      const existing = await notifRef.get();
      if (existing.exists) {
        results.push({ id: notifRef.id, deduplicated: true, pushedCount: 0 });
        continue;
      }
    }

    await notifRef.set({
      recipientUid: String(item.recipientUid).slice(0, 128),
      title: String(item.title).slice(0, 160),
      message: String(item.message).slice(0, 500),
      eventType: String(item.eventType).slice(0, 60),
      relatedBookingId: item.relatedBookingId ? String(item.relatedBookingId).slice(0, 128) : "",
      read: false,
      createdAt: FieldValue.serverTimestamp(),
    });

    // 2. FCM Push delivery honoring user notification preferences
    let pushedCount = 0;
    if (messaging && typeof messaging.sendEachForMulticast === "function") {
      const allowed = await shouldSendPushForUser(db, item.recipientUid, item.eventType);
      if (allowed) {
        const tokenSnap = await db
          .collection("users")
          .doc(item.recipientUid)
          .collection("fcm_tokens")
          .get();
        const activeDocs = tokenSnap.docs.filter(
          (d) => d.data()?.token && d.data()?.enabled !== false && d.data()?.active !== false
        );
        const tokens = activeDocs.map((d) => String(d.data().token));
        if (tokens.length > 0) {
          const push = buildSafePushPayload(item);
          const batchResp = await messaging.sendEachForMulticast({
            tokens,
            notification: push.notification,
            data: push.data,
            android: {
              priority: "high",
              notification: {
                channelId: "hrconnect_updates",
              },
            },
          });
          pushedCount = Number(batchResp?.successCount || 0);

          // Mark unregistered / invalid tokens inactive cleanly
          const responses = Array.isArray(batchResp?.responses) ? batchResp.responses : [];
          for (let idx = 0; idx < responses.length; idx++) {
            const r = responses[idx];
            const errCode = r?.error?.code || "";
            if (
              !r?.success &&
              (errCode === "messaging/registration-token-not-registered" ||
                errCode === "messaging/invalid-registration-token")
            ) {
              await activeDocs[idx].ref
                .update({
                  active: false,
                  enabled: false,
                  invalidatedAt: FieldValue.serverTimestamp(),
                })
                .catch(() => {});
            }
          }
        }
      }
    }

    results.push({ id: notifRef.id, deduplicated: false, pushedCount });
  }
  return results;
}

module.exports = {
  buildSafePushPayload,
  shouldSendPushForUser,
  dispatchNotificationsWithPush,
};
