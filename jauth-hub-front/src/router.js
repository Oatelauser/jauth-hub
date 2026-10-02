import { createRouter, createWebHistory } from 'vue-router';
import Consent from './pages/Consent.vue';
import DeviceVerify from './pages/DeviceVerify.vue';
import Login from './pages/Login.vue';
import Sudo from './pages/Sudo.vue';

// base /front/（B4 装配的服务路径）；consent/sudo 页参数从 location.search 自取，不入路由状态
export const router = createRouter({
  history: createWebHistory('/front/'),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: Login },
    { path: '/consent', component: Consent },
    { path: '/device-verify', component: DeviceVerify },
    { path: '/sudo', component: Sudo },
  ],
});
