import { afterEach, describe, expect, it } from 'vitest';
import {
  clearTokens,
  randomB64Url,
  readTokens,
  saveTokens,
  saveVerifierAndState,
  sha256B64Url,
  takeVerifierAndState,
} from './flow';

afterEach(() => {
  sessionStorage.clear();
});

describe('demo PKCE 与 state 件（语义对齐 SSR demo.js）', () => {
  it('challenge 派生向量：RFC 7636 附录 B 的 S256 参考向量', async () => {
    const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
    await expect(sha256B64Url(verifier)).resolves.toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('randomB64Url：长度随字节参数、b64url 字符集且无 padding', () => {
    const value = randomB64Url(48);
    expect(value).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(value).not.toContain('=');
    // 48 字节 → 64 位无 padding 字符
    expect(value).toHaveLength(64);
    expect(randomB64Url(16)).not.toBe(randomB64Url(16)); // 随机性冒烟
  });

  it('verifier/state 存取往返：take 取出即清除（一次性）', () => {
    saveVerifierAndState('verifier-1', 'state-1');
    expect(takeVerifierAndState()).toEqual({ verifier: 'verifier-1', state: 'state-1' });
    expect(takeVerifierAndState()).toEqual({ verifier: null, state: null });
  });

  it('令牌存取：save 打 obtainedAt 时间戳、read 往返、clear 清空', () => {
    expect(readTokens()).toBeNull();
    saveTokens({ access_token: 'at-1', expires_in: 300 });
    const tokens = readTokens();
    expect(tokens.access_token).toBe('at-1');
    expect(tokens.obtainedAt).toBeLessThanOrEqual(Date.now());
    clearTokens();
    expect(readTokens()).toBeNull();
  });

  it('readTokens 对损坏载荷防御性返回 null', () => {
    sessionStorage.setItem('jauth_demo_tokens', '{not-json');
    expect(readTokens()).toBeNull();
  });
});
