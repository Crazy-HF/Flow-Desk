import { createPinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 用 SCSS 入口而不是 dist/index.css：组件样式由 SCSS 编译，才能吃到
// styles/element/var-override.scss 里的品牌色配置（light-x 色阶随之正确生成）
import 'element-plus/theme-chalk/src/index.scss'

import App from './App.vue'
import { configureAuthSession } from './api/http'
import router from './router'
import { useAuthStore } from './stores/auth'
// token 必须在 Element Plus 样式之后引入：同优先级下后加载的 --el-* 覆盖才会生效
import './styles/tokens.css'
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
