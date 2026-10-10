/* API-র পাহারাদার: শুধু লগইন করা, active Voice Notyper ইউজার এই endpoint-গুলো
   ব্যবহার করতে পারে। Supabase access token (Authorization: Bearer …) যাচাই করা হয়,
   তারপর vn_profiles.is_active দেখা হয় — PWA লগইনের সময় যে নিয়ম মানে, সেটাই।
   ফাইলের নাম _ দিয়ে শুরু, তাই Vercel এটাকে আলাদা endpoint বানায় না। */

// Public project URL + anon key (PWA-র app.js-এও আছে); env দিলে সেটাই চলবে।
const SB_URL = process.env.SUPABASE_URL || 'https://iqjajofqaimnqvrxgdzc.supabase.co';
const SB_ANON_KEY = process.env.SUPABASE_ANON_KEY
  || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlxamFqb2ZxYWltbnF2cnhnZHpjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEyMDM0NzYsImV4cCI6MjA4Njc3OTQ3Nn0.epEV-GGXUsJe-u4Aumx284qGPrCFZcuXFGN9qmtwwbM';

// একই token বারবার যাচাই না করে কয়েক মিনিট মনে রাখা (Fluid Compute instance-এ থাকে)।
const CACHE_MS = 5 * 60 * 1000;
const MAX_CACHE = 500;
const verified = new Map(); // token -> { userId, until }

function sendJson(res, statusCode, body) {
  res.statusCode = statusCode;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}

/** Browser থেকে শুধু নিজের সাইট (same origin) CORS পায়; Android app কোনো Origin পাঠায় না। */
function setCorsHeaders(req, res) {
  const origin = req.headers.origin || '';
  if (!origin) return;
  let sameHost = false;
  try { sameHost = new URL(origin).host === req.headers.host; } catch { /* bad origin */ }
  if (sameHost) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
  }
}

/** OPTIONS preflight সামলায়; true ফেরত দিলে handler-এর আর কিছু করার নেই। */
function handlePreflight(req, res) {
  if (req.method !== 'OPTIONS') return false;
  res.statusCode = 204;
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.end('');
  return true;
}

function bearerToken(req) {
  const header = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : '';
}

async function lookupActiveUser(token) {
  const headers = { apikey: SB_ANON_KEY, Authorization: `Bearer ${token}` };

  const userRes = await fetch(`${SB_URL}/auth/v1/user`, { headers });
  if (!userRes.ok) return null;
  const user = await userRes.json().catch(() => null);
  if (!user?.id) return null;

  // RLS-এ ইউজার নিজের profile পড়তে পারে, তাই একই token দিয়েই চলে।
  const profileRes = await fetch(
    `${SB_URL}/rest/v1/vn_profiles?select=is_active&user_id=eq.${encodeURIComponent(user.id)}`,
    { headers },
  );
  if (!profileRes.ok) return null;
  const rows = await profileRes.json().catch(() => []);
  if (!Array.isArray(rows) || !rows[0]?.is_active) return null;
  return user.id;
}

/**
 * লগইন যাচাই করে userId ফেরত দেয়। ব্যর্থ হলে নিজেই 401 পাঠিয়ে null ফেরত দেয়,
 * তখন handler থেমে যাবে।
 */
async function requireUser(req, res) {
  const token = bearerToken(req);
  if (!token) {
    sendJson(res, 401, { error: 'লগইন প্রয়োজন', code: 'auth_required' });
    return null;
  }

  const now = Date.now();
  const hit = verified.get(token);
  if (hit && hit.until > now) return hit.userId;

  let userId = null;
  try {
    userId = await lookupActiveUser(token);
  } catch {
    sendJson(res, 503, { error: 'লগইন যাচাই করা যায়নি, একটু পরে চেষ্টা করুন', code: 'auth_unavailable' });
    return null;
  }
  if (!userId) {
    verified.delete(token);
    sendJson(res, 401, { error: 'লগইনের মেয়াদ শেষ বা access নেই', code: 'auth_invalid' });
    return null;
  }

  if (verified.size >= MAX_CACHE) verified.delete(verified.keys().next().value);
  verified.set(token, { userId, until: now + CACHE_MS });
  return userId;
}

module.exports = { sendJson, setCorsHeaders, handlePreflight, requireUser };
