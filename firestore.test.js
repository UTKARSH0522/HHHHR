const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_customer_1";
const BOB_UID = "bob_expert_2";
const CHARLIE_UID = "charlie_other_3";
const ADMIN_UID = "admin_utkarsh_9";
const ADMIN_EMAIL = "utkarsh.work.contact@gmail.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 2000) {
  return new Date(Date.now() - msOffset);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied across collections", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("experts").where("verificationStatus", "==", "APPROVED").get());
  await assertFails(unauth.collection("bookings").where("customerUid", "==", ALICE_UID).get());
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Unauth",
      role: "CUSTOMER",
      accountStatus: "ACTIVE",
      savedExpertIds: [],
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("Users, PII & email_audit_logs isolation: owner manages own profile, cannot escalate role or spoof logs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@gmail.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@gmail.com", email_verified: true }).firestore();
  const now = pastDate();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Sharma",
      verifiedGoogleEmail: "alice@gmail.com",
      emailVerified: true,
      role: "CUSTOMER",
      accountStatus: "ACTIVE",
      savedExpertIds: [],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject self-assigned ADMIN role
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      role: "ADMIN",
      accountStatus: "ACTIVE",
      savedExpertIds: [],
      createdAt: now,
      updatedAt: now,
    })
  );

  // PII write & email audit log
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("private").doc("info").set({
      userId: ALICE_UID,
      email: "alice@example.in",
      phone: "+919876543210",
      city: "Bengaluru",
      notifyBookings: true,
      notifyPayments: true,
      notifyReviews: true,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    aliceDb.collection("email_audit_logs").doc("ea_1").set({
      userId: ALICE_UID,
      previousEmail: "alice@gmail.com",
      newContactEmail: "alice@example.in",
      verifiedGoogleEmail: "alice@gmail.com",
      reverificationRequired: true,
      createdAt: now,
    })
  );

  // Bob cannot read Alice's user doc, private PII, or email audit log
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("private").doc("info").get());
  await assertFails(bobDb.collection("email_audit_logs").doc("ea_1").get());

  // Alice can partial-update savedExpertIds without touching accountStatus
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      savedExpertIds: ["exp_1"],
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      accountStatus: "SUSPENDED",
      updatedAt: pastDate(500),
    })
  );
});

