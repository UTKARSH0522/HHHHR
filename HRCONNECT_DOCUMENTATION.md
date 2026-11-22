# HRConnect — Complete Feature, Architecture & End-to-End Flow Documentation

> **Platform:** India-First HR Professional Consultation & Income Marketplace  
> **Client Stack:** Android (Kotlin, Jetpack Compose, Material 3, Navigation Compose, StateFlow/MVVM)  
> **Cloud Database & Auth:** Firebase Authentication (Google Sign-In via Jetpack Credential Manager) + Named Cloud Firestore (`ai-studio-android-hrconnec-b03b6447-7546-4ed2-bdef-5257728737bb`)  
> **Reference Backend Service:** Node.js HTTP Server (`/backend`) for Live Razorpay Signatures/Webhooks & Google Calendar / Meet REST APIs  
> **Currency & Timezone:** `INR` (stored strictly in integer `paise`, `100 paise = ₹1`) · `Asia/Kolkata` (`IST`)  

---

## 1. System Architecture Overview

HRConnect uses a **zero-trust client + Firestore + optional Node.js verification backend** architecture:

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Android App (Kotlin + Jetpack Compose)                                  │
│  ├─ AuthScreen (Google Sign-In / Auto Sign-In via Credential Manager)   │
│  ├─ HomeMarketplaceScreen (Explore HR Experts, Filters, Saved Experts)  │
│  ├─ ExpertProfileBookingScreen (Bio, Reviews, Services, Slot Hold)      │
│  ├─ CustomerDashboardScreen (My Hub: Bookings, Checkout, Disputes)      │
│  ├─ ExpertPortalScreen (Onboarding, Services, Schedule, 6-Bucket Wallet)│
│  └─ AdminDashboardScreen (Approvals, Inspector, Refunds, Payouts, Rules)│
│                                                                         │
│  Core Business Engines (Pure Kotlin, Unit-Tested):                      │
│  ├─ FinancialLedgerEngine (Splits, 6-Bucket Wallet, Payout Eligibility) │
│  ├─ AttendanceVerificationEngine (Meet REST API Scenarios A–G)          │
│  ├─ ConsultationLifecycleEngine (State Machine & Cancellation Policy)   │
│  └─ RazorpaySignatureVerifier (HMAC-SHA256 & Consistency Verification)  │
└───────────────┬────────────────────────────────────────┬────────────────┘
                │ Direct Authenticated Firestore SDK     │ HTTPS JSON (Optional Live Mode)
                ▼                                        ▼
┌───────────────────────────────┐        ┌────────────────────────────────────────┐
│ Cloud Firestore               │        │ Node.js Backend Reference (`/backend`) │
│  • Zero-trust firestore.rules │        │  • /api/payments/create-order          │
│  • Immutable ledger_entries   │        │  • /api/payments/verify                │
│  • Concurrency slot_locks     │        │  • /api/webhooks/razorpay              │
│  • Server-only webhook_events │        │  • /api/bookings/sync-calendar         │
│  • 15 HR specialisations        │        │  • /api/bookings/sync-meet-attendance  │
└───────────────────────────────┘        │  • /api/refunds/initiate               │
                                         │  • /api/payouts/process                │
                                         └────────────────────────────────────────┘
