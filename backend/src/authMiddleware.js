class HttpAuthError extends Error {
  constructor(statusCode, message) {
    super(message);
    this.statusCode = statusCode;
  }
}

function parseAdminEmails(rawList) {
  if (Array.isArray(rawList)) {
    return new Set(rawList.map((e) => String(e || "").trim().toLowerCase()).filter(Boolean));
  }
  const source = typeof rawList === "string" ? rawList : process.env.ADMIN_EMAILS || "";
  const parsed = source
    .split(",")
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean);
  return new Set(parsed);
}

function extractBearerToken(req) {
  const header = req.headers?.authorization || req.headers?.Authorization;
  if (typeof header !== "string") {
    throw new HttpAuthError(401, "Missing Authorization header");
  }
  const match = /^Bearer\s+([^\s]+)$/i.exec(header.trim());
  if (!match || !match[1]) {
    throw new HttpAuthError(401, "Malformed Authorization Bearer header");
  }
  const token = match[1].trim();
  if (token.length < 10) {
    throw new HttpAuthError(401, "Malformed Firebase ID token");
  }
  return token;
}

async function resolveAdminPrivileges(decodedToken, db, adminEmailAllowlist) {
  if (!decodedToken || !decodedToken.uid) return false;

  // Check Firestore admins/{uid} FIRST so disabling an admin (active === false)
  // immediately revokes access even if cached custom claims or email allowlist exist.
  if (db) {
    const adminSnap = await db.collection("admins").doc(decodedToken.uid).get();
    if (adminSnap.exists) {
      const adminData = adminSnap.data() || {};
      if (adminData.active === false) {
        return false;
      }
      return true;
    }
  }

  if (decodedToken.admin === true || decodedToken.role === "ADMIN") {
    return true;
  }

  const email = typeof decodedToken.email === "string" ? decodedToken.email.trim().toLowerCase() : "";
  if (decodedToken.email_verified === true && email && adminEmailAllowlist.has(email)) {
    return true;
  }
  return false;
}

/**
 * Verifies the Firebase ID token from Authorization: Bearer <token>,
 * checks revocation, extracts authoritative UID/claims, and resolves server-side admin privileges.
 * Never trusts UID or admin flags from request bodies.
 */
async function authenticateFirebaseRequest(req, { authService, db, adminEmails } = {}) {
  if (!authService || typeof authService.verifyIdToken !== "function") {
    throw new HttpAuthError(503, "Firebase Auth service is unavailable");
  }
  const token = extractBearerToken(req);
  let decoded;
  try {
    decoded = await authService.verifyIdToken(token, true);
  } catch (err) {
    const code = err?.code || "";
    if (code === "auth/id-token-expired" || /expired/i.test(err?.message || "")) {
      throw new HttpAuthError(401, "Firebase ID token has expired");
    }
    if (code === "auth/id-token-revoked" || /revoked/i.test(err?.message || "")) {
      throw new HttpAuthError(401, "Firebase ID token has been revoked");
    }
    throw new HttpAuthError(401, "Invalid Firebase ID token");
  }

  if (!decoded || typeof decoded.uid !== "string" || !decoded.uid.trim()) {
    throw new HttpAuthError(401, "Verified token missing UID claim");
  }

  const allowlist = parseAdminEmails(adminEmails);
  const isAdmin = await resolveAdminPrivileges(decoded, db, allowlist);

  return {
    uid: decoded.uid,
    email: typeof decoded.email === "string" ? decoded.email.trim().toLowerCase() : "",
    emailVerified: decoded.email_verified === true,
    isAdmin,
  };
}

module.exports = {
  HttpAuthError,
  authenticateFirebaseRequest,
  extractBearerToken,
  resolveAdminPrivileges,
  parseAdminEmails,
};
