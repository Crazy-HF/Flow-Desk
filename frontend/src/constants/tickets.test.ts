import { describe, expect, it } from 'vitest'

import {
  TICKET_ACTIONS,
  TICKET_PRIORITY_OPTIONS,
  TICKET_SCOPES,
  TICKET_SORT_OPTIONS,
  TICKET_STATUS_OPTIONS,
  describeRecordContext,
  permittedActions,
  ticketCloseReasonLabel,
  ticketPriorityTone,
  ticketStatusLabel,
  ticketStatusTone,
} from './tickets'

/** 用固定格式器，断言的是"挑了哪些字段"，不是本地化格式。 */
const format = (value: string | null | undefined): string => `T(${value})`

describe('工单展示映射', () => {
  it('四种范围的权限与契约一致：队列单独一个权限，另外三种各按声明', () => {
    expect(TICKET_SCOPES.map((scope) => [scope.value, scope.permission])).toEqual([
      ['REQUESTED_BY_ME', 'TICKET_VIEW_OWN'],
      ['PENDING_QUEUE', 'TICKET_VIEW_QUEUE'],
      ['ASSIGNED_TO_ME', 'TICKET_VIEW_PARTICIPATED'],
      ['PARTICIPATED_BY_ME', 'TICKET_VIEW_PARTICIPATED'],
    ])
  })

  it('每个范围都有自己的空结果说明，不用同一句"暂无数据"', () => {
    const titles = TICKET_SCOPES.map((scope) => scope.emptyTitle)

    expect(new Set(titles).size).toBe(TICKET_SCOPES.length)
    expect(titles.every((title) => title.trim() !== '')).toBe(true)
  })

  it('每个范围也有自己的页头说明，不是一句对所有范围都成立的套话', () => {
    const descriptions = TICKET_SCOPES.map((scope) => scope.pageDescription)

    expect(new Set(descriptions).size).toBe(TICKET_SCOPES.length)
    expect(descriptions.every((text) => text.trim() !== '')).toBe(true)
  })

  describe('动作登记表', () => {
    it('登记表里每个动作的权限与后端判定条件一致', () => {
      expect(
        Object.entries(TICKET_ACTIONS).map(([name, meta]) => [name, meta.permission]),
      ).toEqual([
        ['claim', 'TICKET_CLAIM'],
        ['add-processing-record', 'TICKET_PROCESS'],
        ['submit-resolution', 'TICKET_PROCESS'],
        ['confirm-resolution', 'TICKET_REQUESTER_ACTION'],
        // 完整状态机片 A：撤回补充请求（当前负责人）与反馈未解决（提交人）
        ['withdraw-supplement-request', 'TICKET_PROCESS'],
        ['report-unresolved', 'TICKET_REQUESTER_ACTION'],
      ])
    })

    it('只有需要写正文的动作才有 content，长度上限与后端 @Size 对齐', () => {
      expect(TICKET_ACTIONS.claim.content).toBeUndefined()
      expect(TICKET_ACTIONS['confirm-resolution'].content).toBeUndefined()
      expect(TICKET_ACTIONS['add-processing-record'].content?.maxLength).toBe(10000)
      expect(TICKET_ACTIONS['submit-resolution'].content?.maxLength).toBe(10000)
      // 原因类动作的上限是 1000，与 WithdrawSupplementRequestCommand / ReportUnresolvedCommand 对齐
      expect(TICKET_ACTIONS['withdraw-supplement-request'].content?.maxLength).toBe(1000)
      expect(TICKET_ACTIONS['report-unresolved'].content?.maxLength).toBe(1000)
    })

    it('permittedActions 取 allowedActions 与权限的交集，顺序由登记表决定', () => {
      const allowAll = () => true

      // 服务端返回顺序打乱，界面顺序仍然固定
      expect(permittedActions(['confirm-resolution', 'claim'], allowAll)).toEqual([
        'claim',
        'confirm-resolution',
      ])
    })

    it('服务端尚未放行的动作不出现：没有权限的动作也不出现', () => {
      const allowAll = () => true
      const allowNone = () => false

      expect(permittedActions(['add-processing-record'], allowAll)).toEqual([
        'add-processing-record',
      ])
      // 登记表里有提交解决结果，但 allowedActions 没给：界面不自行补一个按钮
      expect(permittedActions([], allowAll)).toEqual([])
      // 服务端给了，但本账号已经不具备该权限（取详情后被撤权）
      expect(permittedActions(['claim'], allowNone)).toEqual([])
    })

    it('登记表之外的动作名被忽略，不会变成按不动的按钮', () => {
      expect(permittedActions(['transfer', 'close'], () => true)).toEqual([])
    })
  })

  it('状态选项覆盖七个编码且顺序按生命周期，不是按字母', () => {
    expect(TICKET_STATUS_OPTIONS.map((option) => option.value)).toEqual([
      'PENDING',
      'PROCESSING',
      'WAITING_FOR_REQUESTER',
      'WAITING_FOR_CONFIRMATION',
      'COMPLETED',
      'CANCELED',
      'CLOSED',
    ])
    expect(TICKET_STATUS_OPTIONS.map((option) => option.label)).toContain('待员工补充')
  })

  it('排序用哨兵值表示"不发送 sort"，避免用空字符串当选中值', () => {
    expect(TICKET_SORT_OPTIONS[0]?.value).toBe('DEFAULT')
    expect(TICKET_SORT_OPTIONS.map((option) => option.value)).not.toContain('')
  })

  it('优先级选项由高到低，且只有"高"占用语义色', () => {
    expect(TICKET_PRIORITY_OPTIONS.map((option) => option.value)).toEqual(['HIGH', 'MEDIUM', 'LOW'])
    expect(ticketPriorityTone('HIGH')).toBe('high')
    expect(ticketPriorityTone('MEDIUM')).toBe('medium')
    expect(ticketPriorityTone('LOW')).toBe('low')
  })

  it('状态色调把"等别人"和"已经结束"分开', () => {
    expect(ticketStatusTone('PENDING')).toBe('waiting')
    expect(ticketStatusTone('PROCESSING')).toBe('active')
    expect(ticketStatusTone('COMPLETED')).toBe('done')
    expect(ticketStatusTone('CLOSED')).toBe('ended')
    expect(ticketStatusLabel('WAITING_FOR_CONFIRMATION')).toBe('待员工确认')
  })
})