```

---

## 2. User Roles & Access Control

| Role | Identification | Permissions & Surfaces |
| :--- | :--- | :--- |
| **Customer** | Authenticated Google Sign-In user (`role = CUSTOMER`) | Discover experts, bookmark experts, hold slots, pay in Test/Live mode, cancel bookings, check in with PIN, confirm completion, open disputes, submit 1 verified review per completed consultation, manage private contact info. |
| **HR Expert** | Authenticated user with an `experts` profile (`PENDING`, `APPROVED`, `REJECTED`) | Submit/resubmit verification application, publish 15–90 min consultation services (once `APPROVED`), manage weekly availability + breaks + date exceptions, share Meet link / verify check-in, confirm completion, track 6-bucket wallet, request INR payouts. |
| **Platform Admin** | Verified Google email matching `utkarsh.work.contact@gmail.com` (`email_verified == true`) or UID in `admins/{uid}` | Access **Admin** tab: approve/reject experts, suspend/reactivate accounts, inspect every booking, override attendance (`ADMIN_APPROVED`/`ADMIN_REJECTED`), resolve disputes & issue full/partial refunds, settle/fail/reject payouts, configure commission/fee/window settings, moderate reviews, seed demo marketplace, inspect `audit_logs`. |

---

## 3. Navigation & Screen Inventory

### Top-Level App Routes (`MainActivity.kt`)
1. **Unauthenticated Gate**: `AuthScreen` (renders when `Firebase.auth.currentUser == null`)
2. **Authenticated Shell** (Bottom `NavigationBar` on compact screens; `NavigationRail` on medium/expanded screens):
   - `explore` → `HomeMarketplaceScreen`
   - `hub` → `CustomerDashboardScreen`
   - `expert_portal` → `ExpertPortalScreen`
   - `admin` → `AdminDashboardScreen` *(rendered only when `viewModel.isAdminUser == true`)*
   - `expert_detail/{expertId}` → `ExpertProfileBookingScreen`

---

## 4. Detailed Feature & Flow Specifications

### Flow 1: Authentication, Verified Email & Privacy Isolation
- **Files**: `AuthScreen.kt`, `GoogleAuthHelper.kt`, `UserAccountRepository.kt`
- **Step-by-Step Flow**:
  1. App attempts silent auto-sign-in via `GetGoogleIdOption`; tapping **Sign In** triggers `GetSignInWithGoogleOption` via Jetpack `CredentialManager`.
  2. Anonymous authentication (`signInAnonymously`) and email/password auth are disabled.
  3. On first sign-in, `ensureUserAccount` creates:
     - `users/{uid}`: public/account metadata (`displayName`, `verifiedGoogleEmail`, `emailVerified`, `role`, `accountStatus`, `savedExpertIds`).
     - `users/{uid}/private/info`: private contact details (`email`, `phone`, `city`, `notifyBookings`, `notifyPayments`, `notifyReviews`) readable only by the owner or Admin.
  4. If a user changes their contact email in **Profile & Prefs**, an immutable audit entry is recorded in `email_audit_logs` noting `previousEmail`, `newContactEmail`, `verifiedGoogleEmail`, and `reverificationRequired`.

---

### Flow 2: Marketplace Discovery & Filtering (`HomeMarketplaceScreen`)
- **Features**:
  - Live metrics cards (count of Approved Experts, Active Services, Verified Reviews—never hardcoded).
  - Real-time search across expert name, headline, bio, skills, specialisations, and service titles/descriptions.
  - Bottom-sheet & chip filters:
    - **HR Specialisation** (15 categories: Talent Acquisition, HRBP, Payroll & Compensation, Labour Law & POSH, Employee Relations, Performance Management, L&D, HR Ops, HR Strategy, Career Guidance, Resume/LinkedIn, Interview Prep, Startup HR, Org Development, Employee Engagement)
    - **Minimum Years of Experience**
    - **Maximum Price (INR)**
    - **Minimum Average Rating**
    - **Session Duration** (`15`, `30`, `45`, `60`, `90` min)
    - **Saved Experts Only** toggle

---

### Flow 3: Expert Application, Service Catalogue & Availability (`ExpertPortalScreen`)
Tabs inside **Expert Portal**:
1. **Overview**: Verification badge (`PENDING`, `APPROVED`, `REJECTED`), referral code card (`10%` fee vs `15%` marketplace fee), quick KPIs, and client reviews.
2. **My Services**: Create/edit/toggle consultation packages (`15`, `30`, `45`, `60`, `90` min; min `₹100` to `₹2,50,000`; format: `Video Consultation`, `Audio Call`, `Resume & Async Review`; optional preparation instructions). *Blocked by Firestore rules until expert `verificationStatus == APPROVED` and `accountStatus == ACTIVE`.*
3. **Availability**:
   - Configure Monday–Sunday rules (`enabled`, `startMinute`, `endMinute`, `breakStartMinute`, `breakEndMinute` in `Asia/Kolkata`).
   - Add/delete specific **Date Exceptions** (block leave/holiday dates).
4. **Bookings**: View incoming customer consultations, inspect `verifiedCustomerGoogleEmail`, sync Google Calendar/Meet, update/share Google Meet URL, verify attendance via Check-In PIN, confirm completion, or cancel/dispute.
5. **Earnings & Payouts**: Transparent **6-Bucket Wallet**, per-booking payout eligibility blocker breakdown, immutable ledger history, and payout withdrawal request form.
6. **Application / Profile**: Submit or edit HR credentials (headline, designation, org, years of experience, bio, skills, LinkedIn URL, intro video URL, referral code, starting INR price). Rejected experts can edit and resubmit (`REJECTED -> PENDING`).

---

### Flow 4: Dynamic Slot Computation, Concurrency Lock & Booking Hold (`ExpertProfileBookingScreen`)
- **Step-by-Step Flow**:
  1. Customer selects an expert (`ExpertProfileBookingScreen`) and browses active services & verified reviews (with rating histogram).
  2. Customer picks a date (next 14 IST days). `MarketplaceRepository.computeAvailableSlots(...)` dynamically generates valid intervals matching:
     - Expert's `AvailabilityRule` for that weekday
     - Excludes break window (`breakStartMinute..breakEndMinute`)
     - Blocks entire date if `AvailabilityException(unavailable = true)` exists
     - Excludes past slots (`now + 5 min` buffer)
     - Excludes slots with an active `SlotLock.isBlocking(nowMs)`:
       - Blocked if status is `CONFIRMED`, `SCHEDULED`, `IN_PROGRESS`, `ATTENDANCE_PENDING`, `COMPLETION_PENDING`, `COMPLETED`, or `DISPUTED`
       - Blocked if `PENDING_PAYMENT` AND `nowMs <= holdExpiresAtEpochMs`
       - **Automatically available again** if hold expired (`nowMs > holdExpiresAtEpochMs`) or booking was `CANCELLED` / `REFUNDED`
  3. Customer enters consultation topic and optional **Expert Referral Code**:
     - Valid matching `referralCode` on the expert profile → `EXPERT_REFERRAL` (**10% commission / 1000 bps**)
     - No/unmatched referral code → `PLATFORM` (**15% commission / 1500 bps**)
  4. Customer taps **Reserve Slot & Proceed to Pay** → `BookingPaymentRepository.createBookingWithSlotHold`:
     - Runs a **Firestore Transaction** (`db.runTransaction`) locking `slot_locks/{expertId}_{dateIso}_{startMinute}` to prevent two customers booking the same time concurrently.
     - Snapshots financial amounts (`amountPaise`, `commissionRateBps`, `commissionPaise`, `gatewayFeePaise`, `taxDeductionPaise`, `expertEarningPaise`) permanently onto the booking.
     - Sets `holdExpiresAtEpochMs = now + 10 minutes`, `bookingStatus = PENDING_PAYMENT`, `paymentStatus = CREATED`, and generates a 6-digit `checkInPin`.
     - Captures `verifiedCustomerGoogleEmail` and `verifiedExpertGoogleEmail` onto the booking document.

---

### Flow 5: Payment Processing, Verification & Ledger Recording
- **Files**: `BookingPaymentRepository.kt`, `CustomerDashboardScreen.kt`, `FinancialLedgerEngine.kt`, `RazorpaySignatureVerifier.kt`, `backend/src/paymentService.js`
- **Two Execution Paths**:
  - **Path A — Live/Server Backend Mode** *(Active when `HRCONNECT_BACKEND_BASE_URL` is configured)*:
    1. Calls `POST /api/payments/create-order` with `bookingId` (server recalculates price & commission—never trusts client totals).
    2. If `testMode == true`, calls `POST /api/payments/verify` with HMAC verification.
    3. If `testMode == false`, blocks confirmation until native Razorpay Android Checkout SDK handoff passes real `razorpay_payment_id` + `razorpay_signature` to `/api/payments/verify`.
    4. Asynchronous webhooks hit `POST /api/webhooks/razorpay`, verified via `X-Razorpay-Signature` HMAC-SHA256 (`crypto.timingSafeEqual`) and deduplicated via `webhook_events/{eventId}`.
  - **Path B — Explicit Sandbox Test Mode** *(Active when backend URL is not configured and `RAZORPAY_KEY_ID` starts with `rzp_test_`)*:
    1. Validates hold has not expired (`now <= holdExpiresAtEpochMs`) and checks idempotency (`payments/pay_{bookingId}`).
    2. Atomically commits a Firestore `WriteBatch`:
       - Creates `payments/pay_{bookingId}` with `testMode = true`, `serverVerified = false`, `providerSettlementStatus = SANDBOX_SETTLED` *(enforced by `firestore.rules`)*
       - Updates `bookings/{bookingId}` → `bookingStatus = CONFIRMED`, `paymentStatus = SUCCESS`, `calendarSyncStatus = PENDING_BACKEND_SYNC`
       - Updates `slot_locks/{slotKey}` → `CONFIRMED`
       - Writes immutable `ledger_entries`:
         - `PAYMENT_CAPTURED` (`CREDIT`)
         - `PLATFORM_COMMISSION` (`CREDIT`)
         - `GATEWAY_FEE` (`DEBIT` platform-side)
         - `TAX_DEDUCTION` (`DEBIT`, if TDS > 0)
         - `EXPERT_EARNING` (`CREDIT`)
       - Creates in-app `notifications` for both Customer and Expert.
- **Security Guarantee**: Firestore Security Rules reject any non-admin client write attempting `testMode == false`, `serverVerified == true`, or confirming a booking without a matching `payments/pay_{bookingId}` document in the same batch.

---

### Flow 6: Google Calendar & Google Meet Session Lifecycle
- **Files**: `MarketplaceBackendClient.kt`, `BookingPaymentRepository.kt`, `AttendanceVerificationEngine.kt`, `backend/src/googleWorkspaceService.js`
- **Calendar & Meet Creation**:
  - When backend is configured (`HRCONNECT_BACKEND_BASE_URL`), `syncGoogleCalendarAndMeet(booking)` calls `POST /api/bookings/sync-calendar`.
  - Backend builds a Google Calendar v3 event (`timeZone: Asia/Kolkata`, `conferenceDataVersion: 1`, `hangoutsMeet` request) inviting `verifiedCustomerGoogleEmail` and `verifiedExpertGoogleEmail`, and configures Google Meet REST `spaces.patch` with `accessType: "TRUSTED"` (no open-access link abuse).
  - Without a live backend, experts can share/attach a `https://meet.google.com/...` URL (`updateMeetingUrl`), and status stays clearly marked `NOT_SYNCED` or `PENDING_BACKEND_SYNC`.
