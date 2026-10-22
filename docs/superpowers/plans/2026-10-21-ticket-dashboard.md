# Ticket Dashboard Implementation Plan

> **For agentic workers:** Implement the checked tasks in order. Read the spec and repository instructions first. The user has authorized WorkBuddy to implement this dashboard, including its ServiceImpl business logic; do not commit, push, or merge.

**Goal:** Replace the `/dashboard` placeholder with a scoped, permission checked ticket count overview whose four group totals reconcile with the total.

**Architecture:** Add a dedicated ticket query path, SQL aggregation over the existing ticket scopes, and a Vue page using the current design system. Keep role and permission checks on the server, derive identity from authentication, and use one consistent database snapshot across aggregation queries.

**Tech Stack:** Java 21, Spring Boot 3, MyBatis Plus, MySQL 8, Vue 3, TypeScript, Element Plus, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-21-ticket-dashboard-design.md`; API contract in `docs/api-design.md` §8.5.

## Global Constraints

- Work only in `C:\Users\mxb00\.codex\worktrees\ticket-dashboard\FLow-Desk` on `flow-desk/ticket-dashboard`, based on `main` at `58a59ce`.
- Read root `AGENTS.md`, `PROJECT_STATUS.md`, `frontend/AGENTS.md`, `.ui-craft/frontend-redesign-handoff.md`, `.ui-craft/references/README.md`; inspect the five reference images before frontend edits.
- Preserve existing application `command/query/result/service/impl` and cross-module port direction. Check related code and tests before deciding signatures.
- No new infrastructure, cache, scheduled job, or migration without evidence. Never alter published migrations.
- Do not run Git branch/checkout/worktree/reset/stash/commit/push/merge operations. Leave changes for Codex review.

## Review Focus

- Multi-role admin without `IT_SUPPORT` must receive 403, even if they have `DASHBOARD_VIEW`.
- Historical participation with multiple rows for one user and ticket must count the ticket once.
- Total and all four grouped sums must match under concurrent writes and after filters.
- Inactive categories and users must retain current display labels; unassigned tickets need a group without the `assignee` property.
- UI must not offer unauthorized scopes, lose an inactive category filter option, or overflow at 375 px.

---

### Task 1: Backend contract and authorization

**Files:** Add dedicated dashboard Query/Result, service interface and implementation under `src/main/java/com/flowdesk/ticket/application/`; add endpoint under `ticket/controller/`; add an IAM backed role check port/adapter only if existing trusted port cannot express the check. Add focused Web/service tests next to current ticket tests.

- [ ] Define `GET /fd/v1/dashboard/tickets` with required `scope` limited to `PENDING_QUEUE`, `ASSIGNED_TO_ME`, `PARTICIPATED_BY_ME`, plus optional `createdFrom`, `createdTo`, `categoryId`. Reject unsupported parameters rather than silently ignoring them.
- [ ] Verify unauthenticated 401; `DASHBOARD_VIEW`, `IT_SUPPORT`, and scope specific `TICKET_VIEW_QUEUE` or `TICKET_VIEW_PARTICIPATED` checks produce 403 on failure. Current user comes from auth context.
- [ ] Verify invalid/missing scope, `REQUESTED_BY_ME`, invalid date range, and nonpositive category ID produce `400/VALIDATION_FAILED`.
- [ ] Return `R<T>` with `scope`, `total`, `byStatus`, `byPriority`, `byCategory`, and `byAssignee`, including empty arrays for zero matches.

### Task 2: SQL aggregation and consistency

**Files:** Extend `ticket/mapper/TicketMapper.java` or add a focused mapper, add small persistence row types under `ticket/infrastructure/persistence/`; add real MySQL integration tests under `src/test/`.

- [ ] Match the existing `selectScopedPage` scope predicates. Use `EXISTS` for historical participation; aggregate in MySQL, never fetch all tickets into Java.
- [ ] Apply UTC inclusive creation bounds and optional category ID identically to total and four groups. Read all results in a single consistent snapshot; verify transaction isolation actually provides it.
- [ ] Verify each group sums to `total`; status and priority order are fixed by spec; category and assignee order by count descending then ID ascending, null assignee last on ties.
- [ ] Test unassigned queue tickets, terminal tickets with last assignee, repeated participation, inactive/renamed category and user, exact time edges, zero results, and scope/list total alignment on real MySQL.
- [ ] Inspect `EXPLAIN` on representative rows; record result and add an index only if justified.

### Task 3: Vue dashboard

**Files:** Add `frontend/src/api/dashboard.ts`, `frontend/src/views/work/TicketDashboardView.vue` and colocated unit test; update `frontend/src/router/index.ts` and any navigation/permission handling needed by existing patterns; add focused Playwright coverage.

- [ ] Replace only `/dashboard` placeholder. Show authorized scope options, default queue if allowed, current scope total, four groups, compact date/category filters, query/reset, loading/empty/error/retry states.
- [ ] Derive category options from the unfiltered-by-category `byCategory` response for current scope and time; retain options when category filter is applied. Clear category on scope or date changes.
- [ ] Hide or block dashboard for users without required role/permissions, with server authorization still decisive. Do not add drilldown, trend, export, or polling.
- [ ] Follow design tokens, Element Plus rules, and reference images; verify at 1440 and 375 px and save review screenshots under `.ui-craft/reviews/`.

### Task 4: End-to-end acceptance and handoff

**Files:** Update implementation status in `PROJECT_STATUS.md`, `README.md` and relevant contract docs only after behavior is verified; add acceptance evidence if real stack is available.

- [ ] Run relevant backend unit/Web/MySQL tests, then full backend verification (`.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"`) when infrastructure is available.
- [ ] Run frontend `pnpm typecheck`, `pnpm lint`, `pnpm test:unit -- --run`, `pnpm build`, and focused/full Playwright as practical. Capture commands, counts, failures and environment limits exactly.
- [ ] Review security and response consistency against all spec acceptance criteria; report modified files, decisions, test output, screenshots and remaining limitations to Codex.
- [ ] Stop with uncommitted changes for Codex's independent review. Do not claim unrun checks passed.
