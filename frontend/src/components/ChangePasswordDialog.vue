<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { changePassword } from '@/api/auth'
import { errorCode } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const visible = defineModel<boolean>({ required: true })

const auth = useAuthStore()
const router = useRouter()

const currentPassword = ref('')
const newPassword = ref('')
const errorMessage = ref('')
const submitting = ref(false)

function reset(): void {
  currentPassword.value = ''
  newPassword.value = ''
  errorMessage.value = ''
}

function close(): void {
  visible.value = false
  reset()
}

function messageFor(code: string): string {
  if (code === 'AUTH_INVALID_CREDENTIALS') {
    return '当前密码不正确'
  }
  if (code === 'VALIDATION_FAILED') {
    return '新密码需要 8 到 64 位'
  }
  if (code === 'USER_CONFLICT') {
    return '账号信息已变化，请重新登录后再试'
  }
  return '修改失败，请稍后重试'
}

async function submit(): Promise<void> {
  errorMessage.value = ''
  submitting.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    close()
    ElMessage.success('密码已修改，请用新密码重新登录')
    // 改密会撤销该用户全部会话（含当前这一个），本地身份必须清空并回到登录页
    auth.clearSession()
    await router.replace({ name: 'login' })
  } catch (error) {
    errorMessage.value = messageFor(errorCode(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="修改密码"
    width="26rem"
    @closed="reset"
  >
    <div class="password-form">
      <label class="auth-field">
        <span>当前密码</span>
        <el-input
          v-model="currentPassword"
          autocomplete="current-password"
          show-password
          type="password"
        />
      </label>
      <label class="auth-field">
        <span>新密码</span>
        <el-input
          v-model="newPassword"
          autocomplete="new-password"
          show-password
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
      <p class="password-form__hint">
        修改成功后所有设备的登录状态都会失效，需要用新密码重新登录。
      </p>
    </div>

    <template #footer>
      <el-button @click="close">
        取消
      </el-button>
      <el-button
        :loading="submitting"
        type="primary"
        @click="submit"
      >
        确定
      </el-button>
    </template>
  </el-dialog>
</template>
