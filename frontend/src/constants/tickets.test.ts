import { describe, expect, it } from 'vitest'

import {
  TICKET_ACTIONS,
  TICKET_CLOSE_REASON_OPTIONS,
  TICKET_PRIORITY_OPTIONS,
  TICKET_SCOPES,
  TICKET_SORT_OPTIONS,
  TICKET_STATUS_OPTIONS,
  describeRecordContext,
  permittedActions,
  ticketCloseReasonLabel,
  ticketPriorityTone,
  ticketRecordTypeLabel,
  ticketStatusLabel,
  ticketStatusTone,
} from './tickets'
import type { TicketActionName } from './tickets'

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
        // 完整状态机片 B：请求补充（当前负责人）
        ['request-supplement', 'TICKET_PROCESS'],
        ['confirm-resolution', 'TICKET_REQUESTER_ACTION'],
        // 完整状态机片 A：撤回补充请求（当前负责人）与反馈未解决（提交人）
        ['withdraw-supplement-request', 'TICKET_PROCESS'],
        ['report-unresolved', 'TICKET_REQUESTER_ACTION'],
        // 完整状态机片 C：调整分类与调整优先级跟"记录处理过程"同权限；
        // 转交是另一条授权（IT_SUPPORT 单独持有 TICKET_TRANSFER）
        ['change-category', 'TICKET_PROCESS'],
        ['change-priority', 'TICKET_PROCESS'],
        ['transfer', 'TICKET_TRANSFER'],
        // 完整状态机片 D：关闭是唯一需要**两条授权同时成立**的动作
        // （后端 canClose = canProcess && TICKET_CLOSE，2026-10-08 用户裁决）；
        // 撤销与补充、确认、反馈未解决共用提交人动作权限
        ['close', ['TICKET_PROCESS', 'TICKET_CLOSE']],
        // 完整状态机片 B：补充信息（提交人）
        ['supplement', 'TICKET_REQUESTER_ACTION'],
        // 两阶段撤销（2026-10-08 规则变更）：提交人侧发起与撤回共用提交人动作权限，
        // 负责人侧同意与驳回共用处理权限（**不要求** TICKET_CLOSE——撤销不是关闭）
        ['request-cancel', 'TICKET_REQUESTER_ACTION'],
        ['withdraw-cancel-request', 'TICKET_REQUESTER_ACTION'],
        ['approve-cancel', 'TICKET_PROCESS'],
        ['reject-cancel', 'TICKET_PROCESS'],
        ['cancel', 'TICKET_REQUESTER_ACTION'],
      ])
    })

    it('只有需要写正文的动作才有 content，长度上限与后端 @Size 对齐', () => {
      expect(TICKET_ACTIONS.claim.content).toBeUndefined()
      expect(TICKET_ACTIONS['confirm-resolution'].content).toBeUndefined()
      expect(TICKET_ACTIONS['add-processing-record'].content?.maxLength).toBe(10000)
      expect(TICKET_ACTIONS['submit-resolution'].content?.maxLength).toBe(10000)
      // 正文类动作的上限是 10000，与 RequestSupplementCommand / SupplementCommand 对齐
      expect(TICKET_ACTIONS['request-supplement'].content?.maxLength).toBe(10000)
      expect(TICKET_ACTIONS.supplement.content?.maxLength).toBe(10000)
      // 原因类动作的上限是 1000，与 WithdrawSupplementRequestCommand / ReportUnresolvedCommand 对齐
      expect(TICKET_ACTIONS['withdraw-supplement-request'].content?.maxLength).toBe(1000)
      expect(TICKET_ACTIONS['report-unresolved'].content?.maxLength).toBe(1000)
    })

    /**
     * 片 C 的三个动作与前面的动作有一处结构性差别：先选目标值，再写原因。
     *
     * <p>原因走 `reason` 而不是 `content`（后端的请求字段就叫 `reason`），所以这里同时钉住
     * "三个动作都没有 `content`"：哪天有人顺手补一个 `content`，弹窗里就会出现两个说明输入框。</p>
     */
    it('片 C 三个动作各自声明目标值来源与原因上限，且不混用 content', () => {
      const cases: [TicketActionName, 'category' | 'priority' | 'assignee', boolean][] = [
        ['change-category', 'category', false],
        ['change-priority', 'priority', false],
        ['transfer', 'assignee', true],
      ]

      for (const [name, source, destructive] of cases) {
        const meta = TICKET_ACTIONS[name]
        expect(meta.select?.source).toBe(source)
        expect(meta.destructive).toBe(destructive)
        expect(meta.content).toBeUndefined()
        // 与 ChangeCategoryCommand / ChangePriorityCommand / TransferCommand 的 @Size(max = 1000) 对齐
        expect(meta.reason?.maxLength).toBe(1000)
        expect(meta.reason?.label.trim()).not.toBe('')
      }
    })

    /**
     * 没有可选项时的提示是转交唯一的说明：候选人接口返回空数组不是错误，界面只能靠这句话
     * 讲清"为什么这个动作现在做不了"。需要选目标值的动作各写一句，不能留空，也不能共用一句套话。
     *
     * <p>片 D 的关闭用的是本地静态三选一，空选项在现实中不会发生；它仍然登记一句，
     * 是因为这个槽位的契约要求"没有可选项时说什么"必须有答案，而不是让页面在那一刻
     * 退化成一个没人解释的空下拉。</p>
     */
    it('每个需要选目标值的动作都有自己的空态说明', () => {
      const empties = (
        ['change-category', 'change-priority', 'transfer', 'close'] as const
      ).map((name) => {
        const select = TICKET_ACTIONS[name].select
        expect(select?.placeholder).toContain('请选择')
        return select?.emptyText ?? ''
      })

      expect(empties.every((text) => text.trim() !== '')).toBe(true)
      expect(new Set(empties).size).toBe(empties.length)
    })

    /**
     * 服务端在「待补充」上只返回其中一个动作：负责人拿 `withdraw-supplement-request`，
     * 提交人拿 `supplement`。这条断言钉住"两者不会同时被渲染成两个按钮"的界面结果，
     * 因为那个状态下一步只能有一件事可做。
     */
    it('待补充状态下两个动作互斥：同一份 allowedActions 不会同时渲染提交补充与撤回', () => {
      const allowAll = () => true

      expect(permittedActions(['withdraw-supplement-request'], allowAll)).toEqual([
        'withdraw-supplement-request',
      ])
      expect(permittedActions(['supplement'], allowAll)).toEqual(['supplement'])
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

    /**
     * 服务端将来放行某个界面还没实现的动作名时，界面不会摆出一个按不动的按钮。
     *
     * <p>片 D 之后 `close` 与 `cancel` 都已登记，所以这条用例换成一个**确实还没实现**的动作名
     * （`admin-handoff` 是完整版 backlog 第 4 项的管理性交接）。它比"随便写个字符串"更有意义：
     * 这正是最可能先在后端出现、而界面还没接上的那一类动作。</p>
     */
    it('登记表之外的动作名被忽略，不会变成按不动的按钮', () => {
      expect(permittedActions(['transfer', 'admin-handoff'], () => true)).toEqual(['transfer'])
    })

    /**
     * 完整状态机片 D：结束路径的两个动作。
     *
     * <p>两者都是**终态动作**：一次点击就结束这张工单，而 v1 明确不支持恢复
     * （`docs/kickoff.md` 4.7 / 4.8），所以两个都必须标成 `destructive`——按钮用警示色，
     * 确认框也不允许点遮罩关掉。这是"点错就回不去"的最低防线。</p>
     */
    it('片 D 两格都是终态动作：destructive、原因上限 1000、且不混用 content', () => {
      const cases: [TicketActionName, string | readonly string[]][] = [
        ['close', ['TICKET_PROCESS', 'TICKET_CLOSE']],
        ['cancel', 'TICKET_REQUESTER_ACTION'],
      ]

      for (const [name, permission] of cases) {
        const meta = TICKET_ACTIONS[name]
        // 结构比较：关闭声明的是两条权限的数组，`toBe` 会按引用判等而失败
        expect(meta.permission).toEqual(permission)
        expect(meta.destructive).toBe(true)
        expect(meta.content).toBeUndefined()
        // 与 CloseTicketCommand.description / CancelTicketCommand.reason 的 @Size(max = 1000) 对齐
        expect(meta.reason?.maxLength).toBe(1000)
        expect(meta.reason?.label.trim()).not.toBe('')
        expect(meta.reason?.placeholder.trim()).not.toBe('')
      }
    })

    it('关闭原因用本地静态刻度：只列人工关闭的三种，不含自动关闭的逾期原因', () => {
      expect(TICKET_ACTIONS.close.select?.source).toBe('reasonCode')

      // 三个选项就是 CloseTicketCommand 的 @Pattern，中文与时间线上的关闭原因共用同一份映射
      expect(TICKET_CLOSE_REASON_OPTIONS.map((option) => option.value)).toEqual([
        'DUPLICATE',
        'OUT_OF_SCOPE',
        'INVALID',
      ])
      expect(TICKET_CLOSE_REASON_OPTIONS.map((option) => option.label)).toEqual([
        '重复工单',
        '超出支持范围',
        '无效工单',
      ])
      // REQUESTER_NO_RESPONSE 属于 AUTO_SUPPLEMENT_TIMEOUT（员工逾期未补充）：它能出现在展示映射里，
      // 不能出现在选项里——人工关闭传它会被 ck_ticket_close_semantics 拒绝
      expect(TICKET_CLOSE_REASON_OPTIONS.map((option) => option.value)).not.toContain(
        'REQUESTER_NO_RESPONSE',
      )
    })

    it('条件输入的取值口径：whenValue 落在同一动作的选项里，且只有关闭声明了它', () => {
      const conditional = TICKET_ACTIONS.close.conditionalText
      expect(conditional?.field).toBe('duplicateTicketNo')
      // 与 CloseTicketCommand.duplicateTicketNo 的 @Size(max = 32) 对齐
      expect(conditional?.maxLength).toBe(32)
      expect(conditional?.label.trim()).not.toBe('')
      expect(conditional?.placeholder.trim()).not.toBe('')

      /**
       * 这一条是真正的要害：`whenValue` 的语义是"已选目标值等于它时才出现"，所以它必须取自
       * `select` 的取值域。写成一个不存在的编码时条件输入永远不会出现，而
       * `duplicateTicketNo` 恰恰是"关闭原因为重复工单时必须填"的字段——用户选完重复工单、
       * 提交、拿到一句 400，界面上却没有任何地方能填这个编号。
       */
      const values = TICKET_CLOSE_REASON_OPTIONS.map((option) => option.value as string)
      expect(values).toContain(conditional?.whenValue)

      // 其余动作都没有条件输入：多声明一个就会在弹窗里凭空多出一个输入框
      const withConditional = (Object.keys(TICKET_ACTIONS) as TicketActionName[]).filter(
        (name) => TICKET_ACTIONS[name].conditionalText !== undefined,
      )
      expect(withConditional).toEqual(['close'])
      // 撤销是"写个原因就结束"，没有要选的目标值，也没有条件输入
      expect(TICKET_ACTIONS.cancel.select).toBeUndefined()
      expect(TICKET_ACTIONS.cancel.conditionalText).toBeUndefined()
    })

    /**
     * 关闭与撤销分属两侧：关闭是负责人的动作，撤销是提交人的动作，两者不会出现在同一份
     * `allowedActions` 里。顺序还是要钉住，因为 `permittedActions` 按登记表顺序渲染，
     * 顺序一变按钮位置就变（同一张工单上补一个动作不该让已有按钮换位置）。
     */
    it('片 D 的动作位置：关闭紧随转交，撤销排在最后、补充信息在它前面', () => {
      const allowAll = () => true

      expect(permittedActions(['cancel', 'close', 'transfer'], allowAll)).toEqual([
        'transfer',
        'close',
        'cancel',
      ])
      // 提交人在「待补充」上两件事都能做：先补齐信息，再考虑终止
      expect(permittedActions(['cancel', 'supplement'], allowAll)).toEqual(['supplement', 'cancel'])
    })

    /**
     * 片 D 的动作按权限二次收口，而且**关闭要两条都满足**。
     *
     * <p>这一格钉的是一次真实的口径差：后端 `canClose = canProcess && TICKET_CLOSE`，
     * 而登记表原先只写了 `TICKET_CLOSE`。正常路径看不出差别（`V2` 把两个权限一起授给
     * `IT_SUPPORT`），差别只出现在"取详情后 `TICKET_PROCESS` 被撤掉"的窗口里——
     * 按钮还摆着，点下去得到 403。两条缺一都不该渲染。</p>
     */
    it('片 D 的动作按权限二次收口：关闭必须同时持有处理与关闭两条权限', () => {
      const both = (code: string) => code === 'TICKET_PROCESS' || code === 'TICKET_CLOSE'

      expect(permittedActions(['close'], both)).toEqual(['close'])
      // 只持其中一条：不渲染（少写一条就等于放一个点了必 403 的按钮出去）
      expect(permittedActions(['close'], (code) => code === 'TICKET_CLOSE')).toEqual([])
      expect(permittedActions(['close'], (code) => code === 'TICKET_PROCESS')).toEqual([])
      expect(permittedActions(['cancel'], (code) => code === 'TICKET_REQUESTER_ACTION')).toEqual([
        'cancel',
      ])
      // 取详情后被撤权：按钮不该继续摆着，点下去只会得到 403
      expect(permittedActions(['close', 'cancel'], () => false)).toEqual([])
    })

    /**
     * 单个字符串与单元素数组等价，且"全部满足"只对数组生效。
     *
     * <p>没有这一格，`allowedActions` 与权限都不为空、却因为实现把数组当成"任一满足"
     * 而渲染出按钮时，前面那些用例仍然会通过（它们给的权限集合恰好同时覆盖两条）。</p>
     */
    it('单权限动作不受影响：字符串与单元素数组都只要求那一条', () => {
      expect(permittedActions(['transfer'], (code) => code === 'TICKET_TRANSFER')).toEqual([
        'transfer',
      ])
      expect(permittedActions(['transfer'], () => false)).toEqual([])
      expect(TICKET_ACTIONS.transfer.permission).toBe('TICKET_TRANSFER')
    })

    /**
     * 两阶段撤销的四格（2026-10-08 规则变更，`docs/kickoff.md` 4.7）。
     *
     * <p>这四格的价值在于**界面不再把"申请"渲染成"已经撤销"**：只有 `approve-cancel` 是终态动作
     * （`destructive`），另外三格点下去都不结束工单。发起与驳回要写一段说明（`reason`），
     * 同意与撤回与 `claim` 一样只需要版本，因此没有 `reason` 也没有 `content`——
     * 多声明一个就会在弹窗里凭空多出一个没人看的输入框。</p>
     */
    it('两阶段撤销四格：只有「同意撤销」是终态动作，写说明的两格走 reason', () => {
      expect(TICKET_ACTIONS['request-cancel'].destructive).toBe(false)
      expect(TICKET_ACTIONS['withdraw-cancel-request'].destructive).toBe(false)
      // 同意 = 一次点击把工单推进终态「已取消」，v1 不支持恢复
      expect(TICKET_ACTIONS['approve-cancel'].destructive).toBe(true)
      // 驳回只是不同意这一次申请，工单继续处理，所以不该用警示色
      expect(TICKET_ACTIONS['reject-cancel'].destructive).toBe(false)

      // 与 RequestCancelCommand.reason / RejectCancelCommand.reason 的 @Size(max = 1000) 对齐
      expect(TICKET_ACTIONS['request-cancel'].reason?.maxLength).toBe(1000)
      expect(TICKET_ACTIONS['reject-cancel'].reason?.maxLength).toBe(1000)
      expect(TICKET_ACTIONS['request-cancel'].reason?.label.trim()).not.toBe('')
      expect(TICKET_ACTIONS['reject-cancel'].reason?.label.trim()).not.toBe('')

      // 只需要版本的两个动作：没有说明槽，也没有正文槽
      for (const name of ['withdraw-cancel-request', 'approve-cancel'] as const) {
        expect(TICKET_ACTIONS[name].reason).toBeUndefined()
        expect(TICKET_ACTIONS[name].content).toBeUndefined()
        expect(TICKET_ACTIONS[name].select).toBeUndefined()
        expect(TICKET_ACTIONS[name].conditionalText).toBeUndefined()
      }

      // 四格都不需要选目标值
      for (const name of [
        'request-cancel',
        'withdraw-cancel-request',
        'approve-cancel',
        'reject-cancel',
      ] as const) {
        expect(TICKET_ACTIONS[name].select).toBeUndefined()
        expect(TICKET_ACTIONS[name].label.trim()).not.toBe('')
        expect(TICKET_ACTIONS[name].description.trim()).not.toBe('')
      }
    })

    /**
     * 按钮位置：`permittedActions` 按登记表顺序渲染，顺序一变按钮位置就变。
     *
     * <p>两对动作各自**互斥**：同一张单上提交人只会看到"申请"或"撤回"其中之一，
     * 负责人只会看到"同意 + 驳回"。位置按生命周期排（申请 → 撤回 → 同意 → 驳回），
     * 并排在 `cancel` 之前——`cancel` 只剩「待受理」，与它们不会同屏。</p>
     */
    it('两阶段撤销四格的位置与互斥：申请在撤回前，同意在驳回前，都排在撤销之前', () => {
      const allowAll = () => true

      expect(
        permittedActions(
          ['approve-cancel', 'cancel', 'withdraw-cancel-request', 'request-cancel'],
          allowAll,
        ),
      ).toEqual(['request-cancel', 'withdraw-cancel-request', 'approve-cancel', 'cancel'])

      // 提交人在「待补充」上同时能补充与申请撤销：先补齐信息，再谈终止
      expect(permittedActions(['supplement', 'request-cancel'], allowAll)).toEqual([
        'supplement',
        'request-cancel',
      ])
      // 负责人在「处理中」上：关闭与同意/驳回会同时出现（申请期间工单不冻结）
      expect(permittedActions(['approve-cancel', 'reject-cancel', 'close'], allowAll)).toEqual([
        'close',
        'approve-cancel',
        'reject-cancel',
      ])
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

  /**
   * 记录类型中文名必须与迁移同步：少一个键不会报错，只会让时间线里那一行显示成英文编码
   * （`ticketRecordTypeLabels[recordType] ?? recordType` 的兜底）。
   *
   * <p>`V7` 追加的四种两阶段撤销记录尤其容易漏：它们是一条完整链路（申请 → 同意 / 驳回 / 撤回），
   * 而"待受理直接撤销"的 `CANCELLATION` 是另一件事，两者不能共用一个中文名，否则时间线上
   * 分不清"有人申请撤销"和"工单已经被撤销"。</p>
   */
  it('V7 追加的四种撤销记录都有独立中文名，且与 CANCELLATION 区分', () => {
    const labels = [
      ticketRecordTypeLabel('CANCELLATION_REQUEST'),
      ticketRecordTypeLabel('CANCELLATION_APPROVED'),
      ticketRecordTypeLabel('CANCELLATION_REJECTED'),
      ticketRecordTypeLabel('CANCELLATION_REQUEST_WITHDRAWN'),
    ]

    expect(labels.every((label) => label.trim() !== '' && !label.includes('CANCELLATION'))).toBe(
      true,
    )
    // 四个名字互不相同，也都不等于"待受理直接撤销"那一条
    expect(new Set([...labels, ticketRecordTypeLabel('CANCELLATION')]).size).toBe(5)
    // 兜底行为不变：迁移里将来新增、界面还没接上的类型按编码原样显示，而不是丢掉这一行
    expect(ticketRecordTypeLabel('FUTURE_RECORD')).toBe('FUTURE_RECORD')
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

  /**
   * 撤销申请记录（`CANCELLATION_REQUEST`）：说明与响应期限都要能看到。
   *
   * <p>它**不迁移状态**，所以上下文里 `fromStatus` 与 `toStatus` 相同；这里不断言那一行的具体
   * 文案，只用 `toContainEqual` 钉住"申请理由"和"响应期限"必须出现——时间线在状态没变时照样
   * 渲染一行状态，是 `PROCESS` / `TRANSFER` 等既有记录就有的共同口径，不因这条记录改变。</p>
   */
  it('撤销申请记录渲染申请理由与响应期限', () => {
    const facts = describeRecordContext(
      {
        reason: '问题已自行解决，不需要 IT 再处理了',
        deadlineAt: '2026-10-11T02:00:00Z',
        fromStatus: 'PROCESSING',
        toStatus: 'PROCESSING',
      },
      format,
    )

    expect(facts).toContainEqual({ label: '原因', value: '问题已自行解决，不需要 IT 再处理了' })
    expect(facts).toContainEqual({ label: '期限', value: 'T(2026-10-11T02:00:00Z)' })
  })

  it('驳回撤销申请记录必须带理由：没有理由只渲染出状态一行', () => {
    const facts = describeRecordContext(
      { reason: '问题尚未定位，正在等供应商回复', fromStatus: 'PROCESSING', toStatus: 'PROCESSING' },
      format,
    )

    expect(facts).toContainEqual({ label: '原因', value: '问题尚未定位，正在等供应商回复' })
  })

  it('终态原因文案由同一份映射给出', () => {
    expect(ticketCloseReasonLabel('REQUESTER_NO_RESPONSE')).toBe('提交人逾期未补充信息')
    expect(ticketCloseReasonLabel(null)).toBeNull()
  })
})
