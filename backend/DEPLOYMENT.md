# HRConnect Production Deployment Guide

This runbook covers production provisioning and deployment for **HRConnect** (`gen-lang-client-0782301491`).

---

## 1. Required Production Secrets & Credentials

### Backend (Google Cloud Secret Manager — NEVER commit or put into Android `.env`)
| Secret Name | Purpose |
|---|---|
| `hrconnect-razorpay-key-id` | Razorpay Live/Test Key ID (`rzp_live_...` or `rzp_test_...`) |
| `hrconnect-razorpay-key-secret` | Razorpay Key Secret for `/orders`, `/payments`, `/refunds`, HMAC verify |
| `hrconnect-razorpay-webhook-secret` | Razorpay Webhook signing secret for `/api/webhooks/razorpay` |
| `hrconnect-razorpayx-account-number` | RazorpayX Business Banking Virtual Account Number (required if `RAZORPAY_PAYOUT_MODE=razorpayx`) |
| `hrconnect-google-sa-email` | Google Service Account email for Workspace Calendar & Meet REST v2 |
| `hrconnect-google-sa-private-key` | PEM private key of the Google Service Account |
| `hrconnect-google-workspace-subject` | Google Workspace organizer email to impersonate via Domain-Wide Delegation |
| `hrconnect-google-workspace-domain` | Optional Workspace domain for `TRUSTED` Meet space access (`example.com`) |

*Alternative Google OAuth option (if not using Domain-Wide Delegation)*:
Provide `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, and `GOOGLE_OAUTH_REFRESH_TOKEN`.

### Android App (`/.env` or AI Studio Secrets Panel — public values only)
```properties
RAZORPAY_KEY_ID=rzp_live_xxxxxxxxxxxxxx
HRCONNECT_BACKEND_BASE_URL=https://hrconnect-backend-xxxxx-el.a.run.app
```
> Note: **Release builds (`minifyEnabled` / non-`DEBUG`) strictly refuse debug sandbox payment/attendance fallbacks.** Both `HRCONNECT_BACKEND_BASE_URL` (HTTPS) and `RAZORPAY_KEY_ID` must be valid before distributing release APKs/AABs.

---

## 2. Firebase & Firestore Setup

1. Deploy security rules and composite indexes:
   ```bash
   firebase use gen-lang-client-0782301491
   firebase deploy --only firestore:rules,firestore:indexes
   ```
2. Enable **Point-in-Time Recovery (PITR)** on the named Firestore database `ai-studio-android-hrconnec-b03b6447-7546-4ed2-bdef-5257728737bb`:
   ```bash
   gcloud firestore databases update \
     --project=gen-lang-client-0782301491 \
     --database=ai-studio-android-hrconnec-b03b6447-7546-4ed2-bdef-5257728737bb \
     --enable-pitr
   ```
3. Bootstrap admin document in `admins/{uid}` (`{ "email": "utkarsh.work.contact@gmail.com", "active": true }`) or configure `ADMIN_EMAILS`.
4. Add your Android release keystore SHA-1 and SHA-256 fingerprints in Firebase Console so Google Sign-In (`CredentialManager`) works in release builds.

---

## 3. Google Workspace Calendar & Meet REST API v2

1. Enable **Google Calendar API** and **Google Meet REST API** in GCP project `gen-lang-client-0782301491`.
2. For full production Meet attendance tracking (`meet.googleapis.com/v2/conferenceRecords` -> `participants` -> `participantSessions`) and `TRUSTED` access control:
   - Use a **Google Workspace** account (Business Standard/Plus or Enterprise) with Domain-Wide Delegation enabled for the service account on scopes:
     - `https://www.googleapis.com/auth/calendar.events`
     - `https://www.googleapis.com/auth/meetings.space.created`
     - `https://www.googleapis.com/auth/meetings.space.readonly`
   - Consumer `@gmail.com` organizer accounts cannot create `TRUSTED` domain-restricted Meet spaces and have limited Meet REST v2 conference telemetry access. When Meet API logs are unavailable or contain anonymous/unmapped guests, `evaluateMeetAttendance` safely routes the booking to `MANUAL_REVIEW` or `MISMATCH` rather than auto-unlocking payouts.

---

## 4. Razorpay & Webhook Setup

1. In Razorpay Dashboard -> **Account & Settings -> Webhooks**, add webhook URL:
   `https://<your-cloud-run-domain>/api/webhooks/razorpay`
2. Subscribe to events:
   - `payment.captured`, `payment.failed`
   - `refund.created`, `refund.processed`, `refund.failed`
   - `payout.processed`, `payout.failed`, `payout.reversed` (RazorpayX)
   - `transfer.processed`, `transfer.failed` (Route, if enabled)
3. Set `RAZORPAY_PAYOUT_MODE`:
   - `razorpayx`: Experts onboard via `/api/experts/payout-onboarding` (Contact + Fund Account tokenized; secrets kept in backend-only `experts/{id}/payout_secrets/config`), and admin payouts dispatch real RazorpayX payouts with `X-Payout-Idempotency`.
   - `route`: Split transfers attached on payment capture for linked `razorpayLinkedAccountId`.
   - `manual_utr`: Admin records verified bank/UPI UTR manually via `/api/payouts/process`.

---

## 5. Build & Deploy Backend to Cloud Run

```bash
gcloud config set project gen-lang-client-0782301491

# Build container via Cloud Build / Artifact Registry
gcloud builds submit ./backend \
  --tag asia-south1-docker.pkg.dev/gen-lang-client-0782301491/hrconnect/backend:v1

# Deploy Cloud Run service (update image tag in backend/cloudrun.service.yaml first)
gcloud run services replace backend/cloudrun.service.yaml --region asia-south1
```

Verify readiness immediately after deploy:
```bash
curl -fsS https://<your-cloud-run-domain>/health
curl -fsS https://<your-cloud-run-domain>/ready
```
`/ready` returns HTTP `200` only if Firestore is reachable AND `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` are non-placeholder production values.

---

## 6. Monitoring, Alerting & Release Build

- Create a Cloud Monitoring Uptime Check polling `/ready` every 1 minute from multiple regions.
- Create log-based alerts matching `severity="error"` (`api_request_unhandled_error`) or 5xx responses on `/api/webhooks/razorpay` and `/api/payments/verify`.
- Build release bundle locally with upload keystore env vars (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) and valid `/.env`:
  ```bash
  gradle :app:bundleRelease
  ```
