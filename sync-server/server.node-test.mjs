import { test } from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSyncServer, validateLibrary } from './server.mjs';

const sampleSong = { id: 'blue-skies', title: 'Blue Skies', artist: 'HoliznaCC0', album: 'Happy Lo-Fi', artwork: 'images/cover.webp', audioAsset: 'audio/blue-skies.mp3', videoId: '' };
const sample = { liked: [sampleSong], playlists: [{ id: 'abc-123', name: 'Mix', songs: [sampleSong] }] };
async function listening(dir) {
  const server = await createSyncServer({ dataDir: dir });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}
async function close(server) { await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())); }
async function request(url, route, { method = 'GET', token, body } = {}) {
  const response = await fetch(url + route, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  return { status: response.status, data: await response.json() };
}

test('register, authorize, compare-and-swap, persist and isolate accounts', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'ft-sync-test-'));
  let running = await listening(dir);
  try {
    assert.equal((await request(running.url, '/v1/library')).status, 401);
    assert.equal((await request(running.url, '/v1/register', { method: 'POST', body: { username: 'abc', password: 'short' } })).status, 400);
    assert.equal((await request(running.url, '/v1/register', { method: 'POST', body: { username: '__proto__', password: 'correct-horse-123' } })).status, 400);
    const register = await request(running.url, '/v1/register', { method: 'POST', body: { username: 'Alice', password: 'correct-horse-123' } });
    assert.equal(register.status, 201); assert.equal(register.data.username, 'alice');
    const token = register.data.token;
    assert.match(register.data.accountId, /^[A-Za-z0-9_-]{22}$/);
    assert.equal((await request(running.url, '/v1/login', { method: 'POST', body: { username: 'alice', password: 'incorrect-password' } })).status, 401);
    assert.equal((await request(running.url, '/v1/register', { method: 'POST', body: { username: 'ALICE', password: 'correct-horse-123' } })).status, 409);
    const initial = await request(running.url, '/v1/library', { token });
    assert.deepEqual(initial.data, { revision: 0, library: { liked: [], playlists: [] } });
    const pair = await Promise.all([1, 2].map(() => request(running.url, '/v1/library', { method: 'PUT', token, body: { revision: 0, library: sample } })));
    assert.deepEqual(pair.map(item => item.status).sort(), [200, 409]);
    assert.deepEqual((await request(running.url, '/v1/library', { token })).data, { revision: 1, library: sample });
    const other = await request(running.url, '/v1/register', { method: 'POST', body: { username: 'bob', password: 'other-password-123' } });
    assert.equal((await request(running.url, '/v1/library', { token: other.data.token })).data.library.liked.length, 0);
    const persisted = JSON.parse(await fs.readFile(path.join(dir, 'accounts.json'), 'utf8'));
    assert.ok(!JSON.stringify(persisted).includes('correct-horse-123')); assert.equal(persisted.accounts.alice.revision, 1);
    assert.equal((await fs.stat(path.join(dir, 'accounts.json'))).mode & 0o777, 0o600);
    await close(running.server); running = await listening(dir);
    assert.equal((await request(running.url, '/v1/library', { token })).status, 401, 'restart expires sessions');
    const login = await request(running.url, '/v1/login', { method: 'POST', body: { username: 'alice', password: 'correct-horse-123' } });
    assert.equal(login.data.accountId, register.data.accountId, 'account incarnation persists across restarts');
    assert.deepEqual((await request(running.url, '/v1/library', { token: login.data.token })).data.library, sample);
    assert.equal((await request(running.url, '/v1/library', { method: 'PUT', token: login.data.token, body: { revision: 1, library: { liked: [sampleSong, sampleSong], playlists: [] } } })).status, 400);
    assert.equal((await request(running.url, '/v1/library', { method: 'PUT', token: login.data.token, body: { revision: 1, library: { padding: 'x'.repeat(2 * 1024 * 1024) } } })).status, 413);
    assert.equal((await request(running.url, '/v1/logout', { method: 'POST', token: login.data.token })).status, 200);
    assert.equal((await request(running.url, '/v1/library', { token: login.data.token })).status, 401);
  } finally { await close(running.server); await fs.rm(dir, { recursive: true, force: true }); }
});

test('reject invalid, duplicate and oversized library entries', () => {
  assert.throws(() => validateLibrary({ liked: [sampleSong, sampleSong], playlists: [] }));
  assert.throws(() => validateLibrary({ liked: [{ ...sampleSong, id: 'yt:bad', videoId: 'bad' }], playlists: [] }));
  assert.throws(() => validateLibrary({ liked: [], playlists: [{ id: '../bad', name: 'Mix', songs: [] }] }));
  assert.throws(() => validateLibrary({ liked: [], playlists: [{ id: 'ok', name: 'x'.repeat(61), songs: [] }] }));
  assert.deepEqual(validateLibrary(sample), sample);
});
