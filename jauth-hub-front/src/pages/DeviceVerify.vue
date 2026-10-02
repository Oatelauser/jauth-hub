<script setup>
import { onMounted, ref } from 'vue';
import { getJson } from '../api';
import { t } from '../i18n';

const state = ref(null);
const error = ref('');

document.title = t('device.title') + ' · jauth-hub';

onMounted(async () => {
  try {
    state.value = await getJson('/api/device/verify');
  } catch (e) {
    error.value = e.message;
  }
});
</script>

<template>
  <div class="page">
    <main class="card">
      <p class="brand">{{ t('brand') }}</p>
      <h1>{{ t('device.title') }}</h1>
      <p class="alert error" v-if="error" role="alert">{{ error }}</p>
      <template v-if="state">
        <p class="hint">{{ t('device.hint') }}</p>
        <!-- 原生表单 POST 浏览器导航，字段照 SSR device-verify.html -->
        <form class="form" action="/device/verify" method="post">
          <input type="hidden" name="_csrf" v-if="state.csrfToken" :value="state.csrfToken" />
          <label class="field">
            <span>{{ t('device.codeLabel') }}</span>
            <input type="text" name="user_code" class="code-input" autocomplete="one-time-code" required />
          </label>
          <button class="btn primary btn-block" type="submit">{{ t('device.submit') }}</button>
        </form>
      </template>
    </main>
  </div>
</template>
