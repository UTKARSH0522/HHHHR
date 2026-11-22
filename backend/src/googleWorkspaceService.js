const crypto = require("node:crypto");

const CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events";
const MEET_SPACE_SCOPE = "https://www.googleapis.com/auth/meetings.space.created";
const MEET_READONLY_SCOPE = "https://www.googleapis.com/auth/meetings.space.readonly";

function pad2(n) {
  return String(n).padStart(2, "0");
}

function formatCalendarLocalDateTime(dateIso, totalMinutes, timeZone = "Asia/Kolkata") {
  const clamped = Math.max(0, Math.min(1440, Number(totalMinutes) || 0));
  let targetDate = dateIso;
  let effectiveMinutes = clamped;
  if (clamped === 1440) {
    const dt = new Date(`${dateIso}T00:00:00Z`);
    dt.setUTCDate(dt.getUTCDate() + 1);
    targetDate = dt.toISOString().slice(0, 10);
    effectiveMinutes = 0;
  }
  const hrs = Math.floor(effectiveMinutes / 60);
  const mins = effectiveMinutes % 60;
  const offset = timeZone === "UTC" ? "Z" : timeZone === "Asia/Kolkata" ? "+05:30" : "";
  return `${targetDate}T${pad2(hrs)}:${pad2(mins)}:00${offset}`;
}

function buildCalendarEventPayload(booking) {
  if (!booking.verifiedCustomerGoogleEmail || !booking.verifiedExpertGoogleEmail) {
    throw new Error("Both verified customer and expert Google emails are required for Meet invitations.");
  }
  const timeZone = booking.timezone || "Asia/Kolkata";
  const startRfc3339 = formatCalendarLocalDateTime(booking.dateIso, booking.startMinute, timeZone);
  const endRfc3339 = formatCalendarLocalDateTime(booking.dateIso, booking.endMinute, timeZone);

  return {
    calendarId: "primary",
    conferenceDataVersion: 1,
    sendUpdates: "all",
    requestBody: {
      summary: `HRConnect Consultation: ${booking.serviceTitle}`,
      description: [
        `Booking ID: ${booking.id}`,
        `Customer: ${booking.customerName} (${booking.verifiedCustomerGoogleEmail})`,
        `HR Expert: ${booking.expertName} (${booking.verifiedExpertGoogleEmail})`,
        `Topic: ${booking.topic}`,
        `Session Access: Restricted to invited verified Google accounts (TRUSTED).`,
      ].join("\n"),
      start: {
        dateTime: startRfc3339,
        timeZone,
      },
      end: {
        dateTime: endRfc3339,
        timeZone,
      },
      attendees: [
        { email: booking.verifiedExpertGoogleEmail, responseStatus: "accepted" },
        { email: booking.verifiedCustomerGoogleEmail, responseStatus: "needsAction" },
      ],
      guestsCanModify: false,
      guestsCanInviteOthers: false,
      guestsCanSeeOtherGuests: true,
      conferenceData: {
        createRequest: {
          requestId: booking.conferenceRequestId || `conf_${booking.id}`,
          conferenceSolutionKey: { type: "hangoutsMeet" },
        },
      },
      meetSpaceConfig: {
        accessType: "TRUSTED",
        entryPointAccess: "ALL",
      },
    },
  };
}

function mergeIntervals(intervals) {
  if (!Array.isArray(intervals) || intervals.length === 0) return [];
  const valid = intervals
    .filter((i) => Number.isFinite(i.startMs) && Number.isFinite(i.endMs) && i.endMs > i.startMs)
    .sort((a, b) => a.startMs - b.startMs);
  if (valid.length === 0) return [];

  const merged = [{ startMs: valid[0].startMs, endMs: valid[0].endMs }];
  for (let idx = 1; idx < valid.length; idx++) {
    const cur = valid[idx];
    const last = merged[merged.length - 1];
    if (cur.startMs <= last.endMs) {
      last.endMs = Math.max(last.endMs, cur.endMs);
    } else {
      merged.push({ startMs: cur.startMs, endMs: cur.endMs });
    }
  }
  return merged;
}

