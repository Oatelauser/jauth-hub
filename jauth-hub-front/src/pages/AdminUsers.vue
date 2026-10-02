<!-- 用户管理（B3，app 面，SUPER_ADMIN 专属）：建号 + 用户表（分页）+ 改角色/停用启用（确认对话
     框，sudo 面 A0515 由请求包装自动跳验证）+ 重置密码（对话框内给定新值，成功后 shown-once 回显
     ——服务端只存哈希不回显明文，回显的是管理员刚输入的值，供转交用户）。非超管访问状态面被
     @RequiresRole 拦（403）→ 页面错误态兜底不白屏（照 A0508 错误态先例）。行数据无创建时间列：
     B1a 状态面行即建号摘要同形（id/username/displayName/role/status），后端零改动不扩列。 -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { getJson, postJson } from '../api';
import { t } from '../i18n';
import ConfirmDialog from '../components/ConfirmDialog.vue';

const PAGE_SIZE = 20; // 服务端默认页大小（SSR 同款固定值，不设页大小选择器）；clamp 服务端已做

const state = ref(null);
const error = ref('');
const busy = ref(false);
const page = ref(1);
const confirming = ref(null); // { kind: 'role'|'status', user } 待确认的危险动作
const resetting = ref(null); // 待重置密码的用户行
const resetForm = reactive({ password: '' });
const resetBanner = ref(null); // shown-once：{ username, password }，仅本页生命周期内可见
const createForm = reactive({ username: '', password: '', displayName: '' });

document.title = t('adminusers.title') + ' · jauth-hub';

onMounted(load);

async function load() {
  try {
    state.value = await getJson('/api/admin/users?page=' + page.value + '&size=' + PAGE_SIZE);
    page.value = state.value.pageNum; // 回读服务端 clamp 后的页码，防出界请求号累积漂移
  } catch (e) {
    error.value = e.message; // 非超管 403 / 会话失效残余：错误态，不白屏
  }
}

const csrf = computed(() => ({
  csrfToken: state.value ? state.value.csrfToken : null,
  csrfHeaderName: state.value ? state.value.csrfHeaderName : null,
}));

const summaryText = computed(() => {
  if (!state.value) return '';
  return t('adminusers.page-summary')
    .replace('{0}', state.value.total)
    .replace('{1}', state.value.pageNum)
    .replace('{2}', state.value.totalPage);
});

async function goPage(next) {
  if (busy.value) return;
  page.value = next;
  await load();
}

async function create() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(
      '/api/admin/users',
      {
        username: createForm.username.trim(),
        password: createForm.password,
        displayName: createForm.displayName.trim(),
      },
      csrf.value
    );
    createForm.username = '';
    createForm.password = '';
    createForm.displayName = '';
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
  try {
    // 空对象体照 SSR 模板逐字（端点 consumes JSON，语义是翻转而非设定）
    await postJson(
      '/api/admin/users/' + encodeURIComponent(action.user.id) + (action.kind === 'role' ? '/role' : '/status'),
      {},
      csrf.value
    );
    confirming.value = null;
    await load();
  } catch (e) {
    error.value = e.message;
    confirming.value = null;
  }
  busy.value = false;
}

async function runReset() {
  const user = resetting.value;
  if (!user || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await postJson(
      '/api/admin/users/' + encodeURIComponent(user.id) + '/password',
      { password: resetForm.password },
      csrf.value
    );
    resetBanner.value = { username: user.username, password: resetForm.password };
    resetting.value = null;
    resetForm.password = '';
    await load();
  } catch (e) {
    error.value = e.message;
    resetting.value = null;
    resetForm.password = '';
  }
  busy.value = false;
}
</script>

