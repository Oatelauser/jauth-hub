<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson, postJson } from '../api';
import { t } from '../i18n';
import { assertionBody, toArrayBuffer, webauthnRawPost } from '../webauthn';

const state = ref(null);
const error = ref('');
const submitting = ref(false);
const passkeyError = ref(false);
const passkeyMessage = ref('');
const form = reactive({ username: '', password: '' });
const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

document.title = t('login.title') + ' · jauth-hub';

onMounted(async () => {
  // 自身 query 原样转发：?error 反射与 SSR ${param.error} 同语义
  try {
    state.value = await getJson('/api/login' + window.location.search);
    if (state.value.error) error.value = t('login.error');
  } catch (e) {
    error.value = e.message;
  }
});

// 登录落点（B2b）：redirectUrl === '/' 是框架默认落点——改落 SPA 看板；
// saved request（/oauth2/authorize...）原样导航（协议流程必须服务端走）
function landing(redirectUrl) {
  return !redirectUrl || redirectUrl === '/' ? '/front/selfservice/apps' : redirectUrl;
}

async function submit() {
  if (submitting.value) return;
  submitting.value = true;
  error.value = '';
  try {
    const data = await postJson('/api/login', { username: form.username, password: form.password }, csrf.value);
    // 登录成功即 CSRF token 换发（B2 现场事实）：此页不再发任何请求，直接整页导航离开
    window.location.href = landing(data.redirectUrl);
  } catch (e) {
    error.value = e.message; // A0520/A0521 的 message 即 SSR ?error 同款文案
    submitting.value = false;
  }
}

// passkey 入口：流程逐行照 SSR login.html 内联 JS（options→credentials.get→/login/webauthn）
async function passkeyLogin() {
  passkeyError.value = false;
  passkeyMessage.value = '';
  if (!window.PublicKeyCredential) {
    passkeyMessage.value = t('login.passkey.unsupported');
    passkeyError.value = true;
    return;
  }
  try {
    // 每次尝试都取新 options：challenge 存 session，断言按 session 配对
    const options = await webauthnRawPost('/webauthn/authenticate/options', null, csrf.value);
    const credential = await navigator.credentials.get({
      publicKey: {
        challenge: toArrayBuffer(options.challenge),
        rpId: options.rpId,
        timeout: options.timeout,
        userVerification: options.userVerification,
        allowCredentials: (options.allowCredentials || []).map((d) => ({ type: d.type, id: toArrayBuffer(d.id) })),
      },
    });
    const body = await webauthnRawPost('/login/webauthn', assertionBody(credential), csrf.value);
    window.location.href = landing(body.redirectUrl);
  } catch {
    passkeyError.value = true;
  }
}
</script>

<template>
  <div class="page">
    <main class="card">
      <p class="brand">{{ t('brand') }}</p>
      <h1>{{ t('login.title') }}</h1>
      <p class="alert error" v-if="error" role="alert">{{ error }}</p>
      <form class="form" @submit.prevent="submit">
        <label class="field">
          <span>{{ t('login.username') }}</span>
          <input type="text" v-model="form.username" autocomplete="username" required />
        </label>
        <label class="field">
          <span>{{ t('login.password') }}</span>
          <input type="password" v-model="form.password" autocomplete="current-password" required />
        </label>
        <button class="btn primary btn-block" type="submit" :disabled="submitting">{{ t('login.submit') }}</button>
      </form>
      <template v-if="state && state.passkeyEnabled">
        <hr class="pk-divider" />
        <button class="btn secondary btn-block" type="button" @click="passkeyLogin">{{ t('login.passkey.button') }}</button>
        <p class="alert error" v-if="passkeyError" role="alert">
          {{ passkeyMessage || t('login.passkey.error') }}
        </p>
      </template>
    </main>
  </div>
</template>