describe('describeRecordContext', () => {
  it('创建记录只渲染状态与优先级，内部主键不进界面', () => {
    expect(
      describeRecordContext({ toStatus: 'PENDING', categoryId: 5, toPriority: 'HIGH' }, format),
    ).toEqual([
      { label: '状态', value: '待受理' },
      { label: '优先级', value: '高' },
    ])
  })

  it('领取记录不渲染负责人主键：当前负责人由详情快照给出', () => {
    expect(
      describeRecordContext({ assigneeId: 7, fromStatus: 'PENDING', toStatus: 'PROCESSING' }, format),
    ).toEqual([{ label: '状态', value: '待受理 → 处理中' }])
  })

  it('调整分类只留下原因，分类主键被丢弃', () => {
    expect(
      describeRecordContext({ fromCategoryId: 1, toCategoryId: 9, reason: '归类到硬件' }, format),
    ).toEqual([{ label: '原因', value: '归类到硬件' }])
  })

  it('补充与解决记录把正文放在最前，再给期限与状态', () => {
    expect(
      describeRecordContext(
        {
          content: '请提供设备编号',
          deadlineAt: '2026-09-30T01:00:00Z',
          fromStatus: 'PROCESSING',
          toStatus: 'WAITING_FOR_REQUESTER',
        },
        format,
      ),
    ).toEqual([
      { label: '内容', value: '请提供设备编号' },
      { label: '期限', value: 'T(2026-09-30T01:00:00Z)' },
      { label: '状态', value: '处理中 → 待员工补充' },
    ])
  })

  it('完成与关闭记录把编码翻成中文，未知取值原样显示而不是丢掉', () => {
    expect(
      describeRecordContext(
        {
          completionMethod: 'REQUESTER_CONFIRMED',
          closeMethod: 'MANUAL',
          closeReason: 'DUPLICATE',
          fromStatus: 'PROCESSING',
          toStatus: 'CLOSED',
        },
        format,
      ),
    ).toEqual([
      { label: '完成方式', value: '员工确认完成' },
      { label: '关闭方式', value: '人工关闭' },
      { label: '关闭原因', value: '重复工单' },
      { label: '状态', value: '处理中 → 已关闭' },
    ])

    expect(describeRecordContext({ completionMethod: 'FUTURE_METHOD' }, format)).toEqual([
      { label: '完成方式', value: 'FUTURE_METHOD' },
    ])
  })

  it('空白字符串与缺失字段都不产生条目，避免渲染出一堆空标签', () => {
    expect(describeRecordContext({ reason: '   ', content: undefined }, format)).toEqual([])
  })

  it('终态原因文案由同一份映射给出', () => {
    expect(ticketCloseReasonLabel('REQUESTER_NO_RESPONSE')).toBe('提交人逾期未补充信息')
    expect(ticketCloseReasonLabel(null)).toBeNull()
  })
})
