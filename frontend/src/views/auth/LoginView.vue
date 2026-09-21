<script setup lang="ts">
import type { FormInstance, FormRules, InputInstance } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { errorCode } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface LoginForm {
  username: string
  password: string
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const usernameInputRef = ref<InputInstance>()
const passwordInputRef = ref<InputInstance>()
const form = reactive<LoginForm>({
  username: '',
  password: '',
})
const errorMessage = ref('')
const submitting = ref(false)

const rules: FormRules<LoginForm> = {
  username: [
    { required: true, whitespace: true, message: '请输入登录名', trigger: 'blur' },
    { max: 64, message: '登录名不能超过 64 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 64, message: '密码不能超过 64 个字符', trigger: 'blur' },
  ],
}

function clearServerError(): void {
  errorMessage.value = ''
}

function focusFirstInvalidField(): void {
  if (!form.username.trim()) {
    usernameInputRef.value?.focus()
    return
  }
  passwordInputRef.value?.focus()
}

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) {
    return
  }

  errorMessage.value = ''
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    focusFirstInvalidField()
    return
  }

  submitting.value = true
  try {
    await auth.signIn(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    // 后端不区分账号不存在、密码错误和账号停用，界面也不猜测具体原因
    errorMessage.value = describeError(error, '登录失败，请稍后重试')
    if (errorCode(error) === 'AUTH_INVALID_CREDENTIALS') {
      passwordInputRef.value?.focus()
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section
      class="login-shell"
      aria-labelledby="login-title"
    >
      <aside class="login-intro">
        <a
          class="login-brand"
          href="/login"
          aria-label="FlowDesk 登录页"
        >
          <span
            class="login-brand__mark"
            aria-hidden="true"
          >F</span>
          <span>FlowDesk</span>
        </a>

        <div class="login-intro__content">
          <p class="login-intro__label">
            企业工单协作平台
          </p>
          <h2>让每一次请求，都有清晰的下一步。</h2>
          <p class="login-intro__description">
            从提交问题到协作解决，团队在同一条工作流中保持信息同步。
          </p>

          <ol
            class="login-flow"
            aria-label="工单协作流程"
          >
            <li>
              <span>01</span>
              <strong>提交请求</strong>
              <small>记录背景与优先级</small>
            </li>
            <li>
              <span>02</span>
              <strong>协同处理</strong>
              <small>让进度与责任人可见</small>
            </li>
            <li>
              <span>03</span>
              <strong>确认解决</strong>
              <small>沉淀完整处理记录</small>
            </li>
          </ol>
        </div>

        <p class="login-intro__trust">
          仅供 FlowDesk 企业成员使用
        </p>
      </aside>

      <div class="login-panel">
        <div class="login-panel__content">
          <p class="login-panel__kicker">
            继续今天的协作
          </p>
          <h1 id="login-title">
            登录
          </h1>
          <p class="login-panel__hint">
            使用企业账号进入工作台。
          </p>

          <el-form
            ref="formRef"
            class="login-form"
            :model="form"
            :rules="rules"
            label-position="top"
            scroll-to-error
            status-icon
            @submit.prevent="submit"
          >
            <el-form-item
              label="登录名"
              prop="username"
            >
              <el-input
                ref="usernameInputRef"
                v-model="form.username"
                :disabled="submitting"
                :maxlength="64"
                autocomplete="username"
                autofocus
                name="username"
                placeholder="请输入登录名"
                size="large"
                @input="clearServerError"
              />
            </el-form-item>

            <el-form-item
              label="密码"
              prop="password"
            >
              <el-input
                ref="passwordInputRef"
                v-model="form.password"
                :disabled="submitting"
                :maxlength="64"
                autocomplete="current-password"
                name="password"
                placeholder="请输入密码"
                show-password
                size="large"
                type="password"
                @input="clearServerError"
              />
            </el-form-item>

            <el-alert
              v-if="errorMessage"
              class="login-form__error"
              :closable="false"
              show-icon
              :title="errorMessage"
              type="error"
            />

            <el-button
              class="login-form__submit"
              :disabled="submitting"
              :loading="submitting"
              native-type="submit"
              size="large"
              type="primary"
            >
              登录
            </el-button>
          </el-form>

          <p class="login-panel__support">
            无法登录？请联系企业管理员确认账号状态。
          </p>
        </div>
      </div>
    </section>
  </main>
</template>