- **Dual Verification Modes (Never Confused)**:
  1. **Primary — Google Meet REST API Attendance (`AttendanceVerificationEngine`)**:
     - Clips participant session intervals to `[scheduledStart - 10m, scheduledEnd + 15m]`, merges disjoint reconnect intervals per participant, computes intersection overlap, and evaluates against `min(duration, max(10, ceil(duration * 0.65)))`:
       - **Scenario A (`VERIFIED`)**: Both verified Google emails joined and overlapped `>= requiredMinutes`.
       - **Scenario B (`FAILED`)**: Expert no-show (Customer waited `>= 5m`, Expert `0m`). Flagged for admin review / customer refund.
       - **Scenario C (`PARTIALLY_VERIFIED`)**: Customer no-show (Expert waited `>= 5m`, Customer `0m`). Flagged for admin review.
       - **Scenario D (`MISMATCH`)**: Unrecognized Google account or anonymous phone caller joined instead of verified email.
       - **Scenario E (`MANUAL_REVIEW`)**: Both joined, but overlap was below threshold.
       - **Scenario F (`FAILED`)**: Neither verified participant joined.
  2. **Secondary — Supplementary Check-In PIN Fallback**:
     - Both Customer and Expert can enter the 6-digit `checkInPin` (`submitCheckIn`).
     - Records `customerCheckInAtMs` / `expertCheckInAtMs` and sets `attendanceVerificationSource = SUPPLEMENTARY_CHECKIN_FALLBACK` (`PARTIALLY_VERIFIED` or `MANUAL_REVIEW`).
     - Firestore Rules **block** non-admin clients from writing `attendanceVerificationSource = GOOGLE_MEET_REST_API` or `attendanceStatus = VERIFIED`.

