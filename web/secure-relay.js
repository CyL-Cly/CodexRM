// Noise_NNpsk0_25519_AESGCM_SHA256 (Noise revision 34). The browser uses
// native Web Crypto for X25519, HKDF, SHA-256 and AES-GCM.
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
const empty = new Uint8Array();
const ready = encoder.encode('ready');
const recordSize = 65519;
const maxPlaintext = 11 << 20;
const routePattern = /^route_[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const clientPattern = /^cli_[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

function join(...parts) {
  const result = new Uint8Array(parts.reduce((length, part) => length + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function encode(bytes) {
  let text = '';
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    text += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  }
  return btoa(text);
}

function decode(value) {
  if (typeof value !== 'string') throw new Error('Invalid secure frame');
  return Uint8Array.from(atob(value), char => char.charCodeAt(0));
}

export function parsePairingCode(code) {
  const [version, route, encoded, ...extra] = code.split('.');
  if (version !== 'rm2' || !routePattern.test(route) || !/^[\w-]{43}$/.test(encoded) || extra.length) {
    throw new Error('请在电脑 Agent 上重新生成加密配对码。');
  }
  const secret = decode(encoded.replaceAll('-', '+').replaceAll('_', '/'));
  if (secret.length !== 32 || encode(secret).replaceAll('+', '-').replaceAll('/', '_').slice(0, -1) !== encoded) {
    throw new Error('配对码格式无效。');
  }
  return { route, secret: encode(secret) };
}

async function hash(...data) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', join(...data)));
}

async function hkdf(chainingKey, input, outputs) {
  const base = await crypto.subtle.importKey('raw', input, 'HKDF', false, ['deriveBits']);
  const result = new Uint8Array(await crypto.subtle.deriveBits({
    name: 'HKDF', salt: chainingKey, info: empty, hash: 'SHA-256',
  }, base, outputs * 256));
  return Array.from({ length: outputs }, (_, index) => result.subarray(index * 32, (index + 1) * 32));
}

async function aesKey(bits) {
  return crypto.subtle.importKey('raw', bits, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

async function crypt(operation, key, nonce, additionalData, data) {
  const iv = new Uint8Array(12);
  new DataView(iv.buffer).setBigUint64(4, nonce);
  return new Uint8Array(await crypto.subtle[operation]({ name: 'AES-GCM', iv, additionalData }, key, data));
}

export async function initiate(client, route, encodedSecret) {
  if (!clientPattern.test(client) || (route && !routePattern.test(route))) throw new Error('Invalid secure endpoint');
  const secret = decode(encodedSecret);
  if (secret.length !== 32) throw new Error('Invalid secure key');
  const name = encoder.encode('Noise_NNpsk0_25519_AESGCM_SHA256');
  let ck = new Uint8Array(32);
  ck.set(name);
  let h = await hash(ck, encoder.encode(`CodexRM relay e2ee v2\n${client}\n${route}`));
  let mix, key;
  [ck, mix, key] = await hkdf(ck, secret, 3);
  h = await hash(h, mix);
  const ephemeral = await crypto.subtle.generateKey('X25519', false, ['deriveBits']);
  const publicKey = new Uint8Array(await crypto.subtle.exportKey('raw', ephemeral.publicKey));
  h = await hash(h, publicKey);
  [ck, key] = await hkdf(ck, publicKey, 2);
  const tag = await crypt('encrypt', await aesKey(key), 0n, h, empty);
  h = await hash(h, tag);
  return {
    frame: secureFrame('hello', join(publicKey, tag), route),
    async accept(frame) {
      const wire = decode(frame.data);
      if (wire.length !== (route ? 80 : 48)) throw new Error('Invalid secure handshake');
      const peerPublic = wire.subarray(0, 32);
      h = await hash(h, peerPublic);
      [ck] = await hkdf(ck, peerPublic, 2);
      const peer = await crypto.subtle.importKey('raw', peerPublic, 'X25519', false, []);
      const ee = await crypto.subtle.deriveBits({ name: 'X25519', public: peer }, ephemeral.privateKey, 256);
      [ck, key] = await hkdf(ck, ee, 2);
      const payload = await crypt('decrypt', await aesKey(key), 0n, h, wire.subarray(32));
      const [sendKey, receiveKey] = await Promise.all((await hkdf(ck, empty, 2)).map(aesKey));
      return { key: route ? encode(payload) : encodedSecret, channel: new Channel(sendKey, receiveKey) };
    },
  };
}

class Channel {
  sendNonce = 0n;
  receiveNonce = 0n;
  constructor(sendKey, receiveKey) {
    this.sendKey = sendKey;
    this.receiveKey = receiveKey;
  }
  async seal(plain) {
    if (plain.length > maxPlaintext) throw new Error('Secure payload too large');
    const parts = [];
    for (let offset = 0; ; offset += recordSize) {
      const final = plain.length - offset <= recordSize;
      parts.push(await crypt('encrypt', this.sendKey, this.sendNonce++, Uint8Array.of(Number(final)), plain.subarray(offset, offset + recordSize)));
      if (final) return join(...parts);
    }
  }
  async open(ciphertext) {
    if (ciphertext.length < 16 || ciphertext.length > maxPlaintext + 16 * (Math.floor(maxPlaintext / recordSize) + 1)) {
      throw new Error('Invalid secure frame size');
    }
    const parts = [];
    for (let offset = 0; ; offset += recordSize + 16) {
      const final = ciphertext.length - offset <= recordSize + 16;
      parts.push(await crypt('decrypt', this.receiveKey, this.receiveNonce++, Uint8Array.of(Number(final)), ciphertext.subarray(offset, offset + recordSize + 16)));
      if (final) {
        const plain = join(...parts);
        if (plain.length > maxPlaintext) throw new Error('Secure payload too large');
        return plain;
      }
    }
  }
}

function secureFrame(step, data, route = '') {
  return JSON.stringify({ type: 'secure', secure: { step, ...(route ? { route } : {}), data: encode(data) } });
}

export function connectSecure(socket, client, secret, { route = '', onReady, onMessage, onFailure }) {
  let incoming = Promise.resolve();
  let outgoing = Promise.resolve();
  let handshake, session;
  let authenticated = false;
  let failed = false;
  function fail(error) {
    if (failed) return;
    failed = true;
    onFailure(error);
  }
  function send(wire) {
    if (failed || socket.readyState !== WebSocket.OPEN) throw new Error('连接已断开');
    socket.send(wire);
  }
  return {
    consume(envelope) {
      incoming = incoming.then(async () => {
        if (failed) return;
        if (envelope.type === 'welcome' || envelope.type === 'agent_status') {
          if (envelope.status === 'connected' && !handshake) {
            handshake = await initiate(client, route, secret);
            send(handshake.frame);
          } else if (envelope.status !== 'connected' && handshake) {
            throw new Error('电脑 Agent 已离线');
          }
          return;
        }
        if (envelope.type !== 'secure' || !handshake) return;
        const frame = envelope.secure;
        if (frame.step === 'reply' && !session) {
          session = await handshake.accept(frame);
          send(secureFrame('confirm', await session.channel.seal(ready)));
        } else if (frame.step === 'confirm' && session && !authenticated) {
          const plain = decoder.decode(await session.channel.open(decode(frame.data)));
          if (plain !== 'ready') throw new Error('电脑 Agent 的身份验证失败');
          authenticated = true;
          onReady(session.key);
        } else if (frame.step === 'data' && authenticated) {
          const message = JSON.parse(decoder.decode(await session.channel.open(decode(frame.data))));
          if (message.type !== 'server_message') throw new Error('Invalid secure RPC');
          onMessage(message);
        }
      }).catch(fail);
    },
    send(envelope) {
      if (!authenticated || failed) return false;
      outgoing = outgoing.then(async () => {
        if (!failed) send(secureFrame('data', await session.channel.seal(encoder.encode(JSON.stringify(envelope)))));
      }).catch(fail);
      return true;
    },
  };
}

export function verifyPairing(url, result, invitation) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, ['codexrm', `codexrm.token.${result.token}`]);
    const timer = setTimeout(() => finish(new Error('无法验证电脑 Agent，请确认它在线并重新生成配对码。')), 15000);
    const finish = (error, key) => {
      clearTimeout(timer);
      socket.onmessage = socket.onclose = socket.onerror = null;
      socket.close();
      if (error) reject(error);
      else resolve(key);
    };
    const secure = connectSecure(socket, result.clientId, invitation.secret, {
      route: invitation.route,
      onReady: key => finish(null, key),
      onMessage: () => {},
      onFailure: () => finish(new Error('电脑 Agent 的身份验证失败，请重新生成配对码。')),
    });
    socket.onmessage = event => {
      try { secure.consume(JSON.parse(event.data)); } catch (error) { finish(error); }
    };
    socket.onclose = socket.onerror = () => finish(new Error('配对连接已断开，请重新生成配对码。'));
  });
}