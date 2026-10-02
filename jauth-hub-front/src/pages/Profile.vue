<!-- 个人资料（B3，app 面）：改显示名 + 自助改密。改密是 sudo 面——A0515 由请求包装自动整页跳
     验证页；密码策略 ≥8 位前端只做提示（hint）不强校验，服务端为准（避免双源策略漂移）。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson, postJson } from '../api';
import { t } from '../i18n';

const state = ref(null);
const error = ref('');
const notice = ref(''); // 成功反馈（success 警示条），任一动作发起时清空
const busy = ref(false);
const form = reactive({ displayName: '', oldPassword: '', newPassword: '' });

document.title = t('profile.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/profile');
    form.displayName = state.value.displayName || ''; // displayName 可空：空串等价清空，UI 回退用户名
  } catch (e) {
    error.value = e.message;
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

async function saveDisplayName() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await postJson('/api/profile', { displayName: form.displayName.trim() }, csrf.value);
    notice.value = t('profile.saved');
    await load(); // 回读服务端规整值（空串归 null）
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}

async function changePassword() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await postJson(
      '/api/profile/password',
      { oldPassword: form.oldPassword, newPassword: form.newPassword },
      csrf.value
    );
    // 改密成功建议重新登录（照 SSR 文案键 + 派单要求的重登语义）
    notice.value = t('profile.password-changed') + ' · ' + t('profile.relogin-hint');
    form.oldPassword = '';
    form.newPassword = '';
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>{{ t('profile.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <p class="alert success" v-if="notice" role="alert">{{ notice }}</p>
    <template v-if="state">
      <p class="hint">{{ t('profile.account-label') }} {{ state.username }}</p>

      <h2>{{ t('profile.display-title') }}</h2>
      <form class="form" @submit.prevent="saveDisplayName">
        <label class="field">
          <span>{{ t('profile.field-display-name') }}</span>
          <input type="text" v-model="form.displayName" maxlength="100" :placeholder="t('profile.display-placeholder')" />
        </label>
        <button class="btn primary" type="submit" :disabled="busy">{{ t('profile.save-display-name') }}</button>
      </form>

      <h2 class="section-gap">{{ t('profile.password-title') }}</h2>
      <form class="form" @submit.prevent="changePassword">
        <label class="field">
          <span>{{ t('profile.field-old-password') }}</span>
          <input type="password" v-model="form.oldPassword" required autocomplete="current-password" />
        </label>
        <label class="field">
          <span>{{ t('profile.field-new-password') }}</span>
          <input type="password" v-model="form.newPassword" required autocomplete="new-password" />
          <span class="hint">{{ t('adminusers.password-hint') }}</span>
        </label>
        <button class="btn primary" type="submit" :disabled="busy">{{ t('profile.change-password') }}</button>
      </form>
    </template>
  </main>
</template>
