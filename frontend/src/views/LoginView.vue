<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { errorCode } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)

async function submit(): Promise<void> {
  errorMessage.value = ''
  submitting.value = true
  try {
    await auth.signIn(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    // 后端不区分账号不存在、密码错误和账号停用，界面也不猜测具体原因
    errorMessage.value =
      errorCode(error) === 'AUTH_INVALID_CREDENTIALS' ? '用户名或密码不正确' : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <p class="auth-card__eyebrow">
        FLOWDESK
      </p>
      <h1>登录</h1>
      <p class="auth-card__hint">
        使用企业账号登录工单协作平台。
      </p>

      <form
        class="auth-card__form"
        @submit.prevent="submit"
      >
        <label class="auth-field">
          <span>用户名</span>
          <el-input
            v-model="username"
            autocomplete="username"
            placeholder="登录名"
            size="large"
          />
        </label>
        <label class="auth-field">
          <span>密码</span>
          <el-input
            v-model="password"
            autocomplete="current-password"
            placeholder="密码"
            show-password
            size="large"
            type="password"
          />
        </label>

        <p
          v-if="errorMessage"
          class="auth-card__error"
          role="alert"
        >
          {{ errorMessage }}
        </p>

        <el-button
          :loading="submitting"
          native-type="submit"
          size="large"
          type="primary"
        >
          登录
        </el-button>
      </form>
    </section>
  </main>
</template>
