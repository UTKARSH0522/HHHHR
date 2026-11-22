const http = require("node:http");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { getFirebaseServices } = require("./firebaseAdmin");
const { HttpAuthError, authenticateFirebaseRequest } = require("./authMiddleware");
const { verifyRazorpayWebhookSignature } = require("./paymentService");
const {
  RazorpayApiError,
  createRazorpayClient,
  isPlaceholderValue,
  getPayoutMode,
} = require("./razorpayClient");
const { createGoogleWorkspaceClient } = require("./googleWorkspaceService");
const {
  logStructured,
  createSlidingWindowRateLimiter,
  getClientIp,
} = require("./securityMiddleware");
const {
  HttpAppError,
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
} = require("./firestoreRepository");

const MAX_BODY_BYTES = 1024 * 1024; // 1 MB

function readBodyBuffer(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let totalBytes = 0;
    let settled = false;

    req.on("data", (chunk) => {
      if (settled) return;
      totalBytes += chunk.length;
      if (totalBytes > MAX_BODY_BYTES) {
        settled = true;
        reject(new HttpAppError(413, "Request payload exceeds 1MB limit"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (!settled) {
        settled = true;
        resolve(Buffer.concat(chunks));
      }
    });
    req.on("error", (err) => {
      if (!settled) {
        settled = true;
        reject(err);
      }
    });
  });
}

function parseJsonObject(rawBody) {
  if (!rawBody || rawBody.length === 0) return {};
  let parsed;
  try {
    parsed = JSON.parse(rawBody.toString("utf8"));
  } catch {
    throw new HttpAppError(400, "Invalid JSON payload");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new HttpAppError(400, "JSON request body must be an object");
  }
  return parsed;
}

function parseAllowedOrigins(raw) {
  const src = typeof raw === "string" ? raw : process.env.ALLOWED_ORIGINS || "";
  return new Set(
    src
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s && s !== "*")
  );
}

function jsonResponse(res, statusCode, body, extraHeaders = {}) {
  const payload = JSON.stringify(body);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Cache-Control": "no-store",
    ...extraHeaders,
  });
  res.end(payload);
}

