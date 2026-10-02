import { describe, expect, it } from 'vitest';
import { registrationBody, toArrayBuffer } from './webauthn';

// 注册体线格式冒烟：base64url（无 padding）+ 体内 label 位置（逐行照 SSR passkey.html）
describe('webauthn 注册体', () => {
  it('rawId/attestationObject 走 base64url（+/= 出场即替换）', () => {
    const bytes = (arr) => new Uint8Array(arr).buffer;
    const credential = {
      id: 'cred-1',
      rawId: bytes([0xfb]), // base64 '+w' → url-safe '-w'
      type: 'public-key',
      response: { attestationObject: bytes([0xff, 0xff]), clientDataJSON: bytes([0x01]) },
      authenticatorAttachment: null,
    };
    const body = registrationBody(credential, '我的钥匙');
    expect(body.publicKey.credential.rawId).toBe('-w');
    expect(body.publicKey.credential.response.attestationObject).toBe('__8'); // '//8' → url-safe
    expect(body.publicKey.label).toBe('我的钥匙');
    // getTransports 缺席时兜底空数组（老浏览器）
    expect(body.publicKey.credential.response.transports).toEqual([]);
  });

  it('toArrayBuffer 往返 base64url', () => {
    const buffer = toArrayBuffer('Y2hhbGxlbmdl'); // 'challenge'
    expect(new Uint8Array(buffer)).toEqual(new Uint8Array([99, 104, 97, 108, 108, 101, 110, 103, 101]));
  });
});
