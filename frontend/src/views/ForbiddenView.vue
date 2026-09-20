<template>
  <main class="auth-page">
    <section class="auth-card">
      <h1>没有访问权限</h1>
      <p class="auth-card__hint">
        当前账号缺少访问该页面所需的能力，请联系管理员。
      </p>
      <RouterLink
        class="auth-card__link"
        to="/"
      >
        返回首页
      </RouterLink>
    </section>
  </main>
</template>
