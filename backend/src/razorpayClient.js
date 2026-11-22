const crypto = require("node:crypto");

class RazorpayApiError extends Error {
  constructor(statusCode, message, { code = "RAZORPAY_ERROR", raw = null } = {}) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
    this.raw = raw;
  }
}

const PLACEHOLDER_MARKERS = [
  "placeholder",
  "replace_me",
  "changeme",
  "your_",
  "dummy",
  "example",
];

function isPlaceholderValue(value) {
  const clean = String(value || "").trim().toLowerCase();
  if (!clean) return true;
  return PLACEHOLDER_MARKERS.some((marker) => clean.includes(marker));
}

function isRazorpayApiConfigured({ keyId, keySecret }) {
  return !isPlaceholderValue(keyId) && !isPlaceholderValue(keySecret);
}

function getPayoutMode({
  payoutMode = process.env.RAZORPAY_PAYOUT_MODE,
  routeEnabled = process.env.RAZORPAY_ROUTE_ENABLED,
  razorpayXAccountNumber = process.env.RAZORPAYX_ACCOUNT_NUMBER,
} = {}) {
  const clean = String(payoutMode || "").trim().toLowerCase();
  if (clean === "razorpayx" || clean === "route" || clean === "manual_utr") {
    return clean;
  }
  if (!isPlaceholderValue(razorpayXAccountNumber)) return "razorpayx";
  if (String(routeEnabled || "").toLowerCase() === "true") return "route";
  return "manual_utr";
}

