const { test, describe, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const {
  calculateFinancialSplit,
  computeProportionalRefundSplit,
  computeRazorpayPaymentSignature,
  verifyRazorpayPaymentSignature,
  computeRazorpayWebhookSignature,
  verifyRazorpayWebhookSignature,
  evaluateBookingEligibilityForServer,
} = require("./src/paymentService");
const {
  buildCalendarEventPayload,
  evaluateMeetAttendance,
} = require("./src/googleWorkspaceService");
const { getFirebaseServices, DEFAULT_DATABASE_ID } = require("./src/firebaseAdmin");
const { createAppServer } = require("./src/server");

const RZP_KEY_ID = "rzp_test_phase1_key";
const RZP_KEY_SECRET = "rzp_test_phase1_secret_only";
const RZP_WEBHOOK_SECRET = "whsec_phase1_test_only";

// ============================================================================
// PART 1: Pure Financial & Google Workspace Domain Unit Tests
// ============================================================================

test("Server-side commission & financial split (15% marketplace vs 10% referral)", () => {
  const std = calculateFinancialSplit({ amountPaise: 100000, commissionRateBps: 1500, gatewayFeeBps: 200, tdsBps: 0 });
  assert.equal(std.commissionPaise, 15000);
  assert.equal(std.expertEarningPaise, 85000);
  assert.equal(std.gatewayFeePaise, 2000);

  const ref = calculateFinancialSplit({ amountPaise: 100000, commissionRateBps: 1000, gatewayFeeBps: 200, tdsBps: 0 });
  assert.equal(ref.commissionPaise, 10000);
  assert.equal(ref.expertEarningPaise, 90000);
});

test("Razorpay HMAC payment & webhook signature verification [TEST SECRET]", () => {
  const orderId = "order_12345";
  const paymentId = "pay_98765";
  const validSig = computeRazorpayPaymentSignature({ orderId, paymentId, keySecret: RZP_KEY_SECRET });

  assert.equal(verifyRazorpayPaymentSignature({ orderId, paymentId, signature: validSig, keySecret: RZP_KEY_SECRET }), true);
  assert.equal(verifyRazorpayPaymentSignature({ orderId, paymentId: "pay_tampered", signature: validSig, keySecret: RZP_KEY_SECRET }), false);
  assert.equal(verifyRazorpayPaymentSignature({ orderId, paymentId, signature: "0000", keySecret: RZP_KEY_SECRET }), false);

  const rawBody = JSON.stringify({ event: "payment.captured", payload: { payment: { entity: { amount: 100000 } } } });
  const whSig = computeRazorpayWebhookSignature({ rawBody, webhookSecret: RZP_WEBHOOK_SECRET });
  assert.equal(verifyRazorpayWebhookSignature({ rawBody, signatureHeader: whSig, webhookSecret: RZP_WEBHOOK_SECRET }), true);
  assert.equal(verifyRazorpayWebhookSignature({ rawBody: rawBody + " ", signatureHeader: whSig, webhookSecret: RZP_WEBHOOK_SECRET }), false);
});

test("Google Calendar v3 & Meet REST API payload + midnight safety", () => {
  const payload = buildCalendarEventPayload({
    id: "bk_meet_1",
    serviceTitle: "POSH Compliance Review",
    customerName: "Alice",
    verifiedCustomerGoogleEmail: "alice@gmail.com",
    expertName: "Vikram",
    verifiedExpertGoogleEmail: "vikram@gmail.com",
    dateIso: "2026-12-31",
    startMinute: 1380,
    endMinute: 1440,
    timezone: "Asia/Kolkata",
    topic: "IC constitution",
  });
  assert.equal(payload.conferenceDataVersion, 1);
  assert.equal(payload.sendUpdates, "all");
  assert.equal(payload.requestBody.start.dateTime, "2026-12-31T23:00:00+05:30");
  assert.equal(payload.requestBody.end.dateTime, "2027-01-01T00:00:00+05:30");
  assert.equal(payload.requestBody.meetSpaceConfig.accessType, "TRUSTED");
  assert.equal(payload.requestBody.guestsCanInviteOthers, false);
});

test("Google Meet attendance algorithm scenarios A through F", () => {
  const base = 1_700_000_000_000;
  const min = 60_000;

  const scenA = evaluateMeetAttendance({
    participantSessions: [
      { email: "alice@gmail.com", startMs: base, endMs: base + 12 * min },
      { email: "alice@gmail.com", startMs: base + 14 * min, endMs: base + 26 * min },
      { email: "vikram@gmail.com", startMs: base + 1 * min, endMs: base + 25 * min },
    ],
    expectedCustomerEmail: "alice@gmail.com",
    expectedExpertEmail: "vikram@gmail.com",
    scheduledDurationMinutes: 30,
  });
  assert.equal(scenA.status, "VERIFIED");
  assert.equal(scenA.overlapMinutes, 22);

  const scenC = evaluateMeetAttendance({
    participantSessions: [{ email: "alice@gmail.com", startMs: base, endMs: base + 15 * min }],
    expectedCustomerEmail: "alice@gmail.com",
    expectedExpertEmail: "vikram@gmail.com",
    scheduledDurationMinutes: 30,
  });
  assert.equal(scenC.status, "FAILED");

  const scenE = evaluateMeetAttendance({
    participantSessions: [
      { email: "vikram@gmail.com", startMs: base, endMs: base + 20 * min },
      { email: "stranger@gmail.com", startMs: base, endMs: base + 20 * min },
    ],
    expectedCustomerEmail: "alice@gmail.com",
    expectedExpertEmail: "vikram@gmail.com",
    scheduledDurationMinutes: 30,
  });
  assert.equal(scenE.status, "MISMATCH");
});

test("Proportional refund split & 24h payout dispute window eligibility helper", () => {
  const bk = {
    amountPaise: 100000,
    commissionPaise: 15000,
    expertEarningPaise: 85000,
    paymentStatus: "SUCCESS",
    providerSettlementStatus: "SETTLED",
    bookingStatus: "COMPLETED",
    attendanceStatus: "VERIFIED",
    customerCompletedConfirmed: true,
    expertCompletedConfirmed: true,
    completedAtEpochMs: 1_000_000,
    disputeWindowEndsAtEpochMs: 1_000_000 + 86_400_000,
    refundedAmountPaise: 0,
  };

  assert.equal(evaluateBookingEligibilityForServer(bk, 1_000_000 + 3_600_000).eligible, false);
  assert.equal(evaluateBookingEligibilityForServer(bk, 1_000_000 + 86_400_001).eligible, true);

  const partial = computeProportionalRefundSplit(bk, 50000);
  assert.equal(partial.commissionReversalPaise, 7500);
  assert.equal(partial.expertReductionPaise, 42500);
  assert.equal(partial.isFullRefund, false);
});

// ============================================================================
// PART 2: Firestore Emulator Integration Suite (All 15 Phase 1 Scenarios)
// ============================================================================

const EMULATOR_AVAILABLE = Boolean(process.env.FIRESTORE_EMULATOR_HOST);

function createTestAuthVerifier() {
  const tokenDirectory = new Map([
    ["tok_customer_a", { uid: "cust_a", email: "alice@gmail.com", email_verified: true }],
    ["tok_customer_b", { uid: "cust_b", email: "bob@gmail.com", email_verified: true }],
    ["tok_expert_1", { uid: "exp_1", email: "vikram@gmail.com", email_verified: true }],
    ["tok_expert_2", { uid: "exp_2", email: "neha@gmail.com", email_verified: true }],
    ["tok_admin_doc", { uid: "admin_uid_1", email: "ops@hrconnect.in", email_verified: true }],
    ["tok_admin_allowlist", { uid: "admin_uid_2", email: "utkarsh.work.contact@gmail.com", email_verified: true }],
    ["tok_unverified_admin_email", { uid: "spoof_uid", email: "utkarsh.work.contact@gmail.com", email_verified: false }],
  ]);

  return {
    async verifyIdToken(token, checkRevoked) {
      assert.equal(checkRevoked, true);
      if (token === "tok_expired") {
        const err = new Error("Firebase ID token has expired");
        err.code = "auth/id-token-expired";
        throw err;
      }
      if (token === "tok_revoked") {
        const err = new Error("Firebase ID token has been revoked");
        err.code = "auth/id-token-revoked";
        throw err;
      }
      const claims = tokenDirectory.get(token);
      if (!claims) {
        const err = new Error("Decoding Firebase ID token failed");
        err.code = "auth/argument-error";
        throw err;
      }
      return claims;
    },
  };
}

async function clearFirestoreDatabase(projectId, databaseId) {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  const url = `http://${host}/emulator/v1/projects/${projectId}/databases/${databaseId}/documents`;
  const res = await fetch(url, { method: "DELETE" });
  assert.equal(res.ok, true, `Failed to clear Firestore emulator database: ${res.status}`);
}

async function startTestHttpServer({
  db,
  authService,
  messaging = null,
  adminEmails,
  allowedOrigins,
  nodeEnv = "test",
  requireLiveRazorpay = false,
  razorpayKeyId = RZP_KEY_ID,
  razorpayKeySecret = RZP_KEY_SECRET,
  razorpayWebhookSecret = RZP_WEBHOOK_SECRET,
  razorpayXAccountNumber = "",
  payoutMode,
  enableRazorpayApi = false,
  razorpayFetch,
  googleFetch,
  googleWorkspaceOptions,
  rateLimitMaxPerMin = 120,
  sensitiveRateLimitMaxPerMin = 60,
} = {}) {
  const server = createAppServer({
    db,
    authService,
    messaging,
    projectId: "demo-no-project",
    databaseId: DEFAULT_DATABASE_ID,
    adminEmails,
    allowedOrigins,
    nodeEnv,
    requireLiveRazorpay,
    razorpayKeyId,
    razorpayKeySecret,
    razorpayWebhookSecret,
    razorpayXAccountNumber,
    payoutMode,
    enableRazorpayApi: Boolean(enableRazorpayApi || razorpayFetch),
    razorpayFetch,
    googleFetch,
    googleWorkspaceOptions,
    rateLimitMaxPerMin,
    sensitiveRateLimitMaxPerMin,
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  return {
    server,
    baseUrl: `http://127.0.0.1:${port}`,
    async close() {
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

async function postJson(url, body, token) {
  const headers = { "Content-Type": "application/json" };
  if (token !== undefined) {
    headers.Authorization = token;
  }
  const res = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  const data = await res.json();
  return { status: res.status, data };
}

describe("Phase 1 Backend Foundation — Firestore Emulator Integration", { skip: !EMULATOR_AVAILABLE && "Set FIRESTORE_EMULATOR_HOST via firebase emulators:exec to run" }, () => {
  let db;
  let authService;
  let serverHandle;

  before(async () => {
    const services = getFirebaseServices({
      projectId: "demo-no-project",
      databaseId: DEFAULT_DATABASE_ID,
      appName: "hrconnect-test-emulator",
    });
    db = services.db;
    authService = createTestAuthVerifier();
  });

  beforeEach(async () => {
    if (serverHandle) {
      await serverHandle.close();
      serverHandle = null;
    }
    await clearFirestoreDatabase("demo-no-project", DEFAULT_DATABASE_ID);
    await db.collection("admins").doc("admin_uid_1").set({
      email: "ops@hrconnect.in",
      active: true,
    });
    await db.collection("platform_settings").doc("config").set({
      platformCommissionBps: 1500,
      referralCommissionBps: 1000,
      gatewayFeeBps: 200,
      tdsBps: 0,
      disputeWindowHours: 24,
    });
    await db.collection("services").doc("svc_posh").set({
      expertUid: "exp_1",
      title: "POSH Compliance Audit",
      pricePaise: 100000,
      durationMinutes: 30,
      active: true,
    });
    serverHandle = await startTestHttpServer({ db, authService });
  });

  after(async () => {
    if (serverHandle) {
      await serverHandle.close();
    }
  });

  async function seedBooking(id = "bk_1", overrides = {}) {
    const doc = {
      id,
      customerUid: "cust_a",
      customerName: "Alice",
      verifiedCustomerGoogleEmail: "alice@gmail.com",
      expertUid: "exp_1",
      expertName: "Vikram",
      verifiedExpertGoogleEmail: "vikram@gmail.com",
      serviceId: "svc_posh",
      serviceTitle: "POSH Compliance Audit",
      dateIso: "2026-12-15",
      startMinute: 600,
      endMinute: 630,
      durationMinutes: 30,
      timezone: "Asia/Kolkata",
      topic: "POSH audit",
      slotKey: `exp_1_2026-12-15_600`,
      amountPaise: 100000,
      commissionRateBps: 1500,
      commissionPaise: 15000,
      gatewayFeePaise: 2000,
      taxDeductionPaise: 0,
      expertEarningPaise: 85000,
      refundedAmountPaise: 0,
      bookingStatus: "PENDING_PAYMENT",
      paymentStatus: "CREATED",
      providerSettlementStatus: "PENDING_CAPTURE",
      payoutEligibilityStatus: "NOT_ELIGIBLE",
      attendanceStatus: "PENDING",
      disputeStatus: "NONE",
      customerCompletedConfirmed: false,
      expertCompletedConfirmed: false,
      completedAtEpochMs: 0,
      disputeWindowEndsAtEpochMs: 0,
      holdExpiresAtEpochMs: Date.now() + 600_000,
      ...overrides,
    };
    await db.collection("bookings").doc(id).set(doc);
    await db.collection("slot_locks").doc(doc.slotKey).set({
      bookingId: id,
      expertUid: doc.expertUid,
      bookingStatus: doc.bookingStatus,
    });
    return doc;
  }

  test("1. Unauthenticated access rejection across all protected routes", async () => {
    await seedBooking("bk_unauth");
    const routes = [
      ["/api/payments/create-order", { bookingId: "bk_unauth" }],
      ["/api/payments/verify", { bookingId: "bk_unauth", razorpayOrderId: "o", razorpayPaymentId: "p", razorpaySignature: "s" }],
      ["/api/bookings/sync-calendar", { bookingId: "bk_unauth" }],
      ["/api/bookings/sync-meet-attendance", { bookingId: "bk_unauth" }],
      ["/api/refunds/initiate", { bookingId: "bk_unauth", refundAmountPaise: 1000 }],
      ["/api/payouts/process", { payoutRequestId: "pr_1" }],
    ];
    for (const [path, body] of routes) {
      const missing = await postJson(`${serverHandle.baseUrl}${path}`, body);
      assert.equal(missing.status, 401, `${path} must reject missing auth`);

      const malformed = await postJson(`${serverHandle.baseUrl}${path}`, body, "Basic abc123");
      assert.equal(malformed.status, 401, `${path} must reject non-Bearer auth`);
    }
  });

  test("2. Invalid Firebase token rejection", async () => {
    await seedBooking("bk_badtok");
    const res = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_badtok" },
      "Bearer invalid.jwt.token_value"
    );
    assert.equal(res.status, 401);
    assert.match(res.data.error, /Invalid Firebase ID token/i);
  });

  test("3. Expired and revoked Firebase token rejection", async () => {
    await seedBooking("bk_exptok");
    const expiredRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_exptok" },
      "Bearer tok_expired"
    );
    assert.equal(expiredRes.status, 401);
    assert.match(expiredRes.data.error, /expired/i);

    const revokedRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_exptok" },
      "Bearer tok_revoked"
    );
    assert.equal(revokedRes.status, 401);
    assert.match(revokedRes.data.error, /revoked/i);
  });

  test("4. Customer cannot access or mutate another customer's booking (ignores body customerUid spoof)", async () => {
    await seedBooking("bk_cross_cust", { bookingStatus: "CONFIRMED", paymentStatus: "SUCCESS" });

    const orderAttempt = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_cross_cust", customerUid: "cust_b" },
      "Bearer tok_customer_b"
    );
    assert.equal(orderAttempt.status, 403);

    const sig = computeRazorpayPaymentSignature({ orderId: "order_bk_cross", paymentId: "pay_x", keySecret: RZP_KEY_SECRET });
    const verifyAttempt = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      { bookingId: "bk_cross_cust", razorpayOrderId: "order_bk_cross", razorpayPaymentId: "pay_x", razorpaySignature: sig },
      "Bearer tok_customer_b"
    );
    assert.equal(verifyAttempt.status, 403);

    const calAttempt = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-calendar`,
      { bookingId: "bk_cross_cust" },
      "Bearer tok_customer_b"
    );
    assert.equal(calAttempt.status, 403);

    const meetAttempt = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-meet-attendance`,
      { bookingId: "bk_cross_cust" },
      "Bearer tok_customer_b"
    );
    assert.equal(meetAttempt.status, 403);
  });

  test("5. Cross-expert isolation & spoofed Meet attendance rejection", async () => {
    const completedMs = 1_700_000_000_000;
    await seedBooking("bk_exp1", {
      expertUid: "exp_1",
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      attendanceStatus: "VERIFIED",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });

    // Expert 2 cannot inspect Expert 1's Meet attendance and cannot spoof participantSessions
    const meetCross = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-meet-attendance`,
      { bookingId: "bk_exp1" },
      "Bearer tok_expert_2"
    );
    assert.equal(meetCross.status, 403);

    // Even booking participant sending forged participantSessions gets MANUAL_REVIEW / verificationSource=NONE
    const meetSpoof = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-meet-attendance`,
      {
        bookingId: "bk_exp1",
        participantSessions: [
          { email: "alice@gmail.com", startMs: completedMs, endMs: completedMs + 30 * 60_000 },
          { email: "vikram@gmail.com", startMs: completedMs, endMs: completedMs + 30 * 60_000 },
        ],
      },
      "Bearer tok_expert_1"
    );
    assert.equal(meetSpoof.status, 200);
    assert.equal(meetSpoof.data.status, "MANUAL_REVIEW");
    assert.equal(meetSpoof.data.verificationSource, "NONE");

    const bkSnap = await db.collection("bookings").doc("bk_exp1").get();
    assert.equal(bkSnap.data().attendanceStatus, "MANUAL_REVIEW");
    assert.equal(bkSnap.data().attendanceVerificationSource, "NONE");

    // Admin payout referencing a booking belonging to expert_1 under a payout for expert_2 is rejected with 403
    await db.collection("bookings").doc("bk_exp1").update({ attendanceStatus: "VERIFIED" });
    await db.collection("payout_requests").doc("pr_wrong_expert").set({
      expertUid: "exp_2",
      amountPaise: 85000,
      eligibleBookingIds: ["bk_exp1"],
      status: "PENDING",
    });
    const wrongExpPayout = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      { payoutRequestId: "pr_wrong_expert", nowEpochMs: completedMs + 86_400_001 },
      "Bearer tok_admin_doc"
    );
    assert.equal(wrongExpPayout.status, 403);
  });

  test("6 & 7. Unauthorized refund and payout attempts rejected with 403 (even with spoofed body claims or unverified email)", async () => {
    await seedBooking("bk_authz", { bookingStatus: "CONFIRMED", paymentStatus: "SUCCESS" });
    await db.collection("payout_requests").doc("pr_authz").set({
      expertUid: "exp_1",
      amountPaise: 85000,
      eligibleBookingIds: ["bk_authz"],
      status: "PENDING",
    });

    for (const tok of ["Bearer tok_customer_a", "Bearer tok_expert_1", "Bearer tok_unverified_admin_email"]) {
      const refRes = await postJson(
        `${serverHandle.baseUrl}/api/refunds/initiate`,
        { bookingId: "bk_authz", refundAmountPaise: 10000, isAdmin: true, role: "ADMIN" },
        tok
      );
      assert.equal(refRes.status, 403);

      const payRes = await postJson(
        `${serverHandle.baseUrl}/api/payouts/process`,
        { payoutRequestId: "pr_authz", isAdmin: true, role: "ADMIN" },
        tok
      );
      assert.equal(payRes.status, 403);
    }
  });

  test("8 & 14. Valid admin refund + payout operations maintain exact ledger consistency and audit logs", async () => {
    const completedMs = 1_700_000_000_000;
    await seedBooking("bk_full_flow");

    // 1. Customer creates order + verifies payment
    const orderRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_full_flow" },
      "Bearer tok_customer_a"
    );
    assert.equal(orderRes.status, 200);
    const sig = computeRazorpayPaymentSignature({
      orderId: orderRes.data.orderId,
      paymentId: "pay_live_001",
      keySecret: RZP_KEY_SECRET,
    });
    const verifyRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_full_flow",
        razorpayOrderId: orderRes.data.orderId,
        razorpayPaymentId: "pay_live_001",
        razorpaySignature: sig,
      },
      "Bearer tok_customer_a"
    );
    assert.equal(verifyRes.status, 200);

    // Complete booking + admin partial refund 50% (50000 paise)
    await db.collection("bookings").doc("bk_full_flow").update({
      bookingStatus: "COMPLETED",
      attendanceStatus: "VERIFIED",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });

    const partialRefund = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_full_flow", refundAmountPaise: 50000, reason: "50% SLA discount" },
      "Bearer tok_admin_doc"
    );
    assert.equal(partialRefund.status, 200);
    assert.equal(partialRefund.data.commissionReversalPaise, 7500);

    // Remaining payable is 85000 - 42500 = 42500 paise
    await db.collection("payout_requests").doc("pr_flow").set({
      expertUid: "exp_1",
      expertName: "Vikram",
      payoutDestination: "UPI:vikram@okaxis",
      amountPaise: 42500,
      eligibleBookingIds: ["bk_full_flow"],
      status: "PENDING",
    });

    const procRes = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      { payoutRequestId: "pr_flow", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
      "Bearer tok_admin_doc"
    );
    assert.equal(procRes.status, 200);
    assert.equal(procRes.data.status, "PROCESSING");

    const paidRes = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      {
        payoutRequestId: "pr_flow",
        newStatus: "PAID",
        payoutReference: "UTR99887766",
        nowEpochMs: completedMs + 86_400_001,
      },
      "Bearer tok_admin_doc"
    );
    assert.equal(paidRes.status, 200);
    assert.equal(paidRes.data.status, "PAID");

    // Verify all ledger entries in Firestore
    const ledgerSnap = await db.collection("ledger_entries").get();
    const byType = new Map();
    for (const d of ledgerSnap.docs) {
      const row = d.data();
      byType.set(row.entryType, (byType.get(row.entryType) || 0) + row.amountPaise);
    }
    assert.equal(byType.get("PAYMENT_CAPTURED"), 100000);
    assert.equal(byType.get("PLATFORM_COMMISSION"), 15000);
    assert.equal(byType.get("EXPERT_EARNING"), 85000);
    assert.equal(byType.get("GATEWAY_FEE"), 2000);
    assert.equal(byType.get("REFUND_COMPLETED"), 50000);
    assert.equal(byType.get("COMMISSION_REVERSAL"), 7500);
    assert.equal(byType.get("PAYOUT_INITIATED"), 42500);
    assert.equal(byType.get("PAYOUT_COMPLETED"), 42500);

    const auditSnap = await db.collection("audit_logs").get();
    assert.equal(auditSnap.size, 3); // 1 refund + 2 payout transitions
  });

  test("9, 10, 11. Persistence & duplicate webhook deduplication across backend server restart", async () => {
    await seedBooking("bk_restart");

    // Server Instance 1: Create order and process signed webhook
    const orderRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_restart" },
      "Bearer tok_customer_a"
    );
    assert.equal(orderRes.status, 200);

    const whPayload = {
      event_id: "evt_restart_unique_1",
      event: "payment.captured",
      payload: {
        payment: {
          entity: {
            id: "pay_wh_restart_1",
            order_id: orderRes.data.orderId,
            amount: 100000,
            currency: "INR",
            method: "upi",
            notes: { bookingId: "bk_restart" },
          },
        },
      },
    };
    const rawBody = JSON.stringify(whPayload);
    const whSig = computeRazorpayWebhookSignature({ rawBody, webhookSecret: RZP_WEBHOOK_SECRET });

    const wh1 = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-razorpay-signature": whSig,
        "x-razorpay-event-id": "evt_restart_unique_1",
      },
      body: rawBody,
    });
    assert.equal(wh1.status, 200);
    assert.equal((await wh1.json()).status, "PROCESSED");

    // Shut down Server Instance 1 completely and boot a brand-new Server Instance 2
    await serverHandle.close();
    serverHandle = await startTestHttpServer({ db, authService });

    // (9) Duplicate webhook replay on Server 2 is deduplicated via persisted webhook_events
    const whReplay = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-razorpay-signature": whSig,
        "x-razorpay-event-id": "evt_restart_unique_1",
      },
      body: rawBody,
    });
    assert.equal(whReplay.status, 200);
    assert.equal((await whReplay.json()).status, "DUPLICATE_IGNORED");

    // (10) Booking persisted CONFIRMED across restart -> sync-calendar succeeds immediately on Server 2
    const calRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-calendar`,
      { bookingId: "bk_restart" },
      "Bearer tok_customer_a"
    );
    assert.equal(calRes.status, 200);
    assert.equal(calRes.data.syncStatus, "PENDING_BACKEND_SYNC");
    assert.equal(calRes.data.calendarPayload.requestBody.conferenceData.createRequest.requestId, "conf_bk_restart");

    // (11) Payment persisted across restart -> verify same paymentId is idempotent; create-order is blocked with 409
    const orderAfter = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_restart" },
      "Bearer tok_customer_a"
    );
    assert.equal(orderAfter.status, 409);

    const samePaySig = computeRazorpayPaymentSignature({
      orderId: orderRes.data.orderId,
      paymentId: "pay_wh_restart_1",
      keySecret: RZP_KEY_SECRET,
    });
    const verifyReplay = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_restart",
        razorpayOrderId: orderRes.data.orderId,
        razorpayPaymentId: "pay_wh_restart_1",
        razorpaySignature: samePaySig,
      },
      "Bearer tok_customer_a"
    );
    assert.equal(verifyReplay.status, 200);
    assert.equal(verifyReplay.data.alreadyVerified, true);

    const ledgerSnap = await db.collection("ledger_entries").where("bookingId", "==", "bk_restart").get();
    assert.equal(ledgerSnap.size, 4); // PAYMENT_CAPTURED, PLATFORM_COMMISSION, EXPERT_EARNING, GATEWAY_FEE
  });

  test("12. Concurrent payout requests are atomically serialized with Firestore transactions", async () => {
    const completedMs = 1_700_000_000_000;
    await seedBooking("bk_conc", {
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      attendanceStatus: "VERIFIED",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });
    await db.collection("payout_requests").doc("pr_conc_1").set({
      expertUid: "exp_1",
      amountPaise: 85000,
      eligibleBookingIds: ["bk_conc"],
      status: "FAILED",
      retryCount: 1,
    });
    await db.collection("payout_requests").doc("pr_conc_2").set({
      expertUid: "exp_1",
      amountPaise: 85000,
      eligibleBookingIds: ["bk_conc"],
      status: "FAILED",
      retryCount: 1,
    });

    const [r1, r2] = await Promise.all([
      postJson(
        `${serverHandle.baseUrl}/api/payouts/process`,
        { payoutRequestId: "pr_conc_1", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
        "Bearer tok_admin_doc"
      ),
      postJson(
        `${serverHandle.baseUrl}/api/payouts/process`,
        { payoutRequestId: "pr_conc_2", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
        "Bearer tok_admin_doc"
      ),
    ]);

    const statuses = [r1.status, r2.status].sort((a, b) => a - b);
    assert.deepEqual(statuses, [200, 409]);

    const payInitEntries = await db.collection("ledger_entries").where("entryType", "==", "PAYOUT_INITIATED").get();
    assert.equal(payInitEntries.size, 1);
  });

  test("13. Duplicate payment verification with same vs different paymentId", async () => {
    await seedBooking("bk_dup_pay");
    const orderRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_dup_pay" },
      "Bearer tok_customer_a"
    );

    const sig1 = computeRazorpayPaymentSignature({
      orderId: orderRes.data.orderId,
      paymentId: "pay_first",
      keySecret: RZP_KEY_SECRET,
    });
    const first = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_dup_pay",
        razorpayOrderId: orderRes.data.orderId,
        razorpayPaymentId: "pay_first",
        razorpaySignature: sig1,
      },
      "Bearer tok_customer_a"
    );
    assert.equal(first.status, 200);

    // Same paymentId retry -> idempotent 200
    const replay = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_dup_pay",
        razorpayOrderId: orderRes.data.orderId,
        razorpayPaymentId: "pay_first",
        razorpaySignature: sig1,
      },
      "Bearer tok_customer_a"
    );
    assert.equal(replay.status, 200);
    assert.equal(replay.data.alreadyVerified, true);

    // Different paymentId -> 409 Conflict
    const sig2 = computeRazorpayPaymentSignature({
      orderId: orderRes.data.orderId,
      paymentId: "pay_second",
      keySecret: RZP_KEY_SECRET,
    });
    const conflict = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_dup_pay",
        razorpayOrderId: orderRes.data.orderId,
        razorpayPaymentId: "pay_second",
        razorpaySignature: sig2,
      },
      "Bearer tok_customer_a"
    );
    assert.equal(conflict.status, 409);

    const ledgerSnap = await db.collection("ledger_entries").where("bookingId", "==", "bk_dup_pay").get();
    assert.equal(ledgerSnap.size, 4);
  });

  test("15. Webhook works without Firebase Auth via valid signature and rejects invalid signature or tampered amount", async () => {
    await seedBooking("bk_wh_auth");

    const tamperedPayload = {
      event_id: "evt_tamper_wh",
      event: "payment.captured",
      payload: {
        payment: {
          entity: {
            id: "pay_tamper",
            order_id: "order_bk_wh_auth",
            amount: 100, // mismatched vs 100000
            currency: "INR",
            notes: { bookingId: "bk_wh_auth" },
          },
        },
      },
    };
    const tamperedBody = JSON.stringify(tamperedPayload);
    const badSigRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": "deadbeef" },
      body: tamperedBody,
    });
    assert.equal(badSigRes.status, 401);

    const tamperedSignedRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-razorpay-signature": computeRazorpayWebhookSignature({ rawBody: tamperedBody, webhookSecret: RZP_WEBHOOK_SECRET }),
      },
      body: tamperedBody,
    });
    assert.equal(tamperedSignedRes.status, 400);
    assert.equal((await tamperedSignedRes.json()).status, "TAMPER_REJECTED");

    const validPayload = {
      event_id: "evt_valid_wh",
      event: "payment.captured",
      payload: {
        payment: {
          entity: {
            id: "pay_valid_wh",
            order_id: "order_bk_wh_auth",
            amount: 100000,
            currency: "INR",
            notes: { bookingId: "bk_wh_auth" },
          },
        },
      },
    };
    const validBody = JSON.stringify(validPayload);
    const validRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-razorpay-signature": computeRazorpayWebhookSignature({ rawBody: validBody, webhookSecret: RZP_WEBHOOK_SECRET }),
      },
      body: validBody,
    });
    assert.equal(validRes.status, 200);
    assert.equal((await validRes.json()).status, "PROCESSED");
  });

  test("16. Zero unintended default ADMIN_EMAILS: unset ADMIN_EMAILS rejects unconfigured email with 403", async () => {
    await seedBooking("bk_no_def_admin", { bookingStatus: "CONFIRMED", paymentStatus: "SUCCESS" });
    const denied = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_no_def_admin", refundAmountPaise: 10000, reason: "Should reject without allowlist" },
      "Bearer tok_admin_allowlist"
    );
    assert.equal(denied.status, 403);

    // Explicit allowlist grants access cleanly
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      adminEmails: "utkarsh.work.contact@gmail.com",
    });
    const allowed = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_no_def_admin", refundAmountPaise: 10000, reason: "Explicit allowlist" },
      "Bearer tok_admin_allowlist"
    );
    assert.equal(allowed.status, 200);
  });

  test("17. Disabled admin (admins/{uid}.active === false) immediately loses access despite custom claims & allowlist", async () => {
    await seedBooking("bk_dis_admin", { bookingStatus: "CONFIRMED", paymentStatus: "SUCCESS" });
    await db.collection("admins").doc("disabled_admin_uid").set({
      email: "ops-disabled@hrconnect.in",
      active: false,
    });
    const customAuth = {
      async verifyIdToken(token, checkRevoked) {
        if (token === "tok_disabled_claim_admin") {
          return {
            uid: "disabled_admin_uid",
            email: "ops-disabled@hrconnect.in",
            email_verified: true,
            admin: true,
            role: "ADMIN",
          };
        }
        return authService.verifyIdToken(token, checkRevoked);
      },
    };
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService: customAuth,
      adminEmails: "ops-disabled@hrconnect.in",
    });

    const res = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_dis_admin", refundAmountPaise: 10000, reason: "Disabled admin check" },
      "Bearer tok_disabled_claim_admin"
    );
    assert.equal(res.status, 403);
  });

  test("18. Order creation rejects tampered commissionRateBps or financial split against platform_settings", async () => {
    await seedBooking("bk_bad_split", {
      commissionRateBps: 0,
      commissionPaise: 0,
      expertEarningPaise: 100000,
    });
    const res = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_bad_split" },
      "Bearer tok_customer_a"
    );
    assert.equal(res.status, 409);
  });

  test("19. Refund edge cases: blocks refund while payout is PROCESSING (409), blocks over-refund (400), tracks PAID clawback", async () => {
    await seedBooking("bk_proc_lock", {
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      payoutEligibilityStatus: "PROCESSING",
    });
    const procBlock = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_proc_lock", refundAmountPaise: 50000, reason: "Payout in flight" },
      "Bearer tok_admin_doc"
    );
    assert.equal(procBlock.status, 409);

    // Paid booking refund triggers clawback reconciliation ledger entry
    await db.collection("bookings").doc("bk_proc_lock").update({ payoutEligibilityStatus: "PAID" });
    const paidRefund = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_proc_lock", refundAmountPaise: 100000, reason: "Post-payout dispute refund" },
      "Bearer tok_admin_doc"
    );
    assert.equal(paidRefund.status, 200);
    const bkSnap = await db.collection("bookings").doc("bk_proc_lock").get();
    assert.equal(bkSnap.data().payoutClawbackRequired, true);
    const reconSnap = await db.collection("ledger_entries").where("entryType", "==", "RECONCILIATION_ADJUSTMENT").get();
    assert.equal(reconSnap.size, 1);
    assert.equal(reconSnap.docs[0].data().amountPaise, 85000);

    // Second refund on fully refunded booking fails cleanly
    const overRefund = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_proc_lock", refundAmountPaise: 1000, reason: "Duplicate refund" },
      "Bearer tok_admin_doc"
    );
    assert.equal(overRefund.status, 409);
  });

  test("20. Attendance spoof (ADMIN_APPROVED note without ADMIN_MANUAL_REVIEW source) rejected in server payout eligibility", async () => {
    const completedMs = 1_700_000_000_000;
    await seedBooking("bk_att_spoof", {
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      attendanceStatus: "MANUAL_REVIEW",
      attendanceVerificationSource: "SUPPLEMENTARY_CHECKIN_FALLBACK",
      attendanceNotes: "ADMIN_APPROVED spoofed by client",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });
    await db.collection("payout_requests").doc("pr_att_spoof").set({
      expertUid: "exp_1",
      amountPaise: 85000,
      eligibleBookingIds: ["bk_att_spoof"],
      status: "PENDING",
    });

    const spoofRes = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      { payoutRequestId: "pr_att_spoof", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
      "Bearer tok_admin_doc"
    );
    assert.equal(spoofRes.status, 400);

    await db.collection("bookings").doc("bk_att_spoof").update({
      attendanceVerificationSource: "ADMIN_MANUAL_REVIEW",
    });
    const validRes = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      { payoutRequestId: "pr_att_spoof", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
      "Bearer tok_admin_doc"
    );
    assert.equal(validRes.status, 200);
  });

  test("21. Disallowed browser CORS Origin is rejected with 403", async () => {
    const res = await fetch(`${serverHandle.baseUrl}/health`, {
      method: "GET",
      headers: { Origin: "https://evil.example.com" },
    });
    assert.equal(res.status, 403);
  });

  test("22. Production /ready probe enforces live secrets and rate limiter returns 429 + Retry-After", async () => {
    await serverHandle.close();
    const unready = await startTestHttpServer({
      db,
      authService,
      requireLiveRazorpay: true,
      razorpayKeyId: "rzp_test_placeholder",
      razorpayKeySecret: "rzp_secret_replace_me",
      razorpayWebhookSecret: "",
    });
    const probe503 = await fetch(`${unready.baseUrl}/ready`);
    assert.equal(probe503.status, 503);
    const body503 = await probe503.json();
    assert.equal(body503.ready, false);
    assert.ok(body503.missing.includes("RAZORPAY_KEY_ID"));
    assert.ok(body503.missing.includes("RAZORPAY_KEY_SECRET"));
    assert.ok(body503.missing.includes("RAZORPAY_WEBHOOK_SECRET"));
    await unready.close();

    serverHandle = await startTestHttpServer({
      db,
      authService,
      requireLiveRazorpay: true,
      razorpayKeyId: "rzp_live_123456",
      razorpayKeySecret: "sec_live_123456",
      razorpayWebhookSecret: "whsec_live_123456",
      sensitiveRateLimitMaxPerMin: 2,
    });
    const probe200 = await fetch(`${serverHandle.baseUrl}/ready`);
    assert.equal(probe200.status, 200);
    const body200 = await probe200.json();
    assert.equal(body200.ready, true);

    // Trigger 3 sensitive requests -> 3rd hits sliding window 429 with Retry-After
    const req1 = await postJson(`${serverHandle.baseUrl}/api/payments/create-order`, { bookingId: "missing" }, "Bearer tok_customer_a");
    const req2 = await postJson(`${serverHandle.baseUrl}/api/payments/create-order`, { bookingId: "missing" }, "Bearer tok_customer_a");
    const req3 = await postJson(`${serverHandle.baseUrl}/api/payments/create-order`, { bookingId: "missing" }, "Bearer tok_customer_a");
    assert.notEqual(req1.status, 429);
    assert.notEqual(req2.status, 429);
    assert.equal(req3.status, 429);
  });

  test("23. Booking create, reschedule (4h cutoff + slot swap), and cancel with mocked Razorpay refund", async () => {
    await db.collection("experts").doc("exp_vikram").set({
      expertUid: "exp_1",
      fullName: "Vikram Mehta",
      verifiedGoogleEmail: "vikram@gmail.com",
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
      referralCode: "VIK10",
      timezone: "Asia/Kolkata",
    });
    await db.collection("services").doc("svc_posh").set({
      expertId: "exp_vikram",
      expertUid: "exp_1",
      title: "POSH Compliance Audit",
      pricePaise: 100000,
      durationMinutes: 30,
      active: true,
    });

    const razorpayCalls = [];
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      razorpayKeyId: "rzp_live_key",
      razorpayKeySecret: "rzp_live_secret",
      razorpayFetch: async (url, init) => {
        razorpayCalls.push({ url, method: init.method, body: JSON.parse(init.body || "{}") });
        if (url.endsWith("/refund")) {
          return new Response(JSON.stringify({ id: "rfnd_cancel_1", status: "processed" }), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 404 });
      },
    });

    const createRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/create`,
      {
        expertId: "exp_vikram",
        serviceId: "svc_posh",
        dateIso: "2028-05-10",
        startMinute: 600,
        topic: "Annual POSH committee charter review",
        referralCode: "VIK10",
        customerName: "Alice",
      },
      "Bearer tok_customer_a"
    );
    assert.equal(createRes.status, 200);
    const bkId = createRes.data.id;
    assert.equal(createRes.data.commissionRateBps, 1000);
    assert.equal(createRes.data.referralType, "EXPERT_REFERRAL");

    // Promote to CONFIRMED + captured payment to test reschedule & cancel
    await db.collection("bookings").doc(bkId).update({ bookingStatus: "CONFIRMED", paymentStatus: "SUCCESS" });
    await db.collection("payments").doc(`pay_${bkId}`).set({
      bookingId: bkId,
      customerUid: "cust_a",
      expertUid: "exp_1",
      razorpayPaymentId: "pay_live_999",
      amountPaise: 100000,
      paymentStatus: "SUCCESS",
      testMode: false,
    });

    // Reject reschedule inside 4 hours of start
    const nearStartEpoch = Date.parse("2028-05-10T10:00:00+05:30") - 2 * 3_600_000;
    const closeResched = await postJson(
      `${serverHandle.baseUrl}/api/bookings/reschedule`,
      { bookingId: bkId, newDateIso: "2028-05-11", newStartMinute: 660, nowEpochMs: nearStartEpoch },
      "Bearer tok_customer_a"
    );
    assert.equal(closeResched.status, 409);

    // Valid reschedule swaps slot_locks atomically
    const validResched = await postJson(
      `${serverHandle.baseUrl}/api/bookings/reschedule`,
      { bookingId: bkId, newDateIso: "2028-05-11", newStartMinute: 660, nowEpochMs: nearStartEpoch - 86_400_000 },
      "Bearer tok_customer_a"
    );
    assert.equal(validResched.status, 200);
    assert.equal(validResched.data.slotKey, "exp_vikram_2028-05-11_660");
    const oldLock = await db.collection("slot_locks").doc("exp_vikram_2028-05-10_600").get();
    assert.equal(oldLock.data().bookingStatus, "CANCELLED");

    // Cancel >= 24h out triggers Razorpay refund + releases new slot lock
    const cancelRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/cancel`,
      { bookingId: bkId, reason: "Schedule conflict", nowEpochMs: nearStartEpoch - 2 * 86_400_000 },
      "Bearer tok_customer_a"
    );
    assert.equal(cancelRes.status, 200);
    assert.equal(cancelRes.data.refundPaise, 100000);
    assert.equal(cancelRes.data.refundStatus, "REFUNDED");
    assert.equal(razorpayCalls.length, 1);
    const canceledSnap = await db.collection("bookings").doc(bkId).get();
    assert.equal(canceledSnap.data().bookingStatus, "REFUNDED");
  });

  test("24. Live mocked Razorpay Orders/Capture + async pending refund -> refund.processed webhook", async () => {
    const rzpLog = [];
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      razorpayKeyId: "rzp_live_key",
      razorpayKeySecret: "rzp_live_secret",
      razorpayWebhookSecret: RZP_WEBHOOK_SECRET,
      razorpayFetch: async (url, init) => {
        rzpLog.push(`${init.method} ${url}`);
        if (url.endsWith("/orders")) {
          return new Response(JSON.stringify({ id: "order_live_777", amount: 100000, currency: "INR", status: "created" }), { status: 200 });
        }
        if (url.endsWith("/payments/pay_live_777") && init.method === "GET") {
          return new Response(JSON.stringify({
            id: "pay_live_777",
            order_id: "order_live_777",
            amount: 100000,
            currency: "INR",
            status: "authorized",
            method: "upi",
          }), { status: 200 });
        }
        if (url.endsWith("/payments/pay_live_777/capture")) {
          return new Response(JSON.stringify({ id: "pay_live_777", status: "captured", fee: 2000, tax: 360 }), { status: 200 });
        }
        if (url.endsWith("/payments/pay_live_777/refund")) {
          return new Response(JSON.stringify({ id: "rfnd_async_1", status: "pending", amount: 100000 }), { status: 200 });
        }
        return new Response(JSON.stringify({ error: "unexpected" }), { status: 400 });
      },
    });

    await seedBooking("bk_live_1");
    const orderRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_live_1" },
      "Bearer tok_customer_a"
    );
    assert.equal(orderRes.status, 200);
    assert.equal(orderRes.data.orderId, "order_live_777");
    assert.equal(orderRes.data.razorpayKeyId, "rzp_live_key");

    const sig = computeRazorpayPaymentSignature({
      orderId: "order_live_777",
      paymentId: "pay_live_777",
      keySecret: "rzp_live_secret",
    });
    const verifyRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      { bookingId: "bk_live_1", razorpayOrderId: "order_live_777", razorpayPaymentId: "pay_live_777", razorpaySignature: sig },
      "Bearer tok_customer_a"
    );
    assert.equal(verifyRes.status, 200);
    assert.equal(verifyRes.data.verified, true);
    assert.equal(verifyRes.data.gatewayFeePaise, 2000);
    assert.equal(verifyRes.data.gatewayTaxPaise, 360);
    assert.equal(verifyRes.data.taxDeductionPaise, 0);
    assert.ok(rzpLog.some((x) => x.includes("/capture")));

    // Admin initiates refund -> Razorpay returns pending -> stays REFUND_PENDING until webhook settles it
    const refRes = await postJson(
      `${serverHandle.baseUrl}/api/refunds/initiate`,
      { bookingId: "bk_live_1", refundAmountPaise: 100000, reason: "Customer SLA issue" },
      "Bearer tok_admin_doc"
    );
    assert.equal(refRes.status, 200);
    assert.equal(refRes.data.refundStatus, "REFUND_PENDING");

    const whPayload = JSON.stringify({
      event_id: "evt_ref_done_1",
      event: "refund.processed",
      payload: {
        refund: {
          entity: {
            id: "rfnd_async_1",
            payment_id: "pay_live_777",
            amount: 100000,
            status: "processed",
          },
        },
      },
    });
    const whSig = computeRazorpayWebhookSignature({ rawBody: whPayload, webhookSecret: RZP_WEBHOOK_SECRET });
    const whRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": whSig },
      body: whPayload,
    });
    assert.equal(whRes.status, 200);
    const bkAfter = await db.collection("bookings").doc("bk_live_1").get();
    assert.equal(bkAfter.data().paymentStatus, "REFUNDED");
    assert.equal(bkAfter.data().bookingStatus, "REFUNDED");
  });

  test("25. Expert RazorpayX payout onboarding + payout dispatch + payout.processed webhook", async () => {
    const xCalls = [];
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      razorpayKeyId: "rzp_live_key",
      razorpayKeySecret: "rzp_live_secret",
      razorpayWebhookSecret: RZP_WEBHOOK_SECRET,
      razorpayXAccountNumber: "2323230041626905",
      payoutMode: "razorpayx",
      razorpayFetch: async (url, init) => {
        xCalls.push({ url, idemp: init.headers?.["X-Payout-Idempotency"] || null });
        if (url.endsWith("/contacts")) {
          return new Response(JSON.stringify({ id: "cont_exp_1" }), { status: 200 });
        }
        if (url.endsWith("/fund_accounts")) {
          return new Response(JSON.stringify({ id: "fa_exp_1" }), { status: 200 });
        }
        if (url.endsWith("/payouts")) {
          return new Response(JSON.stringify({ id: "pout_rzpx_1", status: "processing", utr: null }), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 404 });
      },
    });

    await db.collection("experts").doc("exp_exp_1").set({
      expertUid: "exp_1",
      fullName: "Vikram Mehta",
      verifiedGoogleEmail: "vikram@gmail.com",
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
    });

    const onbRes = await postJson(
      `${serverHandle.baseUrl}/api/experts/payout-onboarding`,
      { payoutAccountType: "UPI", vpaAddress: "vikram.hr@okaxis" },
      "Bearer tok_expert_1"
    );
    assert.equal(onbRes.status, 200);
    assert.equal(onbRes.data.payoutDestinationMasked, "UPI:vi***@okaxis");
    assert.equal(onbRes.data.payoutKycStatus, "LINKED_READY");
    assert.equal(onbRes.data.razorpayFundAccountId, "fa_exp_1");

    const secretDoc = await db.collection("experts").doc("exp_exp_1").collection("payout_secrets").doc("config").get();
    assert.equal(secretDoc.data().vpaAddress, "vikram.hr@okaxis");

    const completedMs = 1_700_000_000_000;
    await seedBooking("bk_rzpx_1", {
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      attendanceStatus: "VERIFIED",
      attendanceVerificationSource: "GOOGLE_MEET_REST_API",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });
    await db.collection("payout_requests").doc("pr_rzpx_1").set({
      expertId: "exp_exp_1",
      expertUid: "exp_1",
      expertName: "Vikram Mehta",
      amountPaise: 85000,
      payoutDestination: "UPI:vi***@okaxis",
      eligibleBookingIds: ["bk_rzpx_1"],
      idempotencyKey: "idem_pr_rzpx_1",
      status: "PENDING",
      retryCount: 0,
    });

    const procRes = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      { payoutRequestId: "pr_rzpx_1", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
      "Bearer tok_admin_doc"
    );
    assert.equal(procRes.status, 200);
    assert.equal(procRes.data.status, "PROCESSING");
    assert.equal(procRes.data.providerTransferId, "pout_rzpx_1");
    assert.ok(xCalls.some((c) => c.url.endsWith("/payouts") && c.idemp === "idem_pr_rzpx_1_0"));

    // Simulate payout.processed webhook carrying final UTR
    const whBody = JSON.stringify({
      event_id: "evt_pout_1",
      event: "payout.processed",
      payload: {
        payout: {
          entity: {
            id: "pout_rzpx_1",
            utr: "UTR50987654321",
            status: "processed",
          },
        },
      },
    });
    const whSig = computeRazorpayWebhookSignature({ rawBody: whBody, webhookSecret: RZP_WEBHOOK_SECRET });
    const whRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": whSig },
      body: whBody,
    });
    assert.equal(whRes.status, 200);
    const prAfter = await db.collection("payout_requests").doc("pr_rzpx_1").get();
    assert.equal(prAfter.data().status, "PAID");
    assert.equal(prAfter.data().payoutReference, "UTR50987654321");
    const bkAfter = await db.collection("bookings").doc("bk_rzpx_1").get();
    assert.equal(bkAfter.data().payoutEligibilityStatus, "PAID");
  });

  test("26. Google Workspace Calendar v3 & Meet REST API v2 single-conference sync + attendance evaluation", async () => {
    const startMs = Date.parse("2026-12-20T10:00:00+05:30");
    const callLog = [];
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      googleWorkspaceOptions: {
        oauthClientId: "g_client",
        oauthClientSecret: "g_secret",
        oauthRefreshToken: "g_refresh",
        workspaceDomain: "hrconnect.in",
        pollDelayMs: 0,
      },
      googleFetch: async (url, init) => {
        callLog.push(`${init?.method || "GET"} ${url}`);
        if (url.includes("oauth2.googleapis.com/token")) {
          return new Response(JSON.stringify({ access_token: "ya29.mock_access", expires_in: 3600 }), { status: 200 });
        }
        if (url.includes("/calendar/v3/calendars/") && url.includes("/events?")) {
          return new Response(JSON.stringify({
            id: "gcal_evt_999",
            hangoutLink: "https://meet.google.com/abc-defg-hij",
            conferenceData: {
              conferenceId: "abc-defg-hij",
              createRequest: { status: { statusCode: "success" } },
            },
          }), { status: 200 });
        }
        if (url.endsWith("/v2/spaces/abc-defg-hij") && (init?.method || "GET") === "GET") {
          return new Response(JSON.stringify({
            name: "spaces/spc_999",
            meetingUri: "https://meet.google.com/abc-defg-hij",
            meetingCode: "abc-defg-hij",
          }), { status: 200 });
        }
        if (url.includes("/v2/spaces/spc_999?updateMask=") && init?.method === "PATCH") {
          return new Response(JSON.stringify({
            name: "spaces/spc_999",
            config: { accessType: "TRUSTED", entryPointAccess: "ALL" },
          }), { status: 200 });
        }
        if (url.includes("/v2/conferenceRecords?")) {
          return new Response(JSON.stringify({
            conferenceRecords: [{ name: "conferenceRecords/conf_999" }],
          }), { status: 200 });
        }
        if (url.endsWith("/participants?pageSize=100")) {
          return new Response(JSON.stringify({
            participants: [
              { name: "conferenceRecords/conf_999/participants/p1", signedinUser: { user: "alice@gmail.com" } },
              { name: "conferenceRecords/conf_999/participants/p2", signedinUser: { user: "vikram@gmail.com" } },
            ],
          }), { status: 200 });
        }
        if (url.includes("/participants/p1/participantSessions")) {
          return new Response(JSON.stringify({
            participantSessions: [{
              startTime: new Date(startMs).toISOString(),
              endTime: new Date(startMs + 25 * 60_000).toISOString(),
            }],
          }), { status: 200 });
        }
        if (url.includes("/participants/p2/participantSessions")) {
          return new Response(JSON.stringify({
            participantSessions: [{
              startTime: new Date(startMs + 2 * 60_000).toISOString(),
              endTime: new Date(startMs + 26 * 60_000).toISOString(),
            }],
          }), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 404 });
      },
    });

    await seedBooking("bk_gmeet_1", {
      bookingStatus: "CONFIRMED",
      paymentStatus: "SUCCESS",
      dateIso: "2026-12-20",
      startMinute: 600,
      endMinute: 630,
      durationMinutes: 30,
    });

    const calRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-calendar`,
      { bookingId: "bk_gmeet_1" },
      "Bearer tok_expert_1"
    );
    assert.equal(calRes.status, 200);
    assert.equal(calRes.data.googleCalendarEventId, "gcal_evt_999");
    assert.equal(calRes.data.meetingUrl, "https://meet.google.com/abc-defg-hij");
    assert.equal(calRes.data.googleMeetSpaceId, "spaces/spc_999");
    assert.equal(calRes.data.googleMeetCode, "abc-defg-hij");
    assert.equal(calRes.data.meetingAccessMode, "TRUSTED");
    assert.equal(calRes.data.syncStatus, "SYNCED");
    // Ensure we never created a disconnected second Meet space via POST /v2/spaces
    assert.ok(!callLog.some((c) => c.startsWith("POST ") && c.endsWith("/v2/spaces")));

    const attRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-meet-attendance`,
      { bookingId: "bk_gmeet_1" },
      "Bearer tok_expert_1"
    );
    assert.equal(attRes.status, 200);
    assert.equal(attRes.data.status, "VERIFIED");
    assert.equal(attRes.data.overlapMinutes, 23);
    assert.equal(attRes.data.verificationSource, "GOOGLE_MEET_REST_API");
  });

  test("26b. Google Calendar pending conferenceData polling + paginated Meet v2 conferenceRecords/participants", async () => {
    const startMs = Date.parse("2026-12-22T10:00:00+05:30");
    let eventPollCount = 0;
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      googleWorkspaceOptions: {
        oauthClientId: "g_client",
        oauthClientSecret: "g_secret",
        oauthRefreshToken: "g_refresh",
        pollDelayMs: 0,
        pollMaxAttempts: 3,
      },
      googleFetch: async (url, init) => {
        if (url.includes("oauth2.googleapis.com/token")) {
          return new Response(JSON.stringify({ access_token: "ya29.mock_access", expires_in: 3600 }), { status: 200 });
        }
        if (url.includes("/calendar/v3/calendars/") && url.includes("/events?")) {
          return new Response(JSON.stringify({
            id: "gcal_poll_1",
            conferenceData: { createRequest: { status: { statusCode: "pending" } } },
          }), { status: 200 });
        }
        if (url.endsWith("/events/gcal_poll_1") && (init?.method || "GET") === "GET") {
          eventPollCount += 1;
          if (eventPollCount === 1) {
            return new Response(JSON.stringify({
              id: "gcal_poll_1",
              conferenceData: { createRequest: { status: { statusCode: "pending" } } },
            }), { status: 200 });
          }
          return new Response(JSON.stringify({
            id: "gcal_poll_1",
            hangoutLink: "https://meet.google.com/xyz-uvwx-rst",
            conferenceData: {
              conferenceId: "xyz-uvwx-rst",
              createRequest: { status: { statusCode: "success" } },
            },
          }), { status: 200 });
        }
        if (url.endsWith("/v2/spaces/xyz-uvwx-rst") && (init?.method || "GET") === "GET") {
          return new Response(JSON.stringify({ name: "spaces/spc_poll_1" }), { status: 200 });
        }
        if (url.includes("/v2/spaces/spc_poll_1?updateMask=") && init?.method === "PATCH") {
          return new Response(JSON.stringify({ name: "spaces/spc_poll_1", config: { accessType: "OPEN" } }), { status: 200 });
        }
        if (url.includes("/v2/conferenceRecords?")) {
          if (url.includes("pageToken=cr_p2")) {
            return new Response(JSON.stringify({
              conferenceRecords: [{ name: "conferenceRecords/cr_2" }],
            }), { status: 200 });
          }
          return new Response(JSON.stringify({
            conferenceRecords: [{ name: "conferenceRecords/cr_1" }],
            nextPageToken: "cr_p2",
          }), { status: 200 });
        }
        if (url.includes("/participantSessions")) {
          if (url.includes("cr_1/participants/p1/participantSessions")) {
            return new Response(JSON.stringify({
              participantSessions: [{ startTime: new Date(startMs).toISOString(), endTime: new Date(startMs + 10 * 60_000).toISOString() }],
            }), { status: 200 });
          }
          if (url.includes("cr_2/participants/p1b/participantSessions")) {
            return new Response(JSON.stringify({
              participantSessions: [{ startTime: new Date(startMs + 10 * 60_000).toISOString(), endTime: new Date(startMs + 25 * 60_000).toISOString() }],
            }), { status: 200 });
          }
          if (url.includes("cr_2/participants/p2/participantSessions")) {
            return new Response(JSON.stringify({
              participantSessions: [{ startTime: new Date(startMs + 2 * 60_000).toISOString(), endTime: new Date(startMs + 25 * 60_000).toISOString() }],
            }), { status: 200 });
          }
          return new Response(JSON.stringify({ participantSessions: [] }), { status: 200 });
        }
        if (url.includes("conferenceRecords/cr_1/participants")) {
          return new Response(JSON.stringify({
            participants: [{ name: "conferenceRecords/cr_1/participants/p1", signedinUser: { user: "alice@gmail.com" } }],
          }), { status: 200 });
        }
        if (url.includes("conferenceRecords/cr_2/participants")) {
          if (url.includes("pageToken=part_p2")) {
            return new Response(JSON.stringify({
              participants: [{ name: "conferenceRecords/cr_2/participants/p2", signedinUser: { user: "vikram@gmail.com" } }],
            }), { status: 200 });
          }
          return new Response(JSON.stringify({
            participants: [{ name: "conferenceRecords/cr_2/participants/p1b", signedinUser: { user: "alice@gmail.com" } }],
            nextPageToken: "part_p2",
          }), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 404 });
      },
    });

    await seedBooking("bk_poll_1", {
      bookingStatus: "CONFIRMED",
      paymentStatus: "SUCCESS",
      dateIso: "2026-12-22",
      startMinute: 600,
      endMinute: 630,
      durationMinutes: 30,
    });

    const calRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-calendar`,
      { bookingId: "bk_poll_1" },
      "Bearer tok_expert_1"
    );
    assert.equal(calRes.status, 200);
    assert.equal(eventPollCount, 2);
    assert.equal(calRes.data.googleMeetCode, "xyz-uvwx-rst");
    assert.equal(calRes.data.googleMeetSpaceId, "spaces/spc_poll_1");

    const attRes = await postJson(
      `${serverHandle.baseUrl}/api/bookings/sync-meet-attendance`,
      { bookingId: "bk_poll_1" },
      "Bearer tok_expert_1"
    );
    assert.equal(attRes.status, 200);
    assert.equal(attRes.data.status, "VERIFIED");
    assert.equal(attRes.data.overlapMinutes, 23);
  });

  test("27. FCM token registration, opt-out handling, dedupKey, and invalid token cleanup", async () => {
    const sentMessages = [];
    const mockMessaging = {
      async sendEachForMulticast(payload) {
        sentMessages.push(payload);
        return {
          responses: payload.tokens.map((tok) =>
            tok === "dead_token_999999999999999999"
              ? { success: false, error: { code: "messaging/registration-token-not-registered" } }
              : { success: true, messageId: "msg_ok_1" }
          ),
        };
      },
    };

    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      messaging: mockMessaging,
    });

    const regRes = await postJson(
      `${serverHandle.baseUrl}/api/notifications/register-token`,
      { fcmToken: "live_valid_token_1234567890_abcdef", platform: "android", deviceId: "Pixel 9" },
      "Bearer tok_customer_a"
    );
    assert.equal(regRes.status, 200);

    await db.collection("users").doc("cust_a").collection("fcm_tokens").doc("stale_doc").set({
      token: "dead_token_999999999999999999",
      active: true,
    });

    const { dispatchNotificationsWithPush } = require("./src/notificationService");
    await dispatchNotificationsWithPush({
      db,
      messaging: mockMessaging,
      items: [{
        recipientUid: "cust_a",
        title: "Consultation Confirmed",
        message: "Sensitive topic detail redacted from lock screen",
        eventType: "BOOKING_CONFIRMED",
        relatedBookingId: "bk_push_1",
        dedupKey: "dedup_push_1",
      }],
    });
    // Repeat same dedupKey -> must not duplicate notification or send second push
    await dispatchNotificationsWithPush({
      db,
      messaging: mockMessaging,
      items: [{
        recipientUid: "cust_a",
        title: "Consultation Confirmed",
        message: "Duplicate",
        eventType: "BOOKING_CONFIRMED",
        relatedBookingId: "bk_push_1",
        dedupKey: "dedup_push_1",
      }],
    });

    assert.equal(sentMessages.length, 1);
    assert.equal(sentMessages[0].notification.title, "Consultation Confirmed");
    assert.ok(!sentMessages[0].notification.body.includes("Sensitive"));
    const staleAfter = await db.collection("users").doc("cust_a").collection("fcm_tokens").doc("stale_doc").get();
    assert.equal(staleAfter.data().active, false);
  });

  test("28. Late payment.captured webhook on CANCELLED booking & reassigned slot_lock raises LATE_CAPTURE_CONFLICT without double-booking", async () => {
    const slotKey = "exp_exp_1_2026-12-25_600";
    await seedBooking("bk_late_1", {
      bookingStatus: "CANCELLED",
      paymentStatus: "FAILED",
      slotKey,
      amountPaise: 100000,
    });
    // Another customer now owns this slot lock
    await db.collection("slot_locks").doc(slotKey).set({
      slotKey,
      expertId: "exp_exp_1",
      bookingId: "bk_other_customer",
      bookingStatus: "CONFIRMED",
    });

    const whBody = JSON.stringify({
      event_id: "evt_late_cap_1",
      event: "payment.captured",
      payload: {
        payment: {
          entity: {
            id: "pay_late_1",
            order_id: "order_late_1",
            amount: 100000,
            currency: "INR",
            fee: 2360,
            tax: 360,
            method: "upi",
            status: "captured",
            notes: { bookingId: "bk_late_1" },
          },
        },
      },
    });
    const whSig = computeRazorpayWebhookSignature({ rawBody: whBody, webhookSecret: RZP_WEBHOOK_SECRET });
    const whRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": whSig },
      body: whBody,
    });
    assert.equal(whRes.status, 200);
    const whData = await whRes.json();
    assert.equal(whData.status, "LATE_CAPTURE_CONFLICT");

    const bkAfter = await db.collection("bookings").doc("bk_late_1").get();
    assert.equal(bkAfter.data().bookingStatus, "CANCELLED");
    assert.equal(bkAfter.data().paymentStatus, "SUCCESS");
    assert.equal(bkAfter.data().lateCaptureConflict, true);
    assert.equal(bkAfter.data().payoutEligibilityStatus, "ON_HOLD");
    assert.equal(bkAfter.data().gatewayFeePaise, 2360);
    assert.equal(bkAfter.data().gatewayTaxPaise, 360);

    const lockAfter = await db.collection("slot_locks").doc(slotKey).get();
    assert.equal(lockAfter.data().bookingId, "bk_other_customer");
    assert.equal(lockAfter.data().bookingStatus, "CONFIRMED");
  });

  test("29. payment.authorized webhook captures via Razorpay API and atomically confirms PENDING_PAYMENT booking", async () => {
    const captureCalls = [];
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      razorpayKeyId: "rzp_live_key",
      razorpayKeySecret: "rzp_live_secret",
      razorpayWebhookSecret: RZP_WEBHOOK_SECRET,
      razorpayFetch: async (url) => {
        if (url.endsWith("/payments/pay_auth_wh_1/capture")) {
          captureCalls.push(url);
          return new Response(JSON.stringify({
            id: "pay_auth_wh_1",
            order_id: "order_auth_wh_1",
            amount: 100000,
            currency: "INR",
            status: "captured",
            fee: 2000,
            tax: 360,
            method: "card",
          }), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 404 });
      },
    });

    await seedBooking("bk_auth_wh_1", {
      bookingStatus: "PENDING_PAYMENT",
      paymentStatus: "UNPAID",
      amountPaise: 100000,
    });

    const whBody = JSON.stringify({
      event_id: "evt_pay_auth_1",
      event: "payment.authorized",
      payload: {
        payment: {
          entity: {
            id: "pay_auth_wh_1",
            order_id: "order_auth_wh_1",
            amount: 100000,
            currency: "INR",
            status: "authorized",
            method: "card",
            notes: { bookingId: "bk_auth_wh_1" },
          },
        },
      },
    });
    const whSig = computeRazorpayWebhookSignature({ rawBody: whBody, webhookSecret: RZP_WEBHOOK_SECRET });
    const whRes = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": whSig },
      body: whBody,
    });
    assert.equal(whRes.status, 200);
    assert.equal(captureCalls.length, 1);

    const bkAfter = await db.collection("bookings").doc("bk_auth_wh_1").get();
    assert.equal(bkAfter.data().bookingStatus, "CONFIRMED");
    assert.equal(bkAfter.data().paymentStatus, "SUCCESS");
    assert.equal(bkAfter.data().gatewayFeePaise, 2000);
    assert.equal(bkAfter.data().gatewayTaxPaise, 360);
  });

  test("30. payout.rejected webhook restores booking eligibility & synchronous processed payout avoids duplicate ledger on replay", async () => {
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      razorpayKeyId: "rzp_live_key",
      razorpayKeySecret: "rzp_live_secret",
      razorpayWebhookSecret: RZP_WEBHOOK_SECRET,
      razorpayXAccountNumber: "2323230041626905",
      payoutMode: "razorpayx",
      razorpayFetch: async (url) => {
        if (url.endsWith("/payouts")) {
          return new Response(JSON.stringify({ id: "pout_sync_1", status: "processed", utr: "UTR_SYNC_111" }), { status: 200 });
        }
        return new Response(JSON.stringify({}), { status: 404 });
      },
    });

    await db.collection("experts").doc("exp_exp_1").set({
      expertUid: "exp_1",
      fullName: "Vikram Mehta",
      verifiedGoogleEmail: "vikram@gmail.com",
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
      razorpayContactId: "cont_1",
      razorpayFundAccountId: "fa_1",
      payoutKycStatus: "LINKED_READY",
    });

    const completedMs = 1_700_000_000_000;
    await seedBooking("bk_pout_rej_1", {
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      attendanceStatus: "VERIFIED",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });
    await db.collection("payout_requests").doc("pr_rej_1").set({
      expertId: "exp_exp_1",
      expertUid: "exp_1",
      expertName: "Vikram Mehta",
      amountPaise: 85000,
      payoutDestination: "UPI:vi***@okaxis",
      eligibleBookingIds: ["bk_pout_rej_1"],
      idempotencyKey: "idem_pr_rej_1",
      status: "PROCESSING",
      providerTransferId: "pout_rej_1",
      retryCount: 0,
    });

    const whRej = JSON.stringify({
      event_id: "evt_pout_rej_1",
      event: "payout.rejected",
      payload: {
        payout: {
          entity: {
            id: "pout_rej_1",
            status: "rejected",
            failure_reason: "Invalid beneficiary IFSC",
          },
        },
      },
    });
    const whSigRej = computeRazorpayWebhookSignature({ rawBody: whRej, webhookSecret: RZP_WEBHOOK_SECRET });
    const whResRej = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": whSigRej },
      body: whRej,
    });
    assert.equal(whResRej.status, 200);
    const prRejAfter = await db.collection("payout_requests").doc("pr_rej_1").get();
    assert.equal(prRejAfter.data().status, "FAILED");
    const bkRejAfter = await db.collection("bookings").doc("bk_pout_rej_1").get();
    assert.equal(bkRejAfter.data().payoutEligibilityStatus, "ELIGIBLE");
    const ledFail = await db.collection("ledger_entries").doc("led_payfail_pr_rej_1_0").get();
    assert.equal(ledFail.exists, true);

    // Now test synchronous RazorpayX status === "processed" + duplicate webhook replay
    await seedBooking("bk_pout_sync_1", {
      bookingStatus: "COMPLETED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      attendanceStatus: "VERIFIED",
      customerCompletedConfirmed: true,
      expertCompletedConfirmed: true,
      completedAtEpochMs: completedMs,
      disputeWindowEndsAtEpochMs: completedMs + 86_400_000,
    });
    await db.collection("payout_requests").doc("pr_sync_1").set({
      expertId: "exp_exp_1",
      expertUid: "exp_1",
      expertName: "Vikram Mehta",
      amountPaise: 85000,
      payoutDestination: "UPI:vi***@okaxis",
      eligibleBookingIds: ["bk_pout_sync_1"],
      idempotencyKey: "idem_pr_sync_1",
      status: "PENDING",
      retryCount: 0,
    });
    const procSync = await postJson(
      `${serverHandle.baseUrl}/api/payouts/process`,
      { payoutRequestId: "pr_sync_1", newStatus: "PROCESSING", nowEpochMs: completedMs + 86_400_001 },
      "Bearer tok_admin_doc"
    );
    assert.equal(procSync.status, 200);
    assert.equal(procSync.data.status, "PAID");
    assert.equal(procSync.data.payoutReference, "UTR_SYNC_111");

    const whDupPaid = JSON.stringify({
      event_id: "evt_pout_sync_dup",
      event: "payout.processed",
      payload: {
        payout: {
          entity: {
            id: "pout_sync_1",
            utr: "UTR_SYNC_111",
            status: "processed",
          },
        },
      },
    });
    const whSigDup = computeRazorpayWebhookSignature({ rawBody: whDupPaid, webhookSecret: RZP_WEBHOOK_SECRET });
    const whResDup = await fetch(`${serverHandle.baseUrl}/api/webhooks/razorpay`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-razorpay-signature": whSigDup },
      body: whDupPaid,
    });
    assert.equal(whResDup.status, 200);
    const ledPaidSnap = await db
      .collection("ledger_entries")
      .where("payoutRequestId", "==", "pr_sync_1")
      .where("entryType", "==", "PAYOUT_COMPLETED")
      .get();
    assert.equal(ledPaidSnap.size, 1);
  });

  test("31. getClientIp extracts rightmost proxy-appended IP to resist X-Forwarded-For spoofing", () => {
    const { getClientIp } = require("./src/securityMiddleware");
    const ip = getClientIp({
      headers: { "x-forwarded-for": "1.2.3.4, ::ffff:203.0.113.45" },
      socket: { remoteAddress: "127.0.0.1" },
    });
    assert.equal(ip, "203.0.113.45");
  });

  test("32. 90-minute booking lock prevents overlapping booking starting 75 minutes later (-75m window)", async () => {
    await db.collection("experts").doc("exp_overlap").set({
      expertUid: "exp_1",
      fullName: "Vikram Overlap",
      verifiedGoogleEmail: "vikram@gmail.com",
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
      referralCode: "OVL10",
      timezone: "Asia/Kolkata",
    });
    await db.collection("services").doc("svc_90m").set({
      expertId: "exp_overlap",
      expertUid: "exp_1",
      title: "90m Deep Advisory",
      pricePaise: 200000,
      durationMinutes: 90,
      active: true,
    });
    await db.collection("services").doc("svc_30m").set({
      expertId: "exp_overlap",
      expertUid: "exp_1",
      title: "30m Quick Check",
      pricePaise: 100000,
      durationMinutes: 30,
      active: true,
    });

    // Customer A reserves 10:00 - 11:30 (600..690)
    const first = await postJson(
      `${serverHandle.baseUrl}/api/bookings/create`,
      {
        expertId: "exp_overlap",
        serviceId: "svc_90m",
        dateIso: "2028-06-15",
        startMinute: 600,
        topic: "Org transformation workshop",
        customerName: "Alice",
      },
      "Bearer tok_customer_a"
    );
    assert.equal(first.status, 200);

    // Customer B attempts 11:15 - 11:45 (675..705, 75 minutes after 10:00) -> MUST fail 409!
    const overlapping = await postJson(
      `${serverHandle.baseUrl}/api/bookings/create`,
      {
        expertId: "exp_overlap",
        serviceId: "svc_30m",
        dateIso: "2028-06-15",
        startMinute: 675,
        topic: "Overlapping follow-up",
        customerName: "Bob",
      },
      "Bearer tok_customer_b"
    );
    assert.equal(overlapping.status, 409);

    // Customer B booking at 11:30 (690..720) succeeds cleanly
    const adjacent = await postJson(
      `${serverHandle.baseUrl}/api/bookings/create`,
      {
        expertId: "exp_overlap",
        serviceId: "svc_30m",
        dateIso: "2028-06-15",
        startMinute: 690,
        topic: "Adjacent clean slot",
        customerName: "Bob",
      },
      "Bearer tok_customer_b"
    );
    assert.equal(adjacent.status, 200);
  });

  test("33. Dev backend without Razorpay keys returns requiresSdkCheckout=false and accepts dev sandbox callback, whereas configured keys or production reject it", async () => {
    await seedBooking("bk_dev_sandbox");
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      nodeEnv: "development",
      requireLiveRazorpay: false,
      razorpayKeyId: "rzp_test_placeholder",
      razorpayKeySecret: "",
      razorpayWebhookSecret: "",
      enableRazorpayApi: true,
    });

    const orderRes = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_dev_sandbox" },
      "Bearer tok_customer_a"
    );
    assert.equal(orderRes.status, 200);
    assert.equal(orderRes.data.requiresSdkCheckout, false);
    assert.equal(orderRes.data.testMode, true);

    const verifySandbox = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_dev_sandbox",
        razorpayOrderId: orderRes.data.orderId,
        razorpayPaymentId: "pay_srv_test_1234567890ab",
        razorpaySignature: "server_sandbox_callback",
        paymentMethod: "UPI_SANDBOX",
      },
      "Bearer tok_customer_a"
    );
    assert.equal(verifySandbox.status, 200);
    assert.equal(verifySandbox.data.serverVerified, false);
    assert.equal(verifySandbox.data.settlementStatus, "SANDBOX_SETTLED");

    // Production (requireLiveRazorpay=true) strictly rejects dev sandbox callback
    await seedBooking("bk_prod_reject");
    await serverHandle.close();
    serverHandle = await startTestHttpServer({
      db,
      authService,
      nodeEnv: "production",
      requireLiveRazorpay: true,
      razorpayKeyId: "rzp_test_placeholder",
      razorpayKeySecret: "",
      razorpayWebhookSecret: "",
      enableRazorpayApi: true,
    });
    const prodOrder = await postJson(
      `${serverHandle.baseUrl}/api/payments/create-order`,
      { bookingId: "bk_prod_reject" },
      "Bearer tok_customer_a"
    );
    assert.equal(prodOrder.status, 503);

    const prodVerify = await postJson(
      `${serverHandle.baseUrl}/api/payments/verify`,
      {
        bookingId: "bk_prod_reject",
        razorpayOrderId: "order_prod_1",
        razorpayPaymentId: "pay_srv_test_1234567890ab",
        razorpaySignature: "server_sandbox_callback",
      },
      "Bearer tok_customer_a"
    );
    assert.equal(prodVerify.status, 503);
  });
});