---

### Flow 7: Consultation Completion, Cancellation, Disputes & Refunds

#### Booking State Machine (`ConsultationLifecycleEngine`)
```
PENDING_PAYMENT ──(payment)──▶ CONFIRMED ──(calendar/join)──▶ SCHEDULED / IN_PROGRESS
       │                           │                                      │
  (hold expires /             (cancel)                             (1st party confirms)
   cancel)                         ▼                                      ▼
       │                       CANCELLED ──(refund)──▶ REFUNDED   COMPLETION_PENDING
       ▼                                                                  │
   CANCELLED                                                      (2nd party confirms)
                                                                          ▼
                                                                      COMPLETED ──(24h window)──▶ Payout ELIGIBLE
                                                                          │
                                                                   (issue raised)
                                                                          ▼
                                                                      DISPUTED (Payout ON_HOLD)
                                                                          │
                                                    ┌─────────────────────┴─────────────────────┐
                                                    ▼                                           ▼
                                          RESOLVED_RELEASE_EXPERT                RESOLVED_REFUND_FULL / PARTIAL
```

- **Cancellation Policy (`evaluateCancellationRefund`)**:
  - Unpaid `PENDING_PAYMENT`: Cancels with `₹0` refund/charge and releases `slot_locks`.
  - Expert cancels paid session: `100%` refund eligibility for Customer.
  - Customer cancels `>= 24 hours` before IST session start: `100%` refund eligibility.
  - Customer cancels `< 24 hours` before session start: `50%` late-cancellation refund policy.
  - `COMPLETED`, `CANCELLED`, or `REFUNDED` bookings cannot be cancelled.