function totalMinutes(intervals) {
  const ms = mergeIntervals(intervals).reduce((acc, i) => acc + (i.endMs - i.startMs), 0);
  return Math.floor(ms / 60000);
}

function overlapMinutes(listA, listB) {
  const a = mergeIntervals(listA);
  const b = mergeIntervals(listB);
  let overlapMs = 0;
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    const start = Math.max(a[i].startMs, b[j].startMs);
    const end = Math.min(a[i].endMs, b[j].endMs);
    if (end > start) overlapMs += end - start;
    if (a[i].endMs < b[j].endMs) i++;
    else j++;
  }
  return Math.floor(overlapMs / 60000);
}

function evaluateMeetAttendance({
  participantSessions,
  expectedCustomerEmail,
  expectedExpertEmail,
  scheduledDurationMinutes,
  minFloorMinutes = 10,
  minRatio = 0.65,
}) {
  const custEmail = String(expectedCustomerEmail || "").trim().toLowerCase();
  const expEmail = String(expectedExpertEmail || "").trim().toLowerCase();
  const minRequiredMinutes = Math.max(minFloorMinutes, Math.ceil(scheduledDurationMinutes * minRatio));

  const custIntervals = [];
  const expIntervals = [];
  const unmatchedEmails = new Set();
  const unmappedSignedInUsers = new Set();
  let anonymousCount = 0;

  for (const session of participantSessions || []) {
    const email = (session.email || "").trim().toLowerCase();
    const interval = { startMs: session.startMs, endMs: session.endMs };
    if (email && email === custEmail) {
      custIntervals.push(interval);
    } else if (email && email === expEmail) {
      expIntervals.push(interval);
    } else if (session.signedInUserResource && !email) {
      unmappedSignedInUsers.add(session.signedInUserResource);
    } else if (!email) {
      anonymousCount += 1;
    } else {
      unmatchedEmails.add(email);
    }
  }

  const customerMinutes = totalMinutes(custIntervals);
  const expertMinutes = totalMinutes(expIntervals);
  const overlap = overlapMinutes(custIntervals, expIntervals);
  const unmatchedParts = [];
  if (unmatchedEmails.size > 0) unmatchedParts.push(`Unrecognized Google accounts: ${[...unmatchedEmails].join(", ")}`);
  if (unmappedSignedInUsers.size > 0) {
    unmatchedParts.push(`Signed-in Meet users without resolved email: ${[...unmappedSignedInUsers].join(", ")}`);
  }
  if (anonymousCount > 0) unmatchedParts.push(`Unauthenticated/phone attendees: ${anonymousCount}`);
  const unmatchedDetails = unmatchedParts.length > 0 ? unmatchedParts.join(" | ") : null;

  let status = "FAILED";
  let payoutHoldRecommended = true;
  let summary = "";

  if (unmappedSignedInUsers.size > 0 && (customerMinutes === 0 || expertMinutes === 0)) {
    status = "MANUAL_REVIEW";
    summary = `Meet API returned signed-in participant resource(s) without email resolution (${unmatchedDetails}). Requires admin verification.`;
  } else if (customerMinutes === 0 && expertMinutes === 0) {
    if (unmatchedDetails) {
      status = "MISMATCH";
      summary = `Neither expected account matched Meet logs. ${unmatchedDetails}`;
    } else {
      status = "FAILED";
      summary = "Neither customer nor expert joined Google Meet.";
    }
  } else if (customerMinutes > 0 && expertMinutes === 0) {
    status = "FAILED";
    summary = `Expert did not join Meet (Customer joined ${customerMinutes}m). Customer eligible for full refund review.`;
  } else if (expertMinutes > 0 && customerMinutes === 0) {
    status = unmatchedDetails ? "MISMATCH" : "MANUAL_REVIEW";
    summary = `Customer expected email absent from Meet logs (Expert waited ${expertMinutes}m). ${unmatchedDetails || "Requires policy no-show review."}`;
  } else if (overlap >= minRequiredMinutes) {
    status = "VERIFIED";
    payoutHoldRecommended = false;
    summary = `Verified via Google Meet REST API: overlap ${overlap}m >= required ${minRequiredMinutes}m (Cust=${customerMinutes}m, Exp=${expertMinutes}m).`;
  } else if (overlap >= 5) {
    status = "PARTIALLY_VERIFIED";
    summary = `Short overlapping Meet session (${overlap}m < required ${minRequiredMinutes}m). Held for admin/dispute review.`;
  } else {
    status = "MANUAL_REVIEW";
    summary = `Minimal simultaneous overlap (${overlap}m; Cust=${customerMinutes}m, Exp=${expertMinutes}m). Requires admin review.`;
  }

  return {
    status,
    verificationSource: "GOOGLE_MEET_REST_API",
    customerMinutes,
    expertMinutes,
    overlapMinutes: overlap,
    minimumRequiredMinutes: minRequiredMinutes,
    unmatchedParticipantDetails: unmatchedDetails,
    payoutHoldRecommended,
    summary,
  };
}