function createAppServer(config = {}) {
  let firebaseServices = null;
  if (config.db || config.authService) {
    firebaseServices = {
      db: config.db,
      auth: config.authService,
      messaging: config.messaging || null,
      projectId: config.projectId || process.env.FIREBASE_PROJECT_ID || "",
      databaseId: config.databaseId || process.env.FIRESTORE_DATABASE_ID || "",
    };
  } else {
    firebaseServices = getFirebaseServices(config.firebaseOptions);
  }

  const db = firebaseServices.db;
  const authService = firebaseServices.auth;
  const messaging = config.messaging !== undefined ? config.messaging : firebaseServices.messaging || null;
  const adminEmails = config.adminEmails ?? process.env.ADMIN_EMAILS;
  const allowedOrigins = parseAllowedOrigins(config.allowedOrigins);

  const isProduction = (config.nodeEnv ?? process.env.NODE_ENV) === "production";
  const requireLiveRazorpay = config.requireLiveRazorpay ?? isProduction;
  const razorpayKeyId = config.razorpayKeyId ?? process.env.RAZORPAY_KEY_ID ?? "rzp_test_placeholder";
  const razorpayKeySecret = config.razorpayKeySecret ?? process.env.RAZORPAY_KEY_SECRET ?? "";
  const razorpayWebhookSecret = config.razorpayWebhookSecret ?? process.env.RAZORPAY_WEBHOOK_SECRET ?? "";
  const razorpayXAccountNumber = config.razorpayXAccountNumber ?? process.env.RAZORPAYX_ACCOUNT_NUMBER ?? "";
  const isTestMode = String(razorpayKeyId).startsWith("rzp_test_");

  const enableRazorpayApi = isProduction ? true : config.enableRazorpayApi !== false;
  const rawRazorpayClient =
    config.razorpayClient ||
    createRazorpayClient({
      keyId: razorpayKeyId,
      keySecret: razorpayKeySecret,
      razorpayXAccountNumber,
      baseUrl: config.razorpayApiBaseUrl ?? process.env.RAZORPAY_API_BASE_URL,
      fetchImpl: config.razorpayFetch,
    });
  const razorpayClient = enableRazorpayApi ? rawRazorpayClient : null;

  const payoutMode = getPayoutMode({
    payoutMode: config.payoutMode ?? process.env.RAZORPAY_PAYOUT_MODE,
    routeEnabled: config.routeEnabled ?? process.env.RAZORPAY_ROUTE_ENABLED,
    razorpayXAccountNumber,
  });

  const googleWorkspaceClient =
    config.googleWorkspaceClient ||
    createGoogleWorkspaceClient({
      ...(config.googleWorkspaceOptions || {}),
      fetchImpl: config.googleFetch,
    });

  const rateLimitMax = Number(config.rateLimitMaxPerMin ?? process.env.RATE_LIMIT_MAX_PER_MIN ?? 120);
  const sensitiveRateLimitMax = Number(config.sensitiveRateLimitMaxPerMin ?? process.env.SENSITIVE_RATE_LIMIT_MAX_PER_MIN ?? 60);
  const generalLimiter = createSlidingWindowRateLimiter({ windowMs: 60_000, maxRequests: rateLimitMax });
  const sensitiveLimiter = createSlidingWindowRateLimiter({ windowMs: 60_000, maxRequests: sensitiveRateLimitMax });
  const logger = config.logger || console;

  const sensitiveRoutes = new Set([
    "/api/payments/create-order",
    "/api/payments/verify",
    "/api/refunds/initiate",
    "/api/payouts/process",
    "/api/webhooks/razorpay",
  ]);

  const knownAuthRoutes = new Set([
    "/api/bookings/create",
    "/api/bookings/reschedule",
    "/api/bookings/cancel",
    "/api/payments/create-order",
    "/api/payments/verify",
    "/api/bookings/sync-calendar",
    "/api/bookings/sync-meet-attendance",
    "/api/refunds/initiate",
    "/api/experts/payout-onboarding",
    "/api/payouts/process",
    "/api/notifications/register-token",
  ]);

  return http.createServer(async (req, res) => {
    const requestId = req.headers?.["x-request-id"] || crypto.randomUUID();
    const startedMs = Date.now();
    const path = (req.url || "/").split("?")[0];

    try {
      const requestOrigin = typeof req.headers?.origin === "string" ? req.headers.origin.trim() : "";
      if (requestOrigin && !allowedOrigins.has(requestOrigin)) {
        return jsonResponse(res, 403, { error: "Origin not allowed", requestId });
      }
      if (req.method === "OPTIONS") {
        if (!requestOrigin) {
          return jsonResponse(res, 405, { error: "Method not allowed", requestId });
        }
        res.writeHead(204, {
          "Access-Control-Allow-Origin": requestOrigin,
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Authorization",
          "Vary": "Origin",
        });
        return res.end();
      }

      if (req.method === "GET" && path === "/health") {
        return jsonResponse(res, 200, {
          ok: true,
          persistence: "firestore",
          projectId: firebaseServices.projectId || undefined,
          databaseId: firebaseServices.databaseId || undefined,
          authConfigured: Boolean(authService && typeof authService.verifyIdToken === "function"),
          razorpayConfigured: Boolean(!isPlaceholderValue(razorpayKeyId) && !isPlaceholderValue(razorpayKeySecret)),
          webhookConfigured: Boolean(!isPlaceholderValue(razorpayWebhookSecret)),
          googleWorkspaceConfigured: Boolean(googleWorkspaceClient?.isConfigured()),
          messagingConfigured: Boolean(messaging),
          payoutMode,
          testMode: isTestMode,
        });
      }

      if (req.method === "GET" && path === "/ready") {
        const missing = [];
        if (!authService || typeof authService.verifyIdToken !== "function") {
          missing.push("firebaseAuth");
        }
        if (requireLiveRazorpay) {
          if (isPlaceholderValue(razorpayKeyId)) missing.push("RAZORPAY_KEY_ID");
          if (isPlaceholderValue(razorpayKeySecret)) missing.push("RAZORPAY_KEY_SECRET");
          if (isPlaceholderValue(razorpayWebhookSecret)) missing.push("RAZORPAY_WEBHOOK_SECRET");
        }
        try {
          await db.collection("platform_settings").doc("config").get();
        } catch (err) {
          return jsonResponse(res, 503, {
            ready: false,
            error: "Firestore connectivity check failed",
            detail: err?.message || "Unavailable",
          });
        }
        if (missing.length > 0) {
          return jsonResponse(res, 503, {
            ready: false,
            error: "Required production secrets are missing or placeholders",
            missing,
          });
        }
        return jsonResponse(res, 200, {
          ready: true,
          environment: isProduction ? "production" : "development",
          payoutMode,
          googleWorkspaceConfigured: Boolean(googleWorkspaceClient?.isConfigured()),
        });
      }

      const clientIp = getClientIp(req);
      if (path.startsWith("/api/")) {
        const limiter = sensitiveRoutes.has(path) ? sensitiveLimiter : generalLimiter;
        const limitCheck = limiter.check(`${clientIp}:${path}`);
        if (!limitCheck.allowed) {
          return jsonResponse(
            res,
            429,
            { error: "Rate limit exceeded; please retry shortly", requestId },
            { "Retry-After": String(limitCheck.retryAfterSec) }
          );
        }
      }

      const rawBody = await readBodyBuffer(req);

      // Razorpay webhook endpoint: authenticated exclusively via HMAC-SHA256 x-razorpay-signature
      if (req.method === "POST" && path === "/api/webhooks/razorpay") {
        if (!razorpayWebhookSecret || isPlaceholderValue(razorpayWebhookSecret)) {
          return jsonResponse(res, 503, { error: "Razorpay webhook secret is not configured", requestId });
        }
        const sig = req.headers["x-razorpay-signature"];
        if (!verifyRazorpayWebhookSignature({ rawBody, signatureHeader: sig, webhookSecret: razorpayWebhookSecret })) {
          logStructured("warn", "webhook_signature_invalid", { requestId, clientIp }, logger);
          return jsonResponse(res, 401, { error: "Invalid Razorpay webhook signature", requestId });
        }
        const parsed = parseJsonObject(rawBody);
        const eventId =
          req.headers["x-razorpay-event-id"] ||
          parsed.event_id ||
          crypto.createHash("sha256").update(rawBody).digest("hex");

        const result = await processWebhookTransaction({
          db,
          messaging,
          eventId: String(eventId),
          eventType: parsed.event,
          payload: parsed,
          razorpayClient,
          enableRazorpayApi,
        });

        const statusCode =
          result.status === "TAMPER_REJECTED" || result.status === "REJECTED" ? 400 : 200;
        logStructured(
          "info",
          "webhook_processed",
          {
            requestId,
            eventType: parsed.event,
            resultStatus: result.status,
            durationMs: Date.now() - startedMs,
          },
          logger
        );
        return jsonResponse(res, statusCode, result);
      }

      if (req.method !== "POST") {
        return jsonResponse(res, 404, { error: "Route not found", requestId });
      }

      if (!knownAuthRoutes.has(path)) {
        return jsonResponse(res, 404, { error: "Route not found", requestId });
      }

      // Every non-webhook API route requires verified Firebase ID token authentication.
      const actor = await authenticateFirebaseRequest(req, {
        authService,
        db,
        adminEmails,
      });

      const body = parseJsonObject(rawBody);
      let result;

      if (path === "/api/bookings/create") {
        result = await createBookingWithHoldTransaction({
          db,
          actor,
          expertId: body.expertId,
          serviceId: body.serviceId,
          dateIso: body.dateIso,
          startMinute: body.startMinute,
          topic: body.topic,
          referralCode: body.referralCode,
          customerName: body.customerName,
          nowEpochMs: body.nowEpochMs,
        });
      } else if (path === "/api/bookings/reschedule") {
        result = await rescheduleBookingTransaction({
          db,
          messaging,
          bookingId: body.bookingId,
          newDateIso: body.newDateIso,
          newStartMinute: body.newStartMinute,
          reason: body.reason,
          actor,
          googleWorkspaceClient,
          nowEpochMs: body.nowEpochMs,
        });
      } else if (path === "/api/bookings/cancel") {
        result = await cancelBookingTransaction({
          db,
          messaging,
          bookingId: body.bookingId,
          reason: body.reason,
          actor,
          razorpayClient,
          googleWorkspaceClient,
          nowEpochMs: body.nowEpochMs,
        });
      } else if (path === "/api/payments/create-order") {
        const orderRes = await createOrderTransaction({
          db,
          bookingId: body.bookingId,
          actor,
          isTestMode,
          razorpayClient,
          enableRazorpayApi,
          requireLiveRazorpay,
        });
        result = {
          ...orderRes,
          razorpayKeyId,
          requiresSdkCheckout: Boolean(!isPlaceholderValue(razorpayKeyId) && razorpayClient?.isConfigured()),
        };
      } else if (path === "/api/payments/verify") {
        result = await verifyPaymentTransaction({
          db,
          messaging,
          bookingId: body.bookingId,
          razorpayOrderId: body.razorpayOrderId || body.orderId,
          razorpayPaymentId: body.razorpayPaymentId || body.paymentId,
          razorpaySignature: body.razorpaySignature || body.signature,
          paymentMethod: body.paymentMethod,
          actor,
          keySecret: razorpayKeySecret,
          isTestMode,
          razorpayClient,
          enableRazorpayApi,
          requireLiveRazorpay,
          payoutMode,
        });
      } else if (path === "/api/bookings/sync-calendar") {
        result = await syncCalendarMetadata({
          db,
          bookingId: body.bookingId,
          actor,
          googleWorkspaceClient,
        });
      } else if (path === "/api/bookings/sync-meet-attendance") {
        // Server boundary: never pass client-supplied participantSessions into attendance evaluation.
        result = await syncMeetAttendanceSafe({
          db,
          bookingId: body.bookingId,
          actor,
          googleWorkspaceClient,
        });
      } else if (path === "/api/refunds/initiate") {
        result = await initiateRefundTransaction({
          db,
          messaging,
          bookingId: body.bookingId,
          refundAmountPaise: body.refundAmountPaise ?? body.amountPaise,
          reason: body.reason,
          actor,
          razorpayClient,
          enableRazorpayApi,
          requireLiveRazorpay,
        });
      } else if (path === "/api/experts/payout-onboarding") {
        result = await saveExpertPayoutOnboarding({
          db,
          actor,
          expertId: body.expertId,
          payoutAccountType: body.payoutAccountType,
          vpaAddress: body.vpaAddress,
          bankAccountHolderName: body.bankAccountHolderName,
          bankIfsc: body.bankIfsc,
          bankAccountNumber: body.bankAccountNumber,
          razorpayClient,
        });
      } else if (path === "/api/payouts/process") {
        result = await processPayoutTransaction({
          db,
          messaging,
          payoutRequestId: body.payoutRequestId || body.payoutId,
          newStatus: body.newStatus,
          payoutReference: body.payoutReference,
          adminNote: body.adminNote,
          nowEpochMs: body.nowEpochMs,
          actor,
          razorpayClient,
          payoutMode,
        });
      } else if (path === "/api/notifications/register-token") {
        result = await registerFcmToken({
          db,
          actor,
          token: body.fcmToken || body.token,
          platform: body.platform,
          deviceId: body.deviceId,
        });
      } else {
        return jsonResponse(res, 404, { error: "Route not found", requestId });
      }

      logStructured(
        "info",
        "api_request_ok",
        {
          requestId,
          path,
          actorUid: actor.uid,
          durationMs: Date.now() - startedMs,
        },
        logger
      );
      return jsonResponse(res, 200, result);
    } catch (err) {
      if (err instanceof HttpAuthError || err instanceof HttpAppError || err instanceof RazorpayApiError) {
        logStructured(
          "warn",
          "api_request_rejected",
          {
            requestId,
            path,
            statusCode: err.statusCode,
            message: err.message,
            durationMs: Date.now() - startedMs,
          },
          logger
        );
        return jsonResponse(res, err.statusCode, { error: err.message, requestId });
      }
      if (err?.code === 10 || /ABORTED|cross-transaction contention/i.test(err?.message || "")) {
        return jsonResponse(res, 409, { error: "Concurrent transaction conflict; please retry", requestId });
      }
      logStructured(
        "error",
        "api_request_unhandled_error",
        {
          requestId,
          path,
          message: err?.message || String(err),
          durationMs: Date.now() - startedMs,
        },
        logger
      );
      return jsonResponse(res, 500, { error: "Internal server error", requestId });
    }
  });
}

function loadLocalEnvIfPresent(envFilePath = path.join(__dirname, "..", ".env")) {
  try {
    if (!fs.existsSync(envFilePath)) return;
    const raw = fs.readFileSync(envFilePath, "utf8");
    for (const line of raw.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const eqIdx = trimmed.indexOf("=");
      if (eqIdx <= 0) continue;
      const key = trimmed.slice(0, eqIdx).trim();
      let value = trimmed.slice(eqIdx + 1).trim();
      if (
        (value.startsWith("\"") && value.endsWith("\"")) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1);
      }
      if (process.env[key] === undefined) {
        process.env[key] = value;
      }
    }
  } catch {
    // Non-fatal: env vars may be supplied by Cloud Run or shell directly
  }
}

if (require.main === module) {
  loadLocalEnvIfPresent();
  const port = Number(process.env.PORT || 8080);
  const host = process.env.HOST || "0.0.0.0";
  const server = createAppServer();
  server.listen(port, host, () => {
    logStructured("info", "server_started", { host, port, nodeEnv: process.env.NODE_ENV || "development" });
  });
}

module.exports = { createAppServer, loadLocalEnvIfPresent };