- **Dual-Party Completion**:
  - Expert or Customer taps **Confirm Session Completed** → transitions booking to `COMPLETION_PENDING` and notifies the other party.
  - Once **both** confirm → transitions to `COMPLETED` and starts the **24-hour dispute protection window** (`disputeWindowEndsAtEpochMs = completedAt + 24h`).
- **Disputes & Refunds**:
  - Either party can open a dispute (`raiseDispute`) → writes `disputes/{bookingId}`, marks booking `DISPUTED` (`payoutEligibilityStatus = ON_HOLD`), writes a `DISPUTE_HOLD` ledger entry, and freezes funds.
  - Admin resolves via **Booking Inspector** (`adminResolveDisputeAndRefund`):
    - `RESOLVED_RELEASE_EXPERT`: Clears hold, marks `COMPLETED`, sets `ELIGIBLE` (if attendance verified).
    - `RESOLVED_REFUND_PARTIAL`: Sets `PARTIALLY_REFUNDED`, records `refundedAmountPaise`, writes `REFUND_COMPLETED` + proportional `COMMISSION_REVERSAL` ledger entries, and preserves proportional payout eligibility on remaining net expert earnings.
    - `RESOLVED_REFUND_FULL`: Sets `REFUNDED`, reverses commission, sets `payoutEligibilityStatus = REVERSED`.

---

