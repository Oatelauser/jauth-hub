// WebAuthn 流程件（Login/Sudo 两页共享，v1.4 B5 评审提取）：流程与线格式逐行照 SSR login.html /
// sudo.html 的内联 JS（options → credentials.get → /login/webauthn）——SSR 模板改动时此处是唯一对齐点。

// /webauthn/* 与 /login/webauthn 是框架原生端点，裸 JSON（非 ResponseRenderer），不走 api.js 包装
export async function webauthnRawPost(path, body, csrf) {
  const headers = { 'Content-Type': 'application/json' };
  if (csrf && csrf.csrfToken && csrf.csrfHeaderName) headers[csrf.csrfHeaderName] = csrf.csrfToken;
  const response = await fetch(path, {
    method: 'POST',
    headers,
    credentials: 'same-origin',
    body: JSON.stringify(body || {}),
  });
  if (!response.ok) throw new Error(path + ' ' + response.status);
  return response.json();
}

export function assertionBody(credential) {
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: toBase64Url(credential.response.clientDataJSON),
      authenticatorData: toBase64Url(credential.response.authenticatorData),
      signature: toBase64Url(credential.response.signature),
      userHandle: credential.response.userHandle ? toBase64Url(credential.response.userHandle) : null,
    },
    clientExtensionResults: {},
    authenticatorAttachment: credential.authenticatorAttachment,
  };
}

// base64url（无 padding，对齐 SS7 Bytes 序列化）↔ ArrayBuffer：WebAuthn 二进制字段的 JSON 线格式
export function toArrayBuffer(value) {
  const binary = window.atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

export function toBase64Url(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
