import { errorCode } from './http'

/**
 * 稳定错误编码到界面文案的映射。
 *
 * <p>界面按 `code` 分支，不依赖后端 message 文案；未收录的编码统一回落到调用方给的兜底文案。
 * 编码清单见 `docs/api-design.md` 第 10.2 节。</p>
 */
const messages: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '用户名或密码不正确',
  AUTH_REQUIRED: '登录状态已失效，请重新登录',
  AUTH_SESSION_INVALID: '登录会话已失效，请重新登录',
  ORIGIN_NOT_ALLOWED: '请求来源不被允许',
  ACCESS_DENIED: '当前账号没有执行该操作的权限',
  VALIDATION_FAILED: '请求内容不符合要求',
  TICKET_NOT_FOUND: '工单不存在或你没有查看权限',
  TICKET_ACTION_FORBIDDEN: '当前状态不允许该操作',
  TICKET_CONFLICT: '工单已被其他人更新，请刷新后重试',
  USER_NOT_FOUND: '用户不存在',
  USERNAME_CONFLICT: '登录名已存在',
  USER_CONFLICT: '账号信息已变化，请刷新后重试',
  USER_ROLE_REQUIRED: '至少需要保留一个角色',
  LAST_ADMIN_PROTECTED: '不能移除最后一个启用的管理员',
  ADMIN_HANDOFF_REQUIRED: '缺少完整的活动工单交接方案',
  ADMIN_HANDOFF_CONFLICT: '交接期间数据发生变化，请刷新后重试',
  CATEGORY_NOT_FOUND: '分类不存在',
  CATEGORY_NAME_CONFLICT: '分类名称已存在',
  CATEGORY_IN_USE: '分类仍被工单引用，无法删除',
  CATEGORY_CONFLICT: '分类已被其他人更新，请刷新后重试',
  ATTACHMENT_NOT_FOUND: '附件不存在或不可见',
  ATTACHMENT_TOO_LARGE: '上传内容超过大小限制',
  ATTACHMENT_TYPE_UNSUPPORTED: '不支持的文件类型',
  RESOURCE_NOT_FOUND: '请求的资源不存在',
  INTERNAL_ERROR: '系统暂时无法处理该请求，请稍后重试',
}

/** 把错误对象或错误编码翻译成界面文案。 */
export function describeError(error: unknown, fallback = '操作失败，请稍后重试'): string {
  const code = typeof error === 'string' ? error : errorCode(error)
  return messages[code] ?? fallback
}
