const REDACTED_KEY_PATTERN =
  /^(authorization|x-razorpay-signature|razorpaySignature|keySecret|webhookSecret|private_key|privateKey|refreshToken|refresh_token|serviceAccountJson|password|token)$/i;

function redactObject(value, depth = 0) {
  if (depth > 4 || value === null || value === undefined) return value;
  if (typeof value === "string") {
    if (value.startsWith("Bearer ")) return "Bearer [REDACTED]";
    if (value.includes("BEGIN PRIVATE KEY")) return "[REDACTED_PRIVATE_KEY]";
    return value;
  }
  if (Array.isArray(value)) {
    return value.slice(0, 20).map((item) => redactObject(item, depth + 1));
  }
  if (typeof value === "object") {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (REDACTED_KEY_PATTERN.test(k)) {
        out[k] = "[REDACTED]";
      } else {
        out[k] = redactObject(v, depth + 1);
      }
    }
    return out;
  }
  return value;
}

function logStructured(level, event, details = {}, logger = console) {
  const payload = {
    timestamp: new Date().toISOString(),
    level,
    event,
    ...redactObject(details),
  };
  const line = JSON.stringify(payload);
  if (level === "error") logger.error(line);
  else if (level === "warn") logger.warn(line);
  else logger.log(line);
}

function createSlidingWindowRateLimiter({
  windowMs = 60_000,
  maxRequests = 120,
} = {}) {
  const buckets = new Map();

  function check(key, nowMs = Date.now()) {
    const cleanKey = String(key || "unknown");
    const cutoff = nowMs - windowMs;
    const prev = buckets.get(cleanKey) || [];
    const active = prev.filter((ts) => ts > cutoff);
    if (active.length >= maxRequests) {
      buckets.set(cleanKey, active);
      const retryAfterSec = Math.max(1, Math.ceil((active[0] + windowMs - nowMs) / 1000));
      return { allowed: false, remaining: 0, retryAfterSec };
    }
    active.push(nowMs);
    buckets.set(cleanKey, active);

    // Occasional sweep to prevent unbounded map growth
    if (buckets.size > 10000) {
      for (const [k, list] of buckets.entries()) {
        if (list.length === 0 || list[list.length - 1] <= cutoff) {
          buckets.delete(k);
        }
      }
    }

    return { allowed: true, remaining: maxRequests - active.length, retryAfterSec: 0 };
  }

  return { check };
}

function getClientIp(req) {
  const forwarded = req.headers?.["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    const parts = forwarded
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    if (parts.length > 0) {
      // Behind Google Cloud / Cloud Run load balancer, the trusted proxy appends client IP at the right;
      // trusting index 0 allows any caller to spoof X-Forwarded-For to bypass IP rate limits.
      return parts[parts.length - 1].replace(/^::ffff:/i, "");
    }
  }
  const remote = req.socket?.remoteAddress || "unknown";
  return String(remote).replace(/^::ffff:/i, "");
}

module.exports = {
  redactObject,
  logStructured,
  createSlidingWindowRateLimiter,
  getClientIp,
};
