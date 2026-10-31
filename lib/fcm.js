// Firebase Cloud Messaging, for the native Android app (android/). Each ping goes out as a
// high-priority data message carrying the same JSON the web push sends, and the app builds
// the notification itself, so it can do what a web notification can't (a countdown, a
// progress bar, buttons that don't collapse the shade). No SDK: an OAuth token from the
// service account's key (a signed JWT), then one POST per device.
// Env FCM_SERVICE_ACCOUNT: the service account key JSON from Firebase (raw or base64).
import { createSign } from 'node:crypto';

export function fcmAccount() {
  const raw = (process.env.FCM_SERVICE_ACCOUNT || '').trim();
  if (!raw) return null;
  try {
    const sa = JSON.parse(raw.startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf8'));
    return sa.client_email && sa.private_key && sa.project_id ? sa : null;
  } catch { return null; }
}

const b64url = x => Buffer.from(typeof x === 'string' ? x : JSON.stringify(x)).toString('base64url');
let cached = null; // { token, exp, email }
async function accessToken(sa, fetchFn) {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.email === sa.client_email && cached.exp - 60 > now) return cached.token;
  const head = b64url({ alg: 'RS256', typ: 'JWT' });
  const claim = b64url({ iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 });
  const sig = createSign('RSA-SHA256').update(`${head}.${claim}`).sign(sa.private_key).toString('base64url');
  const r = await fetchFn('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${head}.${claim}.${sig}` }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error(`fcm auth ${r.status} ${j.error || ''}`.trim());
  cached = { token: j.access_token, exp: now + (j.expires_in || 3600), email: sa.client_email };
  return cached.token;
}

// The message for one device. Data values must be strings, so the payload rides as one field.
export function fcmMessage(token, msg) {
  return { message: { token, data: { p: JSON.stringify(msg) }, android: { priority: 'HIGH', ttl: '3600s' } } };
}

// Send to each token; returns { sent, gone, errors } like pushAll (gone: tokens to drop).
export async function fcmSendAll(sa, tokens, msg, fetchFn = fetch) {
  const out = { sent: 0, gone: [], errors: [] };
  if (!sa || !tokens.length) return out;
  let auth;
  try { auth = await accessToken(sa, fetchFn); } catch (e) { out.errors.push(e.message); return out; }
  for (const token of tokens) {
    try {
      const r = await fetchFn(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: 'POST', headers: { Authorization: `Bearer ${auth}`, 'Content-Type': 'application/json' }, body: JSON.stringify(fcmMessage(token, msg)),
      });
      if (r.ok) { out.sent++; continue; }
      const j = await r.json().catch(() => ({}));
      const code = (j.error?.details || []).map(d => d.errorCode).find(Boolean) || j.error?.status || '';
      // The app was uninstalled or its token rotated: drop it.
      if (r.status === 404 || code === 'UNREGISTERED' || (r.status === 400 && /registration token/i.test(j.error?.message || ''))) out.gone.push(token);
      else out.errors.push(`fcm ${r.status} ${code} ${String(j.error?.message || '').slice(0, 120)}`.trim());
    } catch (e) { out.errors.push(`fcm ${e.message}`); }
  }
  return out;
}
