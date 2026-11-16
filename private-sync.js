const SETTINGS_KEY = 'paperjump-private-sync';
const DB_NAME = 'paperjump-private-data';

function fromBase64(value) {
  const raw = atob(value);
  return Uint8Array.from(raw, char => char.charCodeAt(0));
}

function decodePairing(value) {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
  const json = new TextDecoder().decode(fromBase64(padded));
  const settings = JSON.parse(json);
  if (!settings.key) {
    throw new Error('配对信息不完整');
  }
  if (fromBase64(settings.key).length !== 32) throw new Error('配对密钥无效');
  return settings;
}

function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore('snapshots');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function storedEnvelope(operation, value) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('snapshots', operation === 'read' ? 'readonly' : 'readwrite');
    const request = operation === 'read'
      ? transaction.objectStore('snapshots').get('latest')
      : transaction.objectStore('snapshots').put(value, 'latest');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    transaction.oncomplete = () => db.close();
  });
}

export function pairingFromLocation() {
  const encoded = new URLSearchParams(location.hash.slice(1)).get('sync');
  if (!encoded) return false;
  const settings = decodePairing(encoded);
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  history.replaceState(null, '', location.pathname + location.search);
  return true;
}

export function syncSettings() {
  const value = localStorage.getItem(SETTINGS_KEY);
  return value ? JSON.parse(value) : null;
}

export async function decryptEnvelope(envelope, keyBase64) {
  if (envelope?.format !== 'paperjump-encrypted-v1') throw new Error('同步数据格式无效');
  const key = await crypto.subtle.importKey('raw', fromBase64(keyBase64), 'AES-GCM', false, ['decrypt']);
  const compressed = await crypto.subtle.decrypt(
    {name: 'AES-GCM', iv: fromBase64(envelope.nonce)},
    key, fromBase64(envelope.ciphertext));
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate'));
  const bytes = await new Response(stream).arrayBuffer();
  const snapshot = JSON.parse(new TextDecoder().decode(bytes));
  if (snapshot.format !== 'paperjump-private-mobile-v1') throw new Error('绑定数据格式无效');
  return snapshot;
}

export async function cachedSnapshot() {
  const settings = syncSettings();
  if (!settings) return null;
  const envelope = await storedEnvelope('read');
  return envelope ? decryptEnvelope(envelope, settings.key) : null;
}

export async function fetchSnapshot() {
  const settings = syncSettings();
  if (!settings) throw new Error('请先用电脑配对');
  const response = await fetch('./private-data.enc.json', {cache: 'no-store'});
  if (!response.ok) throw new Error(`同步连接失败（${response.status}）`);
  const envelope = await response.json();
  const snapshot = await decryptEnvelope(envelope, settings.key);
  await storedEnvelope('write', envelope);
  return snapshot;
}
