<!-- 成员管理（B2b，OWNER 面）：成员列表 + 按用户名添加 + 移除/角色切换（sudo 类，A0515 由请求
     包装自动跳验证页）。两动作均销毁性，必经确认对话框。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { getJson, postJson } from '../api';
import { deleteJson } from '../rest';
import { fmtDateTime } from '../format';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const orgId = (window.location.pathname.split('/')[4] || '');

const state = ref(null);
const error = ref('');
const busy = ref(false);
const confirming = ref(null); // { kind: 'remove'|'role', member }
const form = reactive({ username: '' });

document.title = t('orgmembers.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/selfservice/orgs/' + encodeURIComponent(orgId) + '/members');
  } catch (e) {
    error.value = e.message; // A0508 非 OWNER：页面错误态，不白屏
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

const base = '/selfservice/orgs/' + encodeURIComponent(orgId) + '/members';

async function add() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(base, { username: form.username.trim() }, csrf.value);
    form.username = '';
    await load();
  } catch (e) {
    error.value = e.message;
  }
  busy.value = false;
}

async function runConfirmed() {
  const action = confirming.value;
  if (!action || busy.value) return;
  busy.value = true;
  error.value = '';
  const userId = encodeURIComponent(action.member.userId);
  try {
    if (action.kind === 'remove') {
      await deleteJson(base + '/' + userId, csrf.value);
    } else {
      await postJson(base + '/' + userId + '/role', undefined, csrf.value);
    }
    confirming.value = null;
    await load();
  } catch (e) {
    error.value = e.message;
    confirming.value = null;
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>
      {{ t('orgmembers.title') }}
      <span class="pill pill-accent" v-if="state && state.org">{{ state.org.orgName }}</span>
    </h1>
    <p class="hint"><RouterLink to="/selfservice/my-orgs">{{ t('common.back-my-orgs') }}</RouterLink></p>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <div class="alert warn" v-if="!state.membersSupported">{{ t('orgmembers.unsupported') }}</div>

      <template v-else>
        <table class="data-table" v-if="state.members.length">
          <thead>
            <tr>
              <th>{{ t('orgmembers.col-username') }}</th>
              <th>{{ t('orgmembers.col-role') }}</th>
              <th>{{ t('orgmembers.col-joined') }}</th>
              <th>{{ t('orgmembers.col-actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="member in state.members" :key="member.userId">
              <td>{{ member.username }}</td>
              <td>
                <span class="pill" :class="member.role === 'OWNER' ? 'pill-success' : 'pill-muted'">{{ member.role }}</span>
              </td>
              <td>{{ fmtDateTime(member.createdAt) }}</td>
              <td>
                <div class="table-actions">
                  <button class="btn secondary" type="button" @click="confirming = { kind: 'role', member }">
                    {{ t('orgmembers.toggle-role') }}
                  </button>
                  <button class="btn secondary" type="button" @click="confirming = { kind: 'remove', member }">
                    {{ t('orgmembers.remove') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <h2 class="section-gap">{{ t('orgmembers.add-title') }}</h2>
        <form class="form" @submit.prevent="add">
          <label class="field">
            <span>{{ t('orgmembers.field-username') }}</span>
            <input type="text" v-model="form.username" maxlength="50" required />
            <span class="hint">{{ t('orgmembers.add-hint') }}</span>
          </label>
          <button class="btn primary" type="submit" :disabled="busy">{{ t('orgmembers.add') }}</button>
        </form>
      </template>
    </template>
    <ConfirmDialog
      v-if="confirming"
      :title="confirming.kind === 'remove' ? t('orgmembers.remove') : t('orgmembers.toggle-role')"
      :message="confirming.kind === 'remove' ? t('orgmembers.remove-confirm') : t('orgmembers.toggle-role-confirm')"
      :confirm-label="confirming.kind === 'remove' ? t('orgmembers.remove') : t('orgmembers.toggle-role')"
      :busy="busy"
      @confirm="runConfirmed"
      @cancel="confirming = null"
    />
  </main>
</template>