### Flow 8: 6-Bucket Expert Wallet & Payout Lifecycle
- **6 Transparent Wallet Buckets (`FinancialLedgerEngine.computeExpertWalletBreakdown`)**:
  1. **Gross Revenue**: Sum of settled `PAYMENT_CAPTURED` entries.
  2. **Platform Fees & Deductions**: Net commission (`PLATFORM_COMMISSION - COMMISSION_REVERSAL`), estimated gateway fees (`GATEWAY_FEE`), and TDS (`TAX_DEDUCTION`).
  3. **Pending Earnings**: Confirmed/completed sessions awaiting verified attendance, dual confirmation, or 24-hour dispute window expiry.
  4. **Available for Payout**: Strictly sums bookings passing **all** gates (`evaluateBookingPayoutEligibility`):
     - `paymentStatus` is `SUCCESS` or `PARTIALLY_REFUNDED`
     - `providerSettlementStatus` is `SETTLED` or `SANDBOX_SETTLED`
     - `bookingStatus` is not `CANCELLED` / `REFUNDED` / `PENDING_PAYMENT` / `DISPUTED`
     - No active dispute (`OPEN` / `UNDER_REVIEW`) and not 100% refunded
     - Attendance is `VERIFIED`, `PARTIALLY_VERIFIED`, or `MANUAL_REVIEW` with `ADMIN_APPROVED`
     - Both parties confirmed completion (or `COMPLETED` + `ADMIN_APPROVED`)
     - 24-hour dispute window has elapsed (`now >= disputeWindowEndsAtEpochMs`)
     - Minus any already `PENDING`/`APPROVED`/`PROCESSING` or `PAID` payout requests
  5. **Payout Processing**: Funds reserved inside active `PENDING` / `APPROVED` / `PROCESSING` payout requests.
  6. **Paid Out / Disputed & Refunded**: Historical totals for completed payouts, active dispute holds, and refunded amounts.
- **Payout Withdrawal & Admin Settlement**:
  - Expert submits UPI/Bank details (min `₹100`) with an `idempotencyKey` and snapshot of `eligibleBookingIds`.
  - Admin updates payout status in `AdminDashboardScreen`:
    - `PENDING -> PROCESSING` (locks `booking.payoutEligibilityStatus = PROCESSING`)
    - `PROCESSING -> PAID` (requires UTR/reference, writes `PAYOUT_COMPLETED` ledger entry, marks bookings `PAID`; **Firestore rules forbid any further edits once `PAID` or `REJECTED`**)
    - `PROCESSING -> FAILED` / `PENDING -> REJECTED` (writes `PAYOUT_FAILED` if failed, restores bookings to `ELIGIBLE` so funds return to withdrawable balance for safe retry)

---

### Flow 9: Reviews & Moderation
- **Customer Review (`submitReview`)**: Allowed only once per `COMPLETED` booking by the booking's `customerUid` (`reviews/{bookingId}`). Sets `booking.reviewed = true` and notifies the expert.
- **Reporting & Admin Moderation**: Experts or users can report a review (`reported = true`, `reportReason`). Admin can toggle `VISIBLE` / `HIDDEN` in **Moderation**, logging an immutable `audit_logs` record.

---

### Flow 10: Admin Platform Operations (`AdminDashboardScreen`)
Tabs inside **Admin Dashboard**:
1. **Analytics (`OVERVIEW`)**: Platform-wide GMV, commissions, active bookings, pending payouts/disputes, and one-tap **Seed 15 HR Categories & Demo Marketplace**.
2. **Experts (`EXPERTS`)**: Review `PENDING` expert applications (approve or reject with reason), suspend/reactivate experts or customers.
3. **Payouts (`PAYOUTS`)**: Process pending/processing/failed payouts, enter UTR/reference, handle retries or rejections.
4. **Bookings & Payments (`BOOKINGS`)**: Inspect any booking end-to-end (`AdminBookingInspectorDialog`), view Google Meet attendance metrics & unmatched callers, trigger Calendar/Meet sync, issue `ADMIN_APPROVED`/`ADMIN_REJECTED` attendance overrides, resolve disputes, and process full/partial refunds.
5. **Commission & Categories (`SETTINGS`)**: Configure platform commission %, referral commission %, gateway fee bps, GST on commission bps, TDS bps, payment hold minutes, cancellation window hours, dispute window hours, and minimum Meet attendance ratio/minutes; add/toggle HR specialisations.
6. **Reviews & Audit (`MODERATION`)**: Hide/restore reported reviews and inspect chronological `audit_logs`.

