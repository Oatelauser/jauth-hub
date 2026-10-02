import { createRouter, createWebHistory } from 'vue-router';
import ShellLayout from './components/ShellLayout.vue';
import Consent from './pages/Consent.vue';
import DeviceVerify from './pages/DeviceVerify.vue';
import Login from './pages/Login.vue';
import Sudo from './pages/Sudo.vue';
import Apps from './pages/Apps.vue';
import Pat from './pages/Pat.vue';
import MyApps from './pages/MyApps.vue';
import MyOrgs from './pages/MyOrgs.vue';
import OrgApps from './pages/OrgApps.vue';
import OrgInstallations from './pages/OrgInstallations.vue';
import OrgMembers from './pages/OrgMembers.vue';
import Passkey from './pages/Passkey.vue';

// base /front/（B4 装配的服务路径）；consent/sudo 页参数从 location.search 自取，不入路由状态
// （org 子页的 orgId 同理，从 pathname 自取——照 consent 页先例）。
// 信任页（login/consent/device-verify/sudo）不进导航壳（v1.5 宪法：壳只挂自助面）；
// my-app-new 不设独立路由，注册并入 my-apps 页内对话框（B0 普查决议）。
export const router = createRouter({
  history: createWebHistory('/front/'),
  routes: [
    { path: '/login', component: Login },
    { path: '/consent', component: Consent },
    { path: '/device-verify', component: DeviceVerify },
    { path: '/sudo', component: Sudo },
    {
      path: '/',
      component: ShellLayout,
      children: [
        { path: '', redirect: '/selfservice/apps' },
        { path: 'selfservice/apps', component: Apps },
        { path: 'selfservice/pat', component: Pat },
        { path: 'selfservice/my-apps', component: MyApps },
        { path: 'selfservice/my-orgs', component: MyOrgs },
        { path: 'selfservice/orgs/:orgId/apps', component: OrgApps },
        { path: 'selfservice/orgs/:orgId/installations', component: OrgInstallations },
        { path: 'selfservice/orgs/:orgId/members', component: OrgMembers },
        { path: 'selfservice/passkey', component: Passkey },
      ],
    },
  ],
});
