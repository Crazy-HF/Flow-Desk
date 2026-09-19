import { createPinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { configureAuthSession } from './api/http'
import router from './router'
import { useAuthStore } from './stores/auth'
import './styles/main.css'

const app = createApp(App)
app.use(createPinia())

// 网络层与身份状态在此装配：请求注入令牌、刷新回写令牌、会话失效清空并回登录页
const auth = useAuthStore()
configureAuthSession({
  getAccessToken: () => auth.accessToken,
  setAccessToken: (token) => auth.setAccessToken(token),
  onSessionLost: () => {
    auth.clearSession()
    void router.push('/login')
  },
})

app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