test("Email Spoofing & RBAC: unverified admin email rejected; verified admin approves expert", async () => {
  const spoofAdminDb = testEnv
    .authenticatedContext(CHARLIE_UID, { email: ADMIN_EMAIL, email_verified: false })
    .firestore();
  const realAdminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@gmail.com", email_verified: true }).firestore();
  const now = pastDate();

  await assertSucceeds(
    bobDb.collection("experts").doc("exp_bob").set({
      expertUid: BOB_UID,
      fullName: "Bob Nair",
      verifiedGoogleEmail: "bob@gmail.com",
      googleEmailVerified: true,
      headline: "HRBP & Compensation Strategist",
      designation: "Head of People",
      yearsExperience: 11,
      bio: "Over 11 years leading HRBP and compensation architecture across Indian SaaS companies.",
      primarySpecialisation: "HR Business Partnering",
      specialisations: ["HR Business Partnering", "Payroll and Compensation"],
      skills: ["Compensation", "OKRs"],
      verificationStatus: "PENDING",
      accountStatus: "ACTIVE",
      demoProfile: false,
      startingPriceInr: 1500,
      referralCode: "BOBHR10",
      timezone: "Asia/Kolkata",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot self-approve; spoof admin cannot approve; real admin approves
  await assertFails(bobDb.collection("experts").doc("exp_bob").update({ verificationStatus: "APPROVED", updatedAt: pastDate(500) }));
  await assertFails(spoofAdminDb.collection("experts").doc("exp_bob").update({ verificationStatus: "APPROVED", updatedAt: pastDate(500) }));
  await assertSucceeds(realAdminDb.collection("experts").doc("exp_bob").update({ verificationStatus: "APPROVED", updatedAt: pastDate(500) }));
});

test("Zero-Trust Payment, Attendance, Dual Completion, Dispute, Review & Payout Parity", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@gmail.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@gmail.com", email_verified: true }).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID, { email: "charlie@gmail.com", email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true }).firestore();
  const now = pastDate(5000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("experts").doc("exp_bob").set({
      expertUid: BOB_UID,
      fullName: "Bob Nair",
      verifiedGoogleEmail: "bob@gmail.com",
      googleEmailVerified: true,
      headline: "HRBP & Compensation Strategist",
      designation: "Head of People",
      yearsExperience: 11,
      bio: "Over 11 years leading HRBP and compensation architecture across Indian SaaS companies.",
      primarySpecialisation: "HR Business Partnering",
      specialisations: ["HR Business Partnering"],
      skills: ["Compensation"],
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
      demoProfile: false,
      startingPriceInr: 1000,
      referralCode: "BOBHR10",
      timezone: "Asia/Kolkata",
      createdAt: now,
      updatedAt: now,
    });
  });

  // Service creation
  await assertSucceeds(
    bobDb.collection("services").doc("svc_1").set({
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      title: "30-min Compensation Review",
      description: "Detailed salary benchmarking and ESOP negotiation guidance.",
      specialisation: "Payroll and Compensation",
      durationMinutes: 30,
      priceInr: 1000,
      pricePaise: 100000,
      consultationFormat: "Video Consultation",
      active: true,
      demoService: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Tampered price rejected
  await assertFails(
    aliceDb.collection("bookings").doc("bk_tampered").set({
      customerUid: ALICE_UID,
      customerName: "Alice Sharma",
      verifiedCustomerGoogleEmail: "alice@gmail.com",
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      verifiedExpertGoogleEmail: "bob@gmail.com",
      serviceId: "svc_1",
      serviceTitle: "30-min Compensation Review",
      durationMinutes: 30,
      dateIso: "2026-12-01",
      startMinute: 600,
      endMinute: 630,
      slotKey: "exp_bob_2026-12-01_600",
      timezone: "Asia/Kolkata",
      topic: "Tampered price test",
      referralType: "PLATFORM",
      amountPaise: 10000,
      commissionRateBps: 1500,
      commissionPaise: 1500,
      gatewayFeePaise: 200,
      taxDeductionPaise: 0,
      expertEarningPaise: 8500,
      refundedAmountPaise: 0,
      bookingStatus: "PENDING_PAYMENT",
      paymentStatus: "CREATED",
      holdExpiresAtEpochMs: Date.now() + 600000,
      reviewed: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Valid booking hold created atomically with slot_lock by Alice
  const createBookingBatch = aliceDb.batch();
  createBookingBatch.set(aliceDb.collection("bookings").doc("bk_1"), {
    customerUid: ALICE_UID,
    customerName: "Alice Sharma",
    verifiedCustomerGoogleEmail: "alice@gmail.com",
    expertId: "exp_bob",
    expertUid: BOB_UID,
    expertName: "Bob Nair",
    verifiedExpertGoogleEmail: "bob@gmail.com",
    serviceId: "svc_1",
    serviceTitle: "30-min Compensation Review",
    durationMinutes: 30,
    dateIso: "2026-12-01",
    startMinute: 600,
    endMinute: 630,
    slotKey: "exp_bob_2026-12-01_600",
    timezone: "Asia/Kolkata",
    topic: "Negotiating senior HRBP offer",
    referralType: "PLATFORM",
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
    attendanceStatus: "NOT_STARTED",
    attendanceVerificationSource: "NONE",
    payoutEligibilityStatus: "NOT_ELIGIBLE",
    disputeStatus: "NONE",
    calendarSyncStatus: "NOT_SYNCED",
    conferenceRequestId: "conf_bk_1",
    meetingAccessMode: "TRUSTED_INVITEES_ONLY",
    checkInPin: "482910",
    customerCheckInAtMs: 0,
    expertCheckInAtMs: 0,
    customerJoinedMinutes: 0,
    expertJoinedMinutes: 0,
    overlapMinutes: 0,
    customerCompletedConfirmed: false,
    expertCompletedConfirmed: false,
    completedAtEpochMs: 0,
    disputeWindowEndsAtEpochMs: 0,
    holdExpiresAtEpochMs: Date.now() + 600000,
    reviewed: false,
    createdAt: now,
    updatedAt: now,
  });
  createBookingBatch.set(aliceDb.collection("slot_locks").doc("exp_bob_2026-12-01_600"), {
    expertId: "exp_bob",
    expertUid: BOB_UID,
    holderUid: ALICE_UID,
    bookingId: "bk_1",
    dateIso: "2026-12-01",
    startMinute: 600,
    endMinute: 630,
    bookingStatus: "PENDING_PAYMENT",
    holdExpiresAtEpochMs: Date.now() + 600000,
    updatedAt: now,
  });
  await assertSucceeds(createBookingBatch.commit());

  // SECURITY: Customer CANNOT forge live non-sandbox payment (testMode: false / serverVerified: true)
  await assertFails(
    aliceDb.collection("payments").doc("pay_bk_1").set({
      bookingId: "bk_1",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      razorpayOrderId: "order_forged",
      razorpayPaymentId: "pay_forged",
      paymentMethod: "UPI",
      currency: "INR",
      amountPaise: 100000,
      commissionPaise: 15000,
      gatewayFeePaise: 2000,
      taxDeductionPaise: 0,
      expertEarningPaise: 85000,
      refundedAmountPaise: 0,
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SETTLED",
      serverVerified: true,
      idempotencyKey: "idem_bk_1",
      testMode: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // SECURITY: Customer CANNOT confirm booking without a payment doc in the same batch!
  await assertFails(
    aliceDb.collection("bookings").doc("bk_1").update({
      bookingStatus: "CONFIRMED",
      paymentStatus: "SUCCESS",
      providerSettlementStatus: "SANDBOX_SETTLED",
      updatedAt: pastDate(1500),
    })
  );

  // Legitimate Sandbox payment batch (payments + booking confirm + ledger entries) succeeds!
  const payBatch = aliceDb.batch();
  payBatch.set(aliceDb.collection("payments").doc("pay_bk_1"), {
    bookingId: "bk_1",
    customerUid: ALICE_UID,
    expertUid: BOB_UID,
    razorpayOrderId: "order_rzp_sandbox_bk_1",
    razorpayPaymentId: "pay_rzp_sandbox_123",
    paymentMethod: "UPI",
    currency: "INR",
    amountPaise: 100000,
    commissionPaise: 15000,
    gatewayFeePaise: 2000,
    taxDeductionPaise: 0,
    expertEarningPaise: 85000,
    refundedAmountPaise: 0,
    paymentStatus: "SUCCESS",
    providerSettlementStatus: "SANDBOX_SETTLED",
    serverVerified: false,
    idempotencyKey: "idem_bk_1",
    testMode: true,
    createdAt: now,
    updatedAt: now,
  });
  payBatch.update(aliceDb.collection("bookings").doc("bk_1"), {
    bookingStatus: "CONFIRMED",
    paymentStatus: "SUCCESS",
    providerSettlementStatus: "SANDBOX_SETTLED",
    payoutEligibilityStatus: "NOT_ELIGIBLE",
    calendarSyncStatus: "PENDING_BACKEND_SYNC",
    updatedAt: pastDate(1000),
  });
  payBatch.set(aliceDb.collection("ledger_entries").doc("led_earn_bk_1"), {
    entryType: "EXPERT_EARNING",
    direction: "CREDIT",
    entryStatus: "PENDING",
    bookingId: "bk_1",
    paymentId: "pay_bk_1",
    customerUid: ALICE_UID,
    expertUid: BOB_UID,
    amountPaise: 85000,
    currency: "INR",
    idempotencyKey: "led_earn_bk_1",
    description: "Pending expert earning",
    createdAt: now,
  });
  await assertSucceeds(payBatch.commit());

  // SECURITY: Expert CANNOT fabricate standalone CREDIT EXPERT_EARNING or DISPUTE_RELEASE ledger entries
  await assertFails(
    bobDb.collection("ledger_entries").doc("led_forged_1").set({
      entryType: "EXPERT_EARNING",
      direction: "CREDIT",
      entryStatus: "SETTLED",
      bookingId: "bk_1",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      amountPaise: 500000,
      currency: "INR",
      description: "Forged credit",
      createdAt: now,
    })
  );
  await assertFails(
    bobDb.collection("ledger_entries").doc("led_forged_2").set({
      entryType: "DISPUTE_RELEASE",
      direction: "CREDIT",
      entryStatus: "SETTLED",
      bookingId: "bk_1",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      amountPaise: 85000,
      currency: "INR",
      description: "Forged release",
      createdAt: now,
    })
  );

  // SECURITY: Bob CANNOT fake Google Meet REST API verification or unlock payout directly
  await assertFails(
    bobDb.collection("bookings").doc("bk_1").update({
      attendanceStatus: "VERIFIED",
      attendanceVerificationSource: "GOOGLE_MEET_REST_API",
      updatedAt: pastDate(900),
    })
  );
  await assertFails(
    bobDb.collection("attendance_records").doc("bk_1").set({
      bookingId: "bk_1",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      verificationSource: "GOOGLE_MEET_REST_API",
      attendanceStatus: "VERIFIED",
      updatedAt: pastDate(900),
    })
  );

  // SECURITY: Bob CANNOT jump straight to COMPLETED alone (single-party completion blocked)
  await assertFails(
    bobDb.collection("bookings").doc("bk_1").update({
      expertCompletedConfirmed: true,
      bookingStatus: "COMPLETED",
      completedAtEpochMs: Date.now(),
      disputeWindowEndsAtEpochMs: Date.now() + 86400000,
      updatedAt: pastDate(850),
    })
  );

  // Dual-party completion step 1: Bob confirms -> COMPLETION_PENDING
  await assertSucceeds(
    bobDb.collection("bookings").doc("bk_1").update({
      expertCompletedConfirmed: true,
      bookingStatus: "COMPLETION_PENDING",
      attendanceStatus: "MANUAL_REVIEW",
      attendanceNotes: "Completed without Google Meet REST API conference logs",
      updatedAt: pastDate(800),
    })
  );

  // SECURITY: Alice CANNOT backdate disputeWindowEndsAtEpochMs to bypass 24h dispute window!
  await assertFails(
    aliceDb.collection("bookings").doc("bk_1").update({
      customerCompletedConfirmed: true,
      bookingStatus: "COMPLETED",
      completedAtEpochMs: Date.now(),
      disputeWindowEndsAtEpochMs: 1,
      updatedAt: pastDate(750),
    })
  );

  // Dual-party completion step 2: Alice confirms with valid >=24h dispute window -> COMPLETED
  await assertSucceeds(
    aliceDb.collection("bookings").doc("bk_1").update({
      customerCompletedConfirmed: true,
      bookingStatus: "COMPLETED",
      completedAtEpochMs: Date.now(),
      disputeWindowEndsAtEpochMs: Date.now() + 86400000,
      updatedAt: pastDate(700),
    })
  );

  // Alice opens a dispute -> booking becomes DISPUTED, payout ON_HOLD
  await assertSucceeds(
    aliceDb.collection("disputes").doc("bk_1").set({
      bookingId: "bk_1",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      raisedByUid: ALICE_UID,
      reason: "Expert joined 15 minutes late",
      evidenceSummary: "Check-in timestamps",
      status: "OPEN",
      requestedRefundPaise: 100000,
      approvedRefundPaise: 0,
      createdAt: pastDate(600),
      updatedAt: pastDate(600),
    })
  );
  await assertSucceeds(
    aliceDb.collection("bookings").doc("bk_1").update({
      bookingStatus: "DISPUTED",
      disputeStatus: "OPEN",
      disputeReason: "Expert joined 15 minutes late",
      payoutEligibilityStatus: "ON_HOLD",
      updatedAt: pastDate(600),
    })
  );

  // Alice CANNOT resolve her own dispute; Admin resolves dispute + writes attendance_records
  await assertFails(
    aliceDb.collection("disputes").doc("bk_1").update({
      status: "RESOLVED_REFUND_FULL",
      approvedRefundPaise: 100000,
      updatedAt: pastDate(500),
    })
  );
  await assertSucceeds(
    adminDb.collection("attendance_records").doc("bk_1").set({
      bookingId: "bk_1",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      verificationSource: "ADMIN_MANUAL_REVIEW",
      attendanceStatus: "VERIFIED",
      customerDurationMinutes: 30,
      expertDurationMinutes: 30,
      overlapMinutes: 30,
      minimumRequiredMinutes: 10,
      evaluatorSummary: "ADMIN_APPROVED: Verified attendance",
      updatedAt: pastDate(500),
    })
  );
  await assertSucceeds(
    adminDb.collection("disputes").doc("bk_1").update({
      status: "RESOLVED_RELEASE_EXPERT",
      approvedRefundPaise: 0,
      resolutionNote: "Verified full session",
      resolvedByUid: ADMIN_UID,
      updatedAt: pastDate(450),
    })
  );
  await assertSucceeds(
    adminDb.collection("bookings").doc("bk_1").update({
      bookingStatus: "COMPLETED",
      disputeStatus: "RESOLVED_RELEASE_EXPERT",
      payoutEligibilityStatus: "ELIGIBLE",
      updatedAt: pastDate(450),
    })
  );

  // Webhook events cannot be written or read by non-admin clients
  await assertFails(charlieDb.collection("webhook_events").doc("evt_1").get());
  await assertFails(
    aliceDb.collection("webhook_events").doc("evt_1").set({
      eventId: "evt_1",
      eventType: "payment.captured",
      processedAt: now,
    })
  );

  // SECURITY: Bob CANNOT create payout request with empty eligibleBookingIds or pre-filled payoutReference
  await assertFails(
    bobDb.collection("payout_requests").doc("pay_req_empty").set({
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      amountPaise: 85000,
      payoutDestination: "bob@upi",
      eligibleBookingIds: [],
      status: "PENDING",
      retryCount: 0,
      createdAt: pastDate(300),
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    bobDb.collection("payout_requests").doc("pay_req_forged_utr").set({
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      amountPaise: 85000,
      payoutDestination: "bob@upi",
      eligibleBookingIds: ["bk_1"],
      payoutReference: "UTR_FAKE",
      status: "PENDING",
      retryCount: 0,
      createdAt: pastDate(300),
      updatedAt: pastDate(300),
    })
  );

  // Bob requests payout -> Admin marks PROCESSING -> PAID -> Paid payout cannot be re-mutated!
  await assertSucceeds(
    bobDb.collection("payout_requests").doc("pay_req_1").set({
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      amountPaise: 85000,
      payoutDestination: "bob@upi",
      eligibleBookingIds: ["bk_1"],
      idempotencyKey: "payout_bob_1",
      status: "PENDING",
      retryCount: 0,
      createdAt: pastDate(300),
      updatedAt: pastDate(300),
    })
  );
  await assertSucceeds(
    adminDb.collection("payout_requests").doc("pay_req_1").update({
      status: "PROCESSING",
      processedByUid: ADMIN_UID,
      updatedAt: pastDate(200),
    })
  );
  await assertSucceeds(
    adminDb.collection("payout_requests").doc("pay_req_1").update({
      status: "PAID",
      payoutReference: "UTR987654321",
      processedByUid: ADMIN_UID,
      updatedAt: pastDate(100),
    })
  );
  await assertFails(
    adminDb.collection("payout_requests").doc("pay_req_1").update({
      status: "PENDING",
      updatedAt: pastDate(50),
    })
  );
});

test("Phase 1.5 Security: Disabled admin immediate revocation, slot_lock hijack, commission tamper, calendar/attendance spoofing & ledger duplication blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@gmail.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@gmail.com", email_verified: true }).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID, { email: "charlie@gmail.com", email_verified: true }).firestore();
  const disabledAdminDb = testEnv
    .authenticatedContext("disabled_admin_1", { email: ADMIN_EMAIL, email_verified: true, admin: true, role: "ADMIN" })
    .firestore();
  const now = pastDate(5000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminRoot = ctx.firestore();
    await adminRoot.collection("admins").doc("disabled_admin_1").set({
      email: ADMIN_EMAIL,
      active: false,
    });
    await adminRoot.collection("experts").doc("exp_bob").set({
      expertUid: BOB_UID,
      fullName: "Bob Nair",
      verifiedGoogleEmail: "bob@gmail.com",
      googleEmailVerified: true,
      headline: "HRBP & Compensation Strategist",
      designation: "Head of People",
      yearsExperience: 11,
      bio: "Over 11 years leading HRBP and compensation architecture across Indian SaaS companies.",
      primarySpecialisation: "HR Business Partnering",
      specialisations: ["HR Business Partnering"],
      skills: ["Compensation"],
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
      demoProfile: false,
      startingPriceInr: 1000,
      referralCode: "BOBHR10",
      timezone: "Asia/Kolkata",
      createdAt: now,
      updatedAt: now,
    });
    await adminRoot.collection("services").doc("svc_1").set({
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      title: "30-min Compensation Review",
      description: "Detailed salary benchmarking and ESOP negotiation guidance.",
      specialisation: "Payroll and Compensation",
      durationMinutes: 30,
      priceInr: 1000,
      pricePaise: 100000,
      consultationFormat: "Video Consultation",
      active: true,
      demoService: false,
      createdAt: now,
      updatedAt: now,
    });
  });

  // 1. Disabled admin is IMMEDIATELY blocked despite custom claim admin:true and bootstrap email
  await assertFails(
    disabledAdminDb.collection("experts").doc("exp_bob").update({
      accountStatus: "SUSPENDED",
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(disabledAdminDb.collection("audit_logs").get());

  // 2. Tampered commissionRateBps (e.g., 0% instead of 15%) is rejected even with matching slot_lock
  const badCommBatch = aliceDb.batch();
  badCommBatch.set(aliceDb.collection("bookings").doc("bk_bad_comm"), {
    customerUid: ALICE_UID,
    customerName: "Alice",
    verifiedCustomerGoogleEmail: "alice@gmail.com",
    expertId: "exp_bob",
    expertUid: BOB_UID,
    expertName: "Bob Nair",
    verifiedExpertGoogleEmail: "bob@gmail.com",
    serviceId: "svc_1",
    serviceTitle: "30-min Compensation Review",
    durationMinutes: 30,
    dateIso: "2026-12-05",
    startMinute: 600,
    endMinute: 630,
    slotKey: "exp_bob_2026-12-05_600",
    timezone: "Asia/Kolkata",
    topic: "Bad commission split",
    referralType: "PLATFORM",
    amountPaise: 100000,
    commissionRateBps: 0,
    commissionPaise: 0,
    gatewayFeePaise: 2000,
    taxDeductionPaise: 0,
    expertEarningPaise: 100000,
    refundedAmountPaise: 0,
    bookingStatus: "PENDING_PAYMENT",
    paymentStatus: "CREATED",
    holdExpiresAtEpochMs: Date.now() + 600000,
    reviewed: false,
    createdAt: now,
    updatedAt: now,
  });
  badCommBatch.set(aliceDb.collection("slot_locks").doc("exp_bob_2026-12-05_600"), {
    expertId: "exp_bob",
    expertUid: BOB_UID,
    holderUid: ALICE_UID,
    bookingId: "bk_bad_comm",
    dateIso: "2026-12-05",
    startMinute: 600,
    endMinute: 630,
    bookingStatus: "PENDING_PAYMENT",
    holdExpiresAtEpochMs: Date.now() + 600000,
    updatedAt: now,
  });
  await assertFails(badCommBatch.commit());

  // 3. Alice creates legitimate atomic booking + active slot_lock
  const validBatch = aliceDb.batch();
  validBatch.set(aliceDb.collection("bookings").doc("bk_valid_lock"), {
    customerUid: ALICE_UID,
    customerName: "Alice",
    verifiedCustomerGoogleEmail: "alice@gmail.com",
    expertId: "exp_bob",
    expertUid: BOB_UID,
    expertName: "Bob Nair",
    verifiedExpertGoogleEmail: "bob@gmail.com",
    serviceId: "svc_1",
    serviceTitle: "30-min Compensation Review",
    durationMinutes: 30,
    dateIso: "2026-12-05",
    startMinute: 600,
    endMinute: 630,
    slotKey: "exp_bob_2026-12-05_600",
    timezone: "Asia/Kolkata",
    topic: "Compensation strategy",
    referralType: "PLATFORM",
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
    attendanceStatus: "NOT_STARTED",
    attendanceVerificationSource: "NONE",
    payoutEligibilityStatus: "NOT_ELIGIBLE",
    holdExpiresAtEpochMs: Date.now() + 600000,
    reviewed: false,
    createdAt: now,
    updatedAt: now,
  });
  validBatch.set(aliceDb.collection("slot_locks").doc("exp_bob_2026-12-05_600"), {
    expertId: "exp_bob",
    expertUid: BOB_UID,
    holderUid: ALICE_UID,
    bookingId: "bk_valid_lock",
    dateIso: "2026-12-05",
    startMinute: 600,
    endMinute: 630,
    bookingStatus: "PENDING_PAYMENT",
    holdExpiresAtEpochMs: Date.now() + 600000,
    updatedAt: now,
  });
  await assertSucceeds(validBatch.commit());

  // 4. Charlie CANNOT hijack Alice's unexpired slot_lock!
  await assertFails(
    charlieDb.collection("slot_locks").doc("exp_bob_2026-12-05_600").update({
      holderUid: CHARLIE_UID,
      bookingId: "bk_hijack",
      bookingStatus: "PENDING_PAYMENT",
      holdExpiresAtEpochMs: Date.now() + 600000,
      updatedAt: pastDate(500),
    })
  );

  // Confirm booking and verify anti-spoofing rules
  const payBatch = aliceDb.batch();
  payBatch.set(aliceDb.collection("payments").doc("pay_bk_valid_lock"), {
    bookingId: "bk_valid_lock",
    customerUid: ALICE_UID,
    expertUid: BOB_UID,
    razorpayOrderId: "order_sb_valid",
    razorpayPaymentId: "pay_sb_valid",
    paymentMethod: "UPI",
    currency: "INR",
    amountPaise: 100000,
    commissionPaise: 15000,
    gatewayFeePaise: 2000,
    taxDeductionPaise: 0,
    expertEarningPaise: 85000,
    refundedAmountPaise: 0,
    paymentStatus: "SUCCESS",
    providerSettlementStatus: "SANDBOX_SETTLED",
    serverVerified: false,
    idempotencyKey: "idem_bk_valid_lock",
    testMode: true,
    createdAt: now,
    updatedAt: now,
  });
  payBatch.update(aliceDb.collection("bookings").doc("bk_valid_lock"), {
    bookingStatus: "CONFIRMED",
    paymentStatus: "SUCCESS",
    providerSettlementStatus: "SANDBOX_SETTLED",
    payoutEligibilityStatus: "NOT_ELIGIBLE",
    calendarSyncStatus: "PENDING_BACKEND_SYNC",
    updatedAt: pastDate(500),
  });
  // Arbitrarily named ledger doc IDs must be rejected; deterministic IDs succeed
  await assertSucceeds(payBatch.commit());

  await assertFails(
    aliceDb.collection("ledger_entries").doc("led_dup_arbitrary").set({
      entryType: "EXPERT_EARNING",
      direction: "CREDIT",
      entryStatus: "PENDING",
      bookingId: "bk_valid_lock",
      paymentId: "pay_bk_valid_lock",
      customerUid: ALICE_UID,
      expertUid: BOB_UID,
      amountPaise: 85000,
      currency: "INR",
      description: "Duplicate arbitrary ledger doc",
      createdAt: now,
    })
  );

  // 5. Bob CANNOT spoof googleCalendarEventId / SYNCED or ADMIN_APPROVED attendance note
  await assertFails(
    bobDb.collection("bookings").doc("bk_valid_lock").update({
      googleCalendarEventId: "evt_forged",
      calendarSyncStatus: "SYNCED",
      meetingUrl: "https://meet.google.com/abc-defg-hij",
      bookingStatus: "SCHEDULED",
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    bobDb.collection("bookings").doc("bk_valid_lock").update({
      bookingStatus: "IN_PROGRESS",
      expertCheckInAtMs: Date.now(),
      attendanceVerificationSource: "SUPPLEMENTARY_CHECKIN_FALLBACK",
      attendanceStatus: "MANUAL_REVIEW",
      attendanceNotes: "ADMIN_APPROVED: Self-unlocked payout",
      updatedAt: pastDate(200),
    })
  );

  // 6. Direct client writes to audit_logs and payout status escalation are blocked
  await assertFails(
    bobDb.collection("audit_logs").doc("audit_spoof").set({
      actorUid: BOB_UID,
      actorEmail: "bob@gmail.com",
      action: "FAKE_ADMIN_LOG",
      targetType: "booking",
      targetId: "bk_valid_lock",
      details: "forged",
      createdAt: now,
    })
  );
  await assertFails(
    bobDb.collection("payout_requests").doc("pr_self_paid").set({
      expertId: "exp_bob",
      expertUid: BOB_UID,
      expertName: "Bob Nair",
      amountPaise: 85000,
      payoutDestination: "bob@upi",
      eligibleBookingIds: ["bk_valid_lock"],
      status: "PAID",
      retryCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Production hardening: FCM tokens owner-only CRUD, payout_secrets deny-all, expert payout KYC lock", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@gmail.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@gmail.com", email_verified: true }).firestore();
  const now = pastDate(5000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("experts").doc("exp_bob").set({
      expertUid: BOB_UID,
      fullName: "Bob Nair",
      verifiedGoogleEmail: "bob@gmail.com",
      googleEmailVerified: true,
      headline: "HRBP & Compensation Strategist",
      designation: "Head of People",
      yearsExperience: 11,
      bio: "Over 11 years leading HRBP and compensation architecture across Indian SaaS companies.",
      primarySpecialisation: "HR Business Partnering",
      specialisations: ["HR Business Partnering"],
      skills: ["Compensation"],
      verificationStatus: "APPROVED",
      accountStatus: "ACTIVE",
      demoProfile: false,
      startingPriceInr: 1000,
      referralCode: "BOBHR10",
      timezone: "Asia/Kolkata",
      createdAt: now,
      updatedAt: now,
    });
  });

  // 1. Alice can register and update her own FCM token doc; Bob cannot read/write Alice's FCM tokens
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_hash_1").set({
      userId: ALICE_UID,
      token: "fcm_token_sample_abcdefghijklmnopqrstuvwxyz_123456",
      platform: "android",
      deviceId: "Pixel 9",
      enabled: true,
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_hash_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("fcm_tokens").doc("tok_hash_2").set({
      userId: ALICE_UID,
      token: "fcm_token_forged_abcdefghijklmnopqrstuvwxyz_123456",
      platform: "android",
      deviceId: "Spoof",
      enabled: true,
      updatedAt: pastDate(1000),
    })
  );

  // 2. Backend-only payout_secrets cannot be read or written by any client (even the owning expert Bob)
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("experts").doc("exp_bob").collection("payout_secrets").doc("config").set({
      expertUid: BOB_UID,
      vpaAddress: "bob@okaxis",
      razorpayFundAccountId: "fa_secret_123",
    });
  });
  await assertFails(
    bobDb.collection("experts").doc("exp_bob").collection("payout_secrets").doc("config").get()
  );
  await assertFails(
    bobDb.collection("experts").doc("exp_bob").collection("payout_secrets").doc("config").set({
      expertUid: BOB_UID,
      vpaAddress: "hacked@upi",
    })
  );

  // 3. Owning expert cannot self-modify server-managed RazorpayX payout fields on expert profile
  await assertFails(
    bobDb.collection("experts").doc("exp_bob").update({
      razorpayFundAccountId: "fa_spoofed",
      payoutKycStatus: "LINKED_READY",
      updatedAt: pastDate(1000),
    })
  );
});