<template>
  <main class="card card-fluid">
    <h1>{{ t('adminusers.title') }}</h1>
    <p class="alert error" v-if="error" role="alert">{{ error }}</p>
    <template v-if="state">
      <!-- shown-once：重置的新密码只显示这一次（值为管理员刚输入的明文），离开本页即失 -->
      <div class="alert warn" v-if="resetBanner" role="alert">
        {{ t('adminusers.reset-done') }} · {{ t('adminusers.reset-once') }}
        <code class="code-line">{{ resetBanner.username }} / {{ resetBanner.password }}</code>
      </div>

      <h2>{{ t('adminusers.create-title') }}</h2>
      <form class="form" @submit.prevent="create">
        <label class="field">
          <span>{{ t('adminusers.field-username') }}</span>
          <input type="text" v-model="createForm.username" maxlength="50" required />
        </label>
        <label class="field">
          <span>{{ t('adminusers.field-password') }}</span>
          <input type="password" v-model="createForm.password" required autocomplete="new-password" />
          <span class="hint">{{ t('adminusers.password-hint') }}</span>
        </label>
        <label class="field">
          <span>{{ t('adminusers.field-display-name') }}</span>
          <input type="text" v-model="createForm.displayName" maxlength="100" />
        </label>
        <button class="btn primary" type="submit" :disabled="busy">{{ t('adminusers.create-submit') }}</button>
      </form>

      <h2 class="section-gap">{{ t('adminusers.list-title') }}</h2>
      <div class="empty" v-if="!state.item.length"><span class="empty-title">{{ t('adminusers.empty') }}</span></div>
      <table class="data-table" v-else>
        <thead>
          <tr>
            <th>{{ t('adminusers.col-username') }}</th>
            <th>{{ t('adminusers.col-display-name') }}</th>
            <th>{{ t('adminusers.col-role') }}</th>
            <th>{{ t('adminusers.col-status') }}</th>
            <th>{{ t('adminusers.col-actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <!--/* 自操作由服务端 A0513 拒，页面不预判操作者身份（渲染逻辑零特例，照 SSR） */-->
          <tr v-for="user in state.item" :key="user.id">
            <td>{{ user.username }}</td>
            <td>{{ user.displayName || '—' }}</td>
            <td>
              <span class="pill" :class="user.role === 'SUPERADMIN' ? 'pill-success' : 'pill-muted'">
                {{ user.role === 'SUPERADMIN' ? t('adminusers.role-superadmin') : t('adminusers.role-user') }}
              </span>
            </td>
            <td>
              <span class="pill" :class="user.status === 'DISABLED' ? 'pill-danger' : 'pill-muted'">
                {{ user.status === 'ACTIVE' ? t('adminusers.status-active') : t('adminusers.status-disabled') }}
              </span>
            </td>
            <td>
              <div class="table-actions">
                <button class="btn secondary" type="button" @click="confirming = { kind: 'role', user }">
                  {{ t('adminusers.action-toggle-role') }}
                </button>
                <button class="btn secondary" type="button" @click="confirming = { kind: 'status', user }">
                  {{ t('adminusers.action-toggle-status') }}
                </button>
                <button class="btn secondary" type="button" @click="resetting = user">
                  {{ t('adminusers.action-reset-password') }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="actions page-nav">
        <span class="hint">{{ summaryText }}</span>
        <button
          class="btn secondary"
          type="button"
          :disabled="state.pageNum <= 1"
          @click="goPage(state.pageNum - 1)"
        >
          {{ t('adminusers.page-prev') }}
        </button>
        <button
          class="btn secondary"
          type="button"
          :disabled="state.pageNum >= state.totalPage"
          @click="goPage(state.pageNum + 1)"
        >
          {{ t('adminusers.page-next') }}
        </button>
      </div>
    </template>

    <ConfirmDialog
      v-if="confirming"
      :title="confirming.kind === 'role' ? t('adminusers.action-toggle-role') : t('adminusers.action-toggle-status')"
      :message="confirming.kind === 'role' ? t('adminusers.role-confirm') : t('adminusers.status-confirm')"
      :confirm-label="confirming.kind === 'role' ? t('adminusers.action-toggle-role') : t('adminusers.action-toggle-status')"
      :busy="busy"
      @confirm="runConfirmed"
      @cancel="confirming = null"
    />

    <!-- 重置密码对话框：新值由管理员给定（服务端只存哈希不回显），确认即提交 -->
    <div class="dialog-backdrop" v-if="resetting" @click.self="resetting = null">
      <div class="dialog" role="dialog" aria-modal="true" :aria-label="t('adminusers.action-reset-password')">
        <h2>{{ t('adminusers.action-reset-password') }} · {{ resetting.username }}</h2>
        <form class="form" @submit.prevent="runReset">
          <label class="field">
            <span>{{ t('adminusers.reset-field') }}</span>
            <input type="password" v-model="resetForm.password" required autocomplete="new-password" />
            <span class="hint">{{ t('adminusers.password-hint') }}</span>
          </label>
          <div class="actions">
            <button class="btn secondary" type="button" @click="resetting = null">{{ t('common.cancel') }}</button>
            <button class="btn danger" type="submit" :disabled="busy">{{ t('adminusers.action-reset-password') }}</button>
          </div>
        </form>
      </div>
    </div>
  </main>
</template>
