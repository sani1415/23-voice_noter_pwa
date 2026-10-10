/* Gemini File API — resumable upload session তৈরি করে uploadUrl ফেরত দেয়।
   Browser সেই URL-এ সরাসরি file পাঠায়, Vercel-এর 4.5 MB limit বাইপাস হয়। */

const { sendJson, setCorsHeaders, handlePreflight, requireUser } = require('./_auth');

module.exports = async function handler(req, res) {
  setCorsHeaders(req, res);
  if (handlePreflight(req, res)) return;

  if (req.method !== 'POST') { sendJson(res, 405, { error: 'Method not allowed' }); return; }

  if (!(await requireUser(req, res))) return;

  const apiKey = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
  if (!apiKey) { sendJson(res, 500, { error: 'Missing GEMINI_API_KEY' }); return; }

  let payload;
  try {
    if (req.body && typeof req.body === 'object') payload = req.body;
    else { let raw = ''; for await (const c of req) raw += c; payload = JSON.parse(raw || '{}'); }
  } catch { sendJson(res, 400, { error: 'Invalid JSON' }); return; }

  const { mimeType, size, displayName } = payload;
  if (!mimeType || !size) { sendJson(res, 400, { error: 'mimeType এবং size আবশ্যক' }); return; }

  const initUrl = `https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=resumable&key=${apiKey}`;

  let initRes;
  try {
    initRes = await fetch(initUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Upload-Protocol': 'resumable',
        'X-Goog-Upload-Command': 'start',
        'X-Goog-Upload-Header-Content-Length': String(size),
        'X-Goog-Upload-Header-Content-Type': mimeType,
      },
      body: JSON.stringify({ file: { display_name: displayName || 'audio_upload' } }),
    });
  } catch (err) {
    sendJson(res, 502, { error: 'Gemini-এ সংযোগ ব্যর্থ: ' + err.message });
    return;
  }

  if (!initRes.ok) {
    const e = await initRes.json().catch(() => ({}));
    sendJson(res, initRes.status, { error: e?.error?.message || 'Upload session তৈরি ব্যর্থ' });
    return;
  }

  const uploadUrl = initRes.headers.get('x-goog-upload-url');
  if (!uploadUrl) { sendJson(res, 502, { error: 'Gemini থেকে upload URL পাওয়া যায়নি' }); return; }

  sendJson(res, 200, { uploadUrl });
};