---

## 5. Firestore Collections & Security Rules Summary

All 17 Firestore collections/subcollections are protected by deployed `firestore.rules` (default-deny net at root):

| Collection | Read Access | Write / Mutability Rules |
| :--- | :--- | :--- |
| `users/{uid}` & `users/{uid}/private/{doc}` | Owner or Admin | Owner creates `ACTIVE` non-admin role; PII subcollection strictly owner/Admin. |
| `email_audit_logs` | Owner or Admin | Append-only by signed-in user for own `userId`. |
| `specialisations` | Signed-in users | Admin only. |
| `experts` | Public `APPROVED+ACTIVE`, owner, or Admin | Owner creates `PENDING`; only Admin can set `APPROVED`. |
| `services` | Active services, owner, or Admin | Only `APPROVED+ACTIVE` experts can create/update their services. |
| `availability_rules` & `availability_exceptions` | Signed-in users | Owning expert or Admin. |
| `bookings` | Customer, Expert, or Admin | Immutable price/commission/referral snapshot; participant updates tightly scoped by state & changed keys; no client `VERIFIED` Meet spoofing. |
| `slot_locks` | Signed-in users | Transaction-managed lock tied to booking hold & status. |
| `payments` | Customer, Expert, or Admin | Non-admin clients restricted strictly to `testMode == true && serverVerified == false`; live payments require Admin/backend SDK. |
| `ledger_entries` | Customer, Expert, or Admin | **Append-only (`update, delete: if false`)**. |
| `attendance_records` | Customer, Expert, or Admin | **Admin / Backend Admin SDK only**. |
| `disputes` | Customer, Expert, or Admin | Participants can create `OPEN`; **only Admin can resolve/update**. |
| `payout_requests` | Expert or Admin | Expert creates `PENDING` (`>= ₹100`); Admin processes; **`PAID` and `REJECTED` are permanently immutable**. |
| `reviews` | Signed-in users | `reviewId == bookingId`; Customer only on `COMPLETED` booking; Expert/users may report; Admin moderates `VISIBLE`/`HIDDEN`. |
| `notifications` | Recipient or Admin | Recipient can only mark `read = true`. |
| `platform_settings` | Signed-in users | Admin only. |
| `audit_logs` & `webhook_events` | Admin only | `audit_logs`: Admin append-only. `webhook_events`: Backend Admin SDK only (client writes forbidden). |

---

## 6. Pending / Roadmap Items (External & Future Features)

1. **Deploy `/backend` & Configure Live Secrets**:
   - Host Node.js `/backend` over HTTPS and set `HRCONNECT_BACKEND_BASE_URL` in AI Studio Secrets.
   - Configure `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and Google Workspace OAuth / Service Account credentials (`backend/.env.backend.example`).
   - Add native Razorpay Android Checkout SDK handoff in `BookingPaymentRepository.kt` for live `rzp_live_*` payments.
2. **Future Feature Additions (Not Yet Built)**:
   - Automated bank/UPI payout disbursement via RazorpayX / Cashfree Payouts APIs
   - Background push notifications (FCM) and automated Email/WhatsApp session reminders
   - 1-click slot rescheduling (atomic `slot_locks` + Calendar event move)
   - Pre-session messaging & PDF/resume attachment uploads
   - Expert KYC/PAN/Bank penny-drop verification & downloadable GST invoices
   - Profile photo uploads (`Coil` + storage) and deep-link referral URLs