function createRazorpayClient({
  keyId = process.env.RAZORPAY_KEY_ID || "",
  keySecret = process.env.RAZORPAY_KEY_SECRET || "",
  razorpayXAccountNumber = process.env.RAZORPAYX_ACCOUNT_NUMBER || "",
  baseUrl = process.env.RAZORPAY_API_BASE_URL || "https://api.razorpay.com/v1",
  timeoutMs = 15000,
  fetchImpl = globalThis.fetch,
} = {}) {
  const configured = isRazorpayApiConfigured({ keyId, keySecret });

  function getAuthHeader() {
    if (!configured) {
      throw new RazorpayApiError(
        503,
        "Razorpay API credentials (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET) are not configured",
        { code: "RAZORPAY_NOT_CONFIGURED" }
      );
    }
    const token = Buffer.from(`${keyId}:${keySecret}`).toString("base64");
    return `Basic ${token}`;
  }

  async function request(method, path, body = null, extraHeaders = {}) {
    if (typeof fetchImpl !== "function") {
      throw new RazorpayApiError(500, "Fetch implementation is unavailable for Razorpay HTTP client");
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const headers = {
        Authorization: getAuthHeader(),
        Accept: "application/json",
        ...extraHeaders,
      };
      const init = {
        method,
        headers,
        signal: controller.signal,
      };
      if (body !== null && body !== undefined) {
        headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
      }

      const response = await fetchImpl(`${baseUrl.replace(/\/+$/, "")}${path}`, init);
      const rawText = await response.text();
      let parsed = {};
      if (rawText) {
        try {
          parsed = JSON.parse(rawText);
        } catch {
          parsed = { rawText };
        }
      }

      if (!response.ok) {
        const msg =
          parsed?.error?.description ||
          parsed?.error?.reason ||
          parsed?.message ||
          `Razorpay HTTP ${response.status}`;
        const code = parsed?.error?.code || `HTTP_${response.status}`;
        throw new RazorpayApiError(response.status >= 500 ? 502 : response.status, msg, {
          code,
          raw: parsed,
        });
      }
      return parsed;
    } catch (err) {
      if (err instanceof RazorpayApiError) throw err;
      if (err?.name === "AbortError") {
        throw new RazorpayApiError(504, "Razorpay API request timed out", { code: "RAZORPAY_TIMEOUT" });
      }
      throw new RazorpayApiError(502, `Razorpay network error: ${err?.message || err}`, {
        code: "RAZORPAY_NETWORK_ERROR",
      });
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    isConfigured: () => configured,
    isTestKey: () => String(keyId).startsWith("rzp_test_"),
    getKeyId: () => keyId,
    getRazorpayXAccountNumber: () => razorpayXAccountNumber,

    async createOrder({ amountPaise, currency = "INR", receipt, notes = {} }) {
      return request("POST", "/orders", {
        amount: amountPaise,
        currency,
        receipt: String(receipt || "").slice(0, 40),
        payment_capture: 1,
        notes,
      });
    },

    async fetchPayment(paymentId) {
      return request("GET", `/payments/${encodeURIComponent(paymentId)}`);
    },

    async capturePayment(paymentId, amountPaise, currency = "INR") {
      return request("POST", `/payments/${encodeURIComponent(paymentId)}/capture`, {
        amount: amountPaise,
        currency,
      });
    },

    async createRefund(paymentId, { amountPaise, notes = {}, speed = "normal", receipt = undefined } = {}) {
      const payload = {
        amount: amountPaise,
        speed,
        notes,
      };
      if (receipt) payload.receipt = String(receipt).slice(0, 40);
      return request("POST", `/payments/${encodeURIComponent(paymentId)}/refund`, payload);
    },

    async fetchRefund(refundId) {
      return request("GET", `/refunds/${encodeURIComponent(refundId)}`);
    },

    async createContact({ name, email, contact, type = "vendor", referenceId }) {
      return request("POST", "/contacts", {
        name: String(name || "HR Expert").slice(0, 50),
        email: email || undefined,
        contact: contact || undefined,
        type,
        reference_id: referenceId,
      });
    },

    async createFundAccount({ contactId, accountType, vpaAddress, bankAccount }) {
      if (accountType === "vpa") {
        return request("POST", "/fund_accounts", {
          contact_id: contactId,
          account_type: "vpa",
          vpa: { address: vpaAddress },
        });
      }
      return request("POST", "/fund_accounts", {
        contact_id: contactId,
        account_type: "bank_account",
        bank_account: {
          name: bankAccount.accountHolderName,
          ifsc: bankAccount.ifsc,
          account_number: bankAccount.accountNumber,
        },
      });
    },

    async createPayout({
      accountNumber = razorpayXAccountNumber,
      fundAccountId,
      amountPaise,
      currency = "INR",
      mode = "IMPS",
      purpose = "payout",
      referenceId,
      narration = "HRConnect Consultation Payout",
      idempotencyKey,
      notes = {},
    }) {
      if (isPlaceholderValue(accountNumber)) {
        throw new RazorpayApiError(
          503,
          "RAZORPAYX_ACCOUNT_NUMBER is not configured for RazorpayX payouts",
          { code: "RAZORPAYX_NOT_CONFIGURED" }
        );
      }
      const idem = idempotencyKey || crypto.randomUUID();
      return request(
        "POST",
        "/payouts",
        {
          account_number: accountNumber,
          fund_account_id: fundAccountId,
          amount: amountPaise,
          currency,
          mode,
          purpose,
          queue_if_low_balance: true,
          reference_id: String(referenceId || "").slice(0, 40),
          narration: String(narration || "").slice(0, 30),
          notes,
        },
        { "X-Payout-Idempotency": idem }
      );
    },

    async createRouteTransfer(paymentId, { linkedAccountId, amountPaise, currency = "INR", notes = {}, onHold = false }) {
      return request("POST", `/payments/${encodeURIComponent(paymentId)}/transfers`, {
        transfers: [
          {
            account: linkedAccountId,
            amount: amountPaise,
            currency,
            notes,
            on_hold: onHold ? 1 : 0,
          },
        ],
      });
    },
  };
}

module.exports = {
  RazorpayApiError,
  isPlaceholderValue,
  isRazorpayApiConfigured,
  getPayoutMode,
  createRazorpayClient,
};