function base64UrlEncode(input) {
  return Buffer.from(input)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function normalizePrivateKey(rawKey) {
  if (!rawKey || typeof rawKey !== "string") return "";
  return rawKey.includes("\\n") ? rawKey.replace(/\\n/g, "\n").trim() : rawKey.trim();
}

function isWorkspaceAuthConfigured(cfg = {}) {
  const email = (cfg.serviceAccountEmail ?? process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL ?? "").trim();
  const priv = normalizePrivateKey(cfg.serviceAccountPrivateKey ?? process.env.GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY ?? "");
  const clientId = (cfg.oauthClientId ?? process.env.GOOGLE_OAUTH_CLIENT_ID ?? "").trim();
  const clientSecret = (cfg.oauthClientSecret ?? process.env.GOOGLE_OAUTH_CLIENT_SECRET ?? "").trim();
  const refreshToken = (cfg.oauthRefreshToken ?? process.env.GOOGLE_OAUTH_REFRESH_TOKEN ?? "").trim();

  const hasDwdServiceAccount = Boolean(email && priv && priv.includes("PRIVATE KEY"));
  const hasOAuthRefresh = Boolean(clientId && clientSecret && refreshToken);
  return hasDwdServiceAccount || hasOAuthRefresh;
}

function createGoogleWorkspaceClient(config = {}) {
  const calendarId = config.calendarId ?? process.env.GOOGLE_CALENDAR_ID ?? "primary";
  const workspaceDomain = config.workspaceDomain ?? process.env.GOOGLE_WORKSPACE_DOMAIN ?? "";
  const workspaceSubject = config.workspaceSubject ?? process.env.GOOGLE_WORKSPACE_SUBJECT ?? "";
  const serviceAccountEmail = (config.serviceAccountEmail ?? process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL ?? "").trim();
  const serviceAccountPrivateKey = normalizePrivateKey(
    config.serviceAccountPrivateKey ?? process.env.GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY ?? ""
  );
  const oauthClientId = (config.oauthClientId ?? process.env.GOOGLE_OAUTH_CLIENT_ID ?? "").trim();
  const oauthClientSecret = (config.oauthClientSecret ?? process.env.GOOGLE_OAUTH_CLIENT_SECRET ?? "").trim();
  const oauthRefreshToken = (config.oauthRefreshToken ?? process.env.GOOGLE_OAUTH_REFRESH_TOKEN ?? "").trim();
  const fetchImpl = config.fetchImpl || globalThis.fetch;
  const timeoutMs = config.timeoutMs || 15000;
  const pollDelayMs = Number.isFinite(config.pollDelayMs) ? config.pollDelayMs : 400;
  const pollMaxAttempts = Number.isFinite(config.pollMaxAttempts) ? config.pollMaxAttempts : 3;

  let cachedToken = null;
  let cachedTokenExpiresAtMs = 0;

  const hasServiceAccount = Boolean(serviceAccountEmail && serviceAccountPrivateKey);
  const hasOAuthRefresh = Boolean(oauthClientId && oauthClientSecret && oauthRefreshToken);
  const configured = hasServiceAccount || hasOAuthRefresh;

  async function fetchJson(url, options = {}) {
    if (typeof fetchImpl !== "function") {
      throw new Error("Fetch implementation unavailable");
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(url, { ...options, signal: controller.signal });
      const text = await response.text();
      let data = {};
      if (text) {
        try {
          data = JSON.parse(text);
        } catch {
          data = { rawText: text };
        }
      }
      if (!response.ok) {
        const msg = data?.error?.message || data?.error_description || data?.error || `HTTP ${response.status}`;
        const err = new Error(String(msg));
        err.statusCode = response.status;
        err.raw = data;
        throw err;
      }
      return data;
    } finally {
      clearTimeout(timer);
    }
  }

  async function getAccessToken() {
    const nowMs = Date.now();
    if (cachedToken && nowMs < cachedTokenExpiresAtMs - 60_000) {
      return cachedToken;
    }

    if (hasServiceAccount) {
      const iat = Math.floor(nowMs / 1000);
      const exp = iat + 3600;
      const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
      const claimObj = {
        iss: serviceAccountEmail,
        scope: [CALENDAR_SCOPE, MEET_SPACE_SCOPE, MEET_READONLY_SCOPE].join(" "),
        aud: "https://oauth2.googleapis.com/token",
        iat,
        exp,
      };
      if (workspaceSubject) {
        claimObj.sub = workspaceSubject;
      }
      const claims = base64UrlEncode(JSON.stringify(claimObj));
      const signingInput = `${header}.${claims}`;
      const signer = crypto.createSign("RSA-SHA256");
      signer.update(signingInput);
      const signature = signer
        .sign(serviceAccountPrivateKey)
        .toString("base64")
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/g, "");
      const assertion = `${signingInput}.${signature}`;

      const tokenResp = await fetchJson("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
          assertion,
        }).toString(),
      });
      cachedToken = tokenResp.access_token;
      cachedTokenExpiresAtMs = nowMs + Number(tokenResp.expires_in || 3600) * 1000;
      return cachedToken;
    }

    if (hasOAuthRefresh) {
      const tokenResp = await fetchJson("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: oauthClientId,
          client_secret: oauthClientSecret,
          refresh_token: oauthRefreshToken,
          grant_type: "refresh_token",
        }).toString(),
      });
      cachedToken = tokenResp.access_token;
      cachedTokenExpiresAtMs = nowMs + Number(tokenResp.expires_in || 3600) * 1000;
      return cachedToken;
    }

    throw new Error("Google Workspace OAuth/Service Account credentials not configured");
  }

  async function authorizedGoogleRequest(method, url, body = undefined) {
    const token = await getAccessToken();
    const headers = {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    };
    const opts = { method, headers };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetchJson(url, opts);
  }

  function extractMeetCodeFromUrl(url) {
    if (typeof url !== "string") return "";
    const match = /meet\.google\.com\/([a-z]{3}-[a-z]{4}-[a-z]{3})/i.exec(url.trim());
    return match ? match[1].toLowerCase() : "";
  }

  async function fetchAllPages(urlBase, arrayKey, maxPages = 25) {
    const collected = [];
    let pageToken = "";
    for (let page = 0; page < maxPages; page++) {
      const sep = urlBase.includes("?") ? "&" : "?";
      const pageUrl = pageToken
        ? `${urlBase}${sep}pageToken=${encodeURIComponent(pageToken)}`
        : urlBase;
      const resp = await authorizedGoogleRequest("GET", pageUrl);
      const items = Array.isArray(resp?.[arrayKey]) ? resp[arrayKey] : [];
      collected.push(...items);
      if (!resp?.nextPageToken) break;
      pageToken = String(resp.nextPageToken);
    }
    return collected;
  }

  /**
   * Creates the Google Calendar v3 event FIRST (conferenceDataVersion=1&sendUpdates=all) so both
   * Calendar invitees and Firestore share ONE single Meet conference, polls briefly if conferenceData
   * is pending, and then resolves & PATCHes that exact Meet v2 space's accessType (TRUSTED when Workspace domain set).
   */
  async function createCalendarMeetEvent(booking) {
    const calendarSpec = buildCalendarEventPayload(booking);
    const calBody = { ...calendarSpec.requestBody };
    delete calBody.meetSpaceConfig;

    let eventObj = await authorizedGoogleRequest(
      "POST",
      `https://www.googleapis.com/calendar/v3/calendars/${encodeURIComponent(calendarId)}/events?conferenceDataVersion=1&sendUpdates=all`,
      calBody
    );

    if (
      eventObj?.id &&
      eventObj?.conferenceData?.createRequest?.status?.statusCode === "pending"
    ) {
      for (let attempt = 0; attempt < pollMaxAttempts; attempt++) {
        if (pollDelayMs > 0) {
          await new Promise((resolve) => setTimeout(resolve, pollDelayMs));
        }
        const refreshed = await authorizedGoogleRequest(
          "GET",
          `https://www.googleapis.com/calendar/v3/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(
            eventObj.id
          )}`
        );
        eventObj = refreshed || eventObj;
        const statusCode = eventObj?.conferenceData?.createRequest?.status?.statusCode;
        if (statusCode && statusCode !== "pending") break;
        if (eventObj?.hangoutLink && eventObj?.conferenceData?.conferenceId) break;
      }
    }

    const videoEntryPoint = (eventObj?.conferenceData?.entryPoints || []).find(
      (ep) => ep.entryPointType === "video" && ep.uri
    );
    const meetingUrl = eventObj?.hangoutLink || videoEntryPoint?.uri || "";
    const googleMeetCode =
      eventObj?.conferenceData?.conferenceId || extractMeetCodeFromUrl(meetingUrl) || "";

    let googleMeetSpaceId = "";
    let accessMode = "CALENDAR_INVITEES_STANDARD";

    if (googleMeetCode) {
      try {
        const spaceResp = await authorizedGoogleRequest(
          "GET",
          `https://meet.googleapis.com/v2/spaces/${encodeURIComponent(googleMeetCode)}`
        );
        if (spaceResp?.name) {
          googleMeetSpaceId = spaceResp.name;
          const desiredAccessType = workspaceDomain ? "TRUSTED" : "OPEN";
          try {
            const patched = await authorizedGoogleRequest(
              "PATCH",
              `https://meet.googleapis.com/v2/${spaceResp.name}?updateMask=config.accessType,config.entryPointAccess`,
              {
                config: {
                  accessType: desiredAccessType,
                  entryPointAccess: "ALL",
                },
              }
            );
            accessMode = patched?.config?.accessType || desiredAccessType;
          } catch {
            // Consumer @gmail.com organizers cannot enforce TRUSTED space restriction; preserve resolved space ID.
            accessMode = spaceResp?.config?.accessType || "CALENDAR_INVITEES_STANDARD";
          }
        }
      } catch {
        // Meet v2 space lookup requires Meet scopes/Workspace organizer; Calendar hangoutLink still works cleanly.
      }
    }

    return {
      syncStatus: "SYNCED",
      googleCalendarEventId: eventObj?.id || "",
      meetingUrl,
      googleMeetSpaceId,
      googleMeetCode,
      meetingAccessMode: accessMode,
      calendarPayload: calendarSpec,
    };
  }

  async function patchCalendarEvent(booking) {
    if (!booking.googleCalendarEventId) {
      return createCalendarMeetEvent(booking);
    }
    const calendarSpec = buildCalendarEventPayload(booking);
    const updated = await authorizedGoogleRequest(
      "PATCH",
      `https://www.googleapis.com/calendar/v3/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(
        booking.googleCalendarEventId
      )}?sendUpdates=all`,
      {
        summary: calendarSpec.requestBody.summary,
        description: calendarSpec.requestBody.description,
        start: calendarSpec.requestBody.start,
        end: calendarSpec.requestBody.end,
        attendees: calendarSpec.requestBody.attendees,
      }
    );
    return {
      syncStatus: "UPDATED",
      googleCalendarEventId: updated?.id || booking.googleCalendarEventId,
      meetingUrl: booking.meetingUrl || updated?.hangoutLink || "",
    };
  }

  async function cancelCalendarEvent(eventId) {
    if (!configured || !eventId) return { cancelled: false, reason: "Not configured or missing eventId" };
    try {
      await authorizedGoogleRequest(
        "DELETE",
        `https://www.googleapis.com/calendar/v3/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(
          eventId
        )}?sendUpdates=all`
      );
      return { cancelled: true };
    } catch (err) {
      if (err?.statusCode === 404 || err?.statusCode === 410) {
        return { cancelled: true, alreadyGone: true };
      }
      throw err;
    }
  }

  async function fetchMeetConferenceAttendance({
    booking,
    minFloorMinutes = 10,
    minRatio = 0.65,
  }) {
    if (!configured) {
      return {
        available: false,
        reason: "Google Workspace OAuth/Service Account credentials not configured on backend.",
      };
    }

    const filterParts = [];
    if (booking.googleMeetSpaceId) {
      filterParts.push(`space.name="${booking.googleMeetSpaceId}"`);
    } else if (booking.googleMeetCode) {
      filterParts.push(`space.meeting_code="${booking.googleMeetCode}"`);
    } else {
      return {
        available: false,
        reason: "Booking lacks googleMeetSpaceId or googleMeetCode from live Google Meet provisioning.",
      };
    }

    const filterParam = encodeURIComponent(filterParts[0]);
    const records = await fetchAllPages(
      `https://meet.googleapis.com/v2/conferenceRecords?filter=${filterParam}`,
      "conferenceRecords"
    );
    if (records.length === 0) {
      return {
        available: false,
        reason: "No completed Google Meet conferenceRecords found yet for this meeting space.",
      };
    }

    const participantSessions = [];
    for (const record of records) {
      if (!record?.name) continue;
      const participants = await fetchAllPages(
        `https://meet.googleapis.com/v2/${record.name}/participants?pageSize=100`,
        "participants"
      );

      for (const participant of participants) {
        const signedInUser = participant?.signedinUser?.user || "";
        const directEmail =
          participant?.signedinUser?.email ||
          participant?.email ||
          (signedInUser.includes("@") ? signedInUser : "") ||
          (participant?.signedinUser?.displayName && participant.signedinUser.displayName.includes("@")
            ? participant.signedinUser.displayName
            : "");

        const sessions = await fetchAllPages(
          `https://meet.googleapis.com/v2/${participant.name}/participantSessions?pageSize=100`,
          "participantSessions"
        );
        for (const s of sessions) {
          const startMs = Date.parse(s.startTime);
          const endMs = s.endTime ? Date.parse(s.endTime) : Date.now();
          participantSessions.push({
            email: directEmail,
            signedInUserResource: signedInUser,
            startMs,
            endMs,
          });
        }
      }
    }

    const evaluation = evaluateMeetAttendance({
      participantSessions,
      expectedCustomerEmail: booking.verifiedCustomerGoogleEmail,
      expectedExpertEmail: booking.verifiedExpertGoogleEmail,
      scheduledDurationMinutes: Number(booking.durationMinutes || 30),
      minFloorMinutes,
      minRatio,
    });

    return {
      available: true,
      conferenceRecordId: records
        .map((r) => r.name)
        .filter(Boolean)
        .join(", ")
        .slice(0, 200),
      evaluation,
    };
  }

  return {
    isConfigured: () => configured,
    hasServiceAccount: () => hasServiceAccount,
    createCalendarMeetEvent,
    patchCalendarEvent,
    cancelCalendarEvent,
    fetchMeetConferenceAttendance,
  };
}

module.exports = {
  buildCalendarEventPayload,
  evaluateMeetAttendance,
  mergeIntervals,
  overlapMinutes,
  isWorkspaceAuthConfigured,
  createGoogleWorkspaceClient,
};
