import http from 'node:http';
import https from 'node:https';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

// Small private, single-process service. Terminate TLS at this process or a trusted local proxy.
const scrypt = promisify(crypto.scrypt);
const MAX_BODY = 2 * 1024 * 1024;
const MAX_SONGS = 2000;
const SESSION_MS = 12 * 60 * 60 * 1000;
const emptyLibrary = () => ({ liked: [], playlists: [] });
class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
const bad = message => { throw new HttpError(400, message); };

function string(value, max, required = false) {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim()) || /[\u0000-\u001f]/.test(value)) bad('Invalid library data.');
  return value;
}
function song(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) bad('Invalid song.');
  const id = string(input.id, 100, true), title = string(input.title, 200, true);
  const artist = string(input.artist, 200), album = string(input.album, 200);
  const artwork = string(input.artwork, 2048), audioAsset = string(input.audioAsset, 300), videoId = string(input.videoId, 11);
  if (videoId) {
    if (!/^[A-Za-z0-9_-]{11}$/.test(videoId) || id !== `yt:${videoId}` || audioAsset) bad('Invalid YouTube song.');
  } else if (!/^[a-z0-9-]{1,100}$/.test(id)) bad('Invalid local song.');
  return { id, title, artist, album, artwork, audioAsset, videoId };
}
function songList(items, counter) {
  if (!Array.isArray(items) || items.length > 500) bad('Too many songs.');
  const seen = new Set();
  return items.map(item => {
    const result = song(item);
    if (seen.has(result.id) || ++counter.count > MAX_SONGS) bad('Duplicate or too many songs.');
    seen.add(result.id);
    return result;
  });
}
export function validateLibrary(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || !Array.isArray(input.playlists) || input.playlists.length > 100) bad('Invalid library.');
  const counter = { count: 0 }, liked = songList(input.liked, counter), seen = new Set();
  const playlists = input.playlists.map(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) bad('Invalid playlist.');
    const id = string(item.id, 80, true), name = string(item.name, 60, true);
    if (!/^[A-Za-z0-9_-]+$/.test(id) || seen.has(id)) bad('Invalid or duplicate playlist.');
    seen.add(id);
    return { id, name, songs: songList(item.songs, counter) };
  });
  return { liked, playlists };
}
function credentials(body) {
  if (!body || typeof body.username !== 'string' || typeof body.password !== 'string') bad('Enter a username and password.');
  const username = body.username.trim().toLowerCase(), password = body.password;
  if (!/^[a-z0-9_-]{3,32}$/.test(username) || username === '__proto__') bad('Username must have 3–32 letters, numbers, underscores or hyphens.');
  if (password.length < 12 || Buffer.byteLength(password, 'utf8') > 128) bad('Password must be at least 12 characters and at most 128 UTF-8 bytes.');
  return { username, password };
}
async function passwordHash(password, salt) { return scrypt(password, salt, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }); }
function send(res, status, value) {
  const bytes = Buffer.from(JSON.stringify(value));
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length,
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
  res.end(bytes);
}
async function readBody(req) {
  const announced = Number(req.headers['content-length'] || 0);
  if (!Number.isFinite(announced) || announced > MAX_BODY) { req.resume(); throw new HttpError(413, 'Request too large.'); }
  let total = 0; const chunks = [];
  for await (const chunk of req) {
    total += chunk.length;
    if (total > MAX_BODY) { req.resume(); throw new HttpError(413, 'Request too large.'); }
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new HttpError(400, 'Invalid JSON.'); }
}
function tokenKey(token) { return crypto.createHash('sha256').update(token).digest('hex'); }

