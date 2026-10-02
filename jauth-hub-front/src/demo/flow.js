// /demo 教学区 PKCE 与 state 件 + 令牌 sessionStorage 流转（B4）。语义逐行照 SSR 助手
// static/demo/demo.js（jauthDemo）——那是 SSR demo 的唯一对齐点，SSR 改动时此处同步。
// Web Crypto：getRandomValues 出 verifier（48B）/state（16B），SHA-256 派生 S256 challenge。

const VERIFIER_KEY = 'jauth_demo_verifier';
const STATE_KEY = 'jauth_demo_state';
const TOKEN_KEY = 'jauth_demo_tokens';

function b64url(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomB64Url(byteLength) {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return b64url(bytes);
}

export function sha256B64Url(input) {
  return crypto.subtle
    .digest('SHA-256', new TextEncoder().encode(input))
    .then((digest) => b64url(new Uint8Array(digest)));
}

export function saveVerifierAndState(verifier, state) {
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
}

// 取出即清除（一次性）：回调页换完令牌后 verifier 作废，state 亦不复用
export function takeVerifierAndState() {
  const pair = {
    verifier: sessionStorage.getItem(VERIFIER_KEY),
    state: sessionStorage.getItem(STATE_KEY),
  };
  sessionStorage.removeItem(VERIFIER_KEY);
  sessionStorage.removeItem(STATE_KEY);
  return pair;
}

export function saveTokens(tokens) {
  tokens.obtainedAt = Date.now();
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(tokens));
}

export function readTokens() {
  const raw = sessionStorage.getItem(TOKEN_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function clearTokens() {
  sessionStorage.removeItem(TOKEN_KEY);
}
