/**
 * 时间展示工具。
 *
 * <p>后端时间统一是 UTC（ISO 8601），这里只负责转成本地时区的可读文本；
 * 业务判断一律用后端返回的字段，不做前端时间运算。</p>
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(date)
}
