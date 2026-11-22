const { initializeApp, getApps, getApp, cert, applicationDefault } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

const DEFAULT_PROJECT_ID = "gen-lang-client-0782301491";
const DEFAULT_DATABASE_ID = "ai-studio-android-hrconnec-b03b6447-7546-4ed2-bdef-5257728737bb";

function parseServiceAccount(rawJson) {
  if (!rawJson || typeof rawJson !== "string" || !rawJson.trim()) return null;
  let parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (err) {
    throw new Error(`Invalid FIREBASE_SERVICE_ACCOUNT_JSON: ${err.message}`);
  }
  if (!parsed || typeof parsed !== "object" || !parsed.project_id || !parsed.private_key || !parsed.client_email) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON must contain project_id, client_email, and private_key");
  }
  if (typeof parsed.private_key === "string" && parsed.private_key.includes("\\n")) {
    parsed.private_key = parsed.private_key.replace(/\\n/g, "\n");
  }
  return parsed;
}

function getFirebaseServices(options = {}) {
  const projectId =
    options.projectId ||
    process.env.FIREBASE_PROJECT_ID ||
    process.env.FIRESTORE_PROJECT_ID ||
    process.env.GCLOUD_PROJECT ||
    DEFAULT_PROJECT_ID;

  const databaseId =
    options.databaseId ||
    process.env.FIRESTORE_DATABASE_ID ||
    DEFAULT_DATABASE_ID;

  const appName = options.appName || "hrconnect-backend";
  const existing = getApps().find((a) => a.name === appName);

  let app;
  if (existing) {
    app = existing;
  } else {
    const serviceAccount = parseServiceAccount(
      options.serviceAccountJson ?? process.env.FIREBASE_SERVICE_ACCOUNT_JSON
    );
    const appConfig = { projectId };
    if (serviceAccount) {
      appConfig.credential = cert(serviceAccount);
      appConfig.projectId = serviceAccount.project_id || projectId;
    } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      appConfig.credential = applicationDefault();
    } else if (!process.env.FIRESTORE_EMULATOR_HOST && !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
      // Fall back to Application Default Credentials in managed GCP/Cloud Run runtimes.
      appConfig.credential = applicationDefault();
    }
    app = initializeApp(appConfig, appName);
  }

  const auth = getAuth(app);
  const db = getFirestore(app, databaseId);
  let messaging = null;
  try {
    messaging = getMessaging(app);
  } catch {
    messaging = null;
  }

  return {
    app,
    auth,
    db,
    messaging,
    FieldValue,
    Timestamp,
    projectId,
    databaseId,
  };
}

module.exports = {
  DEFAULT_PROJECT_ID,
  DEFAULT_DATABASE_ID,
  getFirebaseServices,
  parseServiceAccount,
};