export async function createSyncServer({ dataDir, tls = null } = {}) {
  if (!dataDir) throw new Error('A private data directory is required.');
  await fs.mkdir(dataDir, { recursive: true, mode: 0o700 });
  const file = path.join(dataDir, 'accounts.json');
  let database;
  try {
    database = JSON.parse(await fs.readFile(file, 'utf8'));
    if (!database || database.version !== 1 || !database.accounts || typeof database.accounts !== 'object') throw new Error('Bad database format');
  } catch (error) {
    if (error.code !== 'ENOENT') throw new Error(`Cannot load the sync database: ${error.message}`);
    database = { version: 1, accounts: {} };
  }
  let writes = Promise.resolve();
  async function mutate(fn) {
    const operation = writes.then(async () => {
      const next = JSON.parse(JSON.stringify(database));
      const result = await fn(next);
      const tmp = path.join(dataDir, `.accounts-${crypto.randomBytes(12).toString('hex')}.tmp`);
      try {
        const handle = await fs.open(tmp, 'wx', 0o600);
        try { await handle.writeFile(JSON.stringify(next)); await handle.sync(); } finally { await handle.close(); }
        await fs.rename(tmp, file);
        database = next;
      } finally { await fs.unlink(tmp).catch(() => {}); }
      return result;
    });
    writes = operation.catch(() => {});
    return operation;
  }
  const sessions = new Map(), attempts = new Map();
  function limit(req) {
    const now = Date.now(), key = req.socket.remoteAddress || 'unknown';
    // Never trust X-Forwarded-For from clients; a reverse proxy makes this a shared limit.
    const prior = attempts.get(key);
    const entry = !prior || now - prior.start > 5 * 60 * 1000 ? { start: now, count: 0 } : prior;
    if (attempts.size > 5000) for (const [ip, item] of attempts) if (now - item.start > 5 * 60 * 1000) attempts.delete(ip);
    if (attempts.size > 5000 && !attempts.has(key)) throw new HttpError(429, 'Too many sign-in attempts.');
    attempts.set(key, entry);
    if (++entry.count > 12) throw new HttpError(429, 'Too many sign-in attempts. Try later.');
  }
  function issue(username) {
    const now = Date.now();
    for (const [key, session] of sessions) if (session.expires <= now) sessions.delete(key);
    if (sessions.size >= 10000) throw new HttpError(503, 'Too many active sessions.');
    const token = crypto.randomBytes(32).toString('base64url');
    sessions.set(tokenKey(token), { username, expires: now + SESSION_MS });
    return token;
  }
  function auth(req) {
    const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '');
    const key = match && tokenKey(match[1]), session = key && sessions.get(key);
    if (!session || session.expires <= Date.now() || !Object.hasOwn(database.accounts, session.username)) {
      if (key) sessions.delete(key);
      throw new HttpError(401, 'Please sign in again.');
    }
    return { ...session, key };
  }
  async function handle(req, res) {
    try {
      const route = new URL(req.url, 'http://localhost').pathname;
      if (req.method === 'POST' && (route === '/v1/register' || route === '/v1/login')) {
        limit(req);
        const { username, password } = credentials(await readBody(req));
        if (route === '/v1/register') {
          const salt = crypto.randomBytes(16), hash = await passwordHash(password, salt);
          await mutate(next => {
            if (Object.hasOwn(next.accounts, username)) throw new HttpError(409, 'That username is already in use.');
            next.accounts[username] = { accountId: crypto.randomBytes(16).toString('base64url'), salt: salt.toString('base64'), hash: hash.toString('base64'), revision: 0, library: emptyLibrary() };
          });
        } else {
          const account = Object.hasOwn(database.accounts, username) ? database.accounts[username] : null;
          const salt = account ? Buffer.from(account.salt, 'base64') : Buffer.alloc(16);
          const expected = account ? Buffer.from(account.hash, 'base64') : Buffer.alloc(64);
          const actual = await passwordHash(password, salt);
          if (!account || expected.length !== actual.length || !crypto.timingSafeEqual(actual, expected)) throw new HttpError(401, 'Invalid username or password.');
        }
        send(res, route === '/v1/register' ? 201 : 200, { token: issue(username), username, accountId: database.accounts[username].accountId, expiresInSeconds: SESSION_MS / 1000 });
      } else if (req.method === 'GET' && route === '/v1/library') {
        const session = auth(req), account = database.accounts[session.username];
        send(res, 200, { revision: account.revision, library: account.library });
      } else if (req.method === 'PUT' && route === '/v1/library') {
        const session = auth(req), body = await readBody(req);
        if (!Number.isSafeInteger(body?.revision) || body.revision < 0) bad('Invalid revision.');
        const library = validateLibrary(body.library);
        const revision = await mutate(next => {
          const account = next.accounts[session.username];
          if (!account || account.revision !== body.revision) throw new HttpError(409, 'Library changed on another device.');
          account.revision++; account.library = library;
          return account.revision;
        });
        send(res, 200, { revision, library });
      } else if (req.method === 'POST' && route === '/v1/logout') {
        const session = auth(req); sessions.delete(session.key); send(res, 200, { ok: true });
      } else throw new HttpError(404, 'Not found.');
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('Sync request failed:', error.message);
      if (!res.headersSent) send(res, error.status || 500, { error: error.status ? error.message : 'Service error.' });
      else res.end();
    }
  }
  return tls ? https.createServer(tls, handle) : http.createServer(handle);
}

async function main() {
  const host = process.env.HOST || '127.0.0.1', port = Number(process.env.PORT || 8787);
  const dataDir = path.resolve(process.env.DATA_DIR || './sync-data');
  if (Boolean(process.env.TLS_CERT) !== Boolean(process.env.TLS_KEY)) throw new Error('Set both TLS_CERT and TLS_KEY.');
  const tls = process.env.TLS_CERT ? { cert: await fs.readFile(process.env.TLS_CERT), key: await fs.readFile(process.env.TLS_KEY) } : null;
  if (!tls && !['127.0.0.1', '::1', 'localhost'].includes(host)) throw new Error('Plain HTTP may bind only to loopback. Configure TLS before exposing the service.');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT.');
  const server = await createSyncServer({ dataDir, tls });
  server.listen(port, host, () => console.log(`FT Music private sync listening on ${host}:${port} (${tls ? 'TLS' : 'loopback HTTP; use a TLS proxy'})`));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.message); process.exitCode = 1; });
