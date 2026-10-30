package com.flowdesk.ticket.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.command.GenerateTicketAiSuggestionCommand;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.query.TicketAiSuggestionQuery;
import com.flowdesk.ticket.application.result.TicketAiInputSnapshotResult;
import com.flowdesk.ticket.application.result.TicketAiSuggestionResult;
import com.flowdesk.ticket.application.result.TicketAiSourcesPreviewResult;
import com.flowdesk.ticket.application.service.TicketAiSuggestionService;
import com.flowdesk.ticket.config.TicketAiProperties;
import com.flowdesk.ticket.infrastructure.persistence.TicketAiRequestRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketAiSuggestionRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketAiSourceRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketAiSuggestionSourceRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketAiTicketStateRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.mapper.TicketAiDailyQuotaMapper;
import com.flowdesk.ticket.mapper.TicketAiRequestMapper;
import com.flowdesk.ticket.mapper.TicketAiSuggestionMapper;
import com.flowdesk.ticket.mapper.TicketAiSourceMapper;
import com.flowdesk.ticket.mapper.TicketAiSuggestionSourceMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ClassName TicketAiSuggestionServiceImpl
 * @Description 单张工单 AI 建议的授权、额度、去重、模型调用与历史保存编排
 * @Author xb.m
 * @Date 2026/10/23 20:48
 * @Version 1.0
 */

@Service
public class TicketAiSuggestionServiceImpl implements TicketAiSuggestionService {

    private static final String PROCESSING = "PROCESSING";
    private static final String TICKET_PROCESS = "TICKET_PROCESS";
    private static final String TICKET_AI_ASSIST = "TICKET_AI_ASSIST";
    private static final Pattern SOURCE_LABEL = Pattern.compile("\\[S(\\d+)\\]");
    private static final Pattern SOURCE_LABEL_START = Pattern.compile("\\[\\s*[sS]");
    private static final Pattern BARE_TICKET_NO =
            Pattern.compile("(?i)\\bFD-\\d{8}-\\d+\\b");
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};

    private final TicketMapper ticketMapper;
    private final TicketAiDailyQuotaMapper quotaMapper;
    private final TicketAiRequestMapper requestMapper;
    private final TicketAiSuggestionMapper suggestionMapper;
    private final TicketAiSourceMapper sourceMapper;
    private final TicketAiSuggestionSourceMapper suggestionSourceMapper;
    private final CurrentRequesterPort currentRequesterPort;
    private final TicketReadPermissionPort permissionPort;
    private final ObjectProvider<TicketAiModelPort> modelPortProvider;
    private final TicketAiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;

    /** 注入工单、额度、请求与历史存取依赖；写事务设为 REQUIRES_NEW，使模型调用处于事务之外。 */
    public TicketAiSuggestionServiceImpl(
            TicketMapper ticketMapper,
            TicketAiDailyQuotaMapper quotaMapper,
            TicketAiRequestMapper requestMapper,
            TicketAiSuggestionMapper suggestionMapper,
            TicketAiSourceMapper sourceMapper,
            TicketAiSuggestionSourceMapper suggestionSourceMapper,
            CurrentRequesterPort currentRequesterPort,
            TicketReadPermissionPort permissionPort,
            ObjectProvider<TicketAiModelPort> modelPortProvider,
            TicketAiProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.ticketMapper = ticketMapper;
        this.quotaMapper = quotaMapper;
        this.requestMapper = requestMapper;
        this.suggestionMapper = suggestionMapper;
        this.sourceMapper = sourceMapper;
        this.suggestionSourceMapper = suggestionSourceMapper;
        this.currentRequesterPort = currentRequesterPort;
        this.permissionPort = permissionPort;
        this.modelPortProvider = modelPortProvider;
        this.aiProperties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 预览只执行授权和受限查询，不触碰额度表或模型端口。
     * 摘要在生成时还要重算；预览本身不能作为后续外发的授权凭据。
     */
    @Override
    @Transactional(readOnly = true)
    public TicketAiSourcesPreviewResult previewSources(String ticketNo, Long version) {
        long userId = currentRequesterPort.currentUserId();
        TicketDetailRow ticket = visibleTicket(ticketNo, userId);

        if (!Boolean.TRUE.equals(aiProperties.enabled())) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_DISABLED",
                    "AI 处理辅助当前未启用");
        }
        if (!permissionPort.hasAuthority(TICKET_PROCESS)
                || !permissionPort.hasAuthority(TICKET_AI_ASSIST)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AI_GENERATION_FORBIDDEN",
                    "无 AI 处理辅助权限");
        }
        if (version == null || version < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "工单版本不能为空且不能为负数");
        }
        checkGenerationState(
                ticket.getStatus(),
                ticket.getAssigneeId(),
                ticket.getVersion(),
                userId,
                version);

        List<PreviewSource> sources = visibleSources(ticket, userId);
        List<TicketAiSourcesPreviewResult.Source> visibleSources = sources.stream()
                .map(source -> new TicketAiSourcesPreviewResult.Source(
                        source.label(), source.title(), source.excerpt()))
                .toList();
        return new TicketAiSourcesPreviewResult(visibleSources, sourceDigest(sources));
    }

    /** 预览、新请求占额前和模型返回后共用同一来源选择与裁剪规则。 */
    private List<PreviewSource> visibleSources(TicketDetailRow ticket, long userId) {
        // 回退只影响新预览和生成；已保存的 v5 历史仍按原来源快照授权读取。
        if (!Boolean.TRUE.equals(aiProperties.retrievalEnabled())) {
            return List.of();
        }
        return preparePreviewSources(sourceMapper.selectVisibleEndedSources(
                ticket.getId(), ticket.getCategoryId(), userId,
                permissionPort.hasAuthority("TICKET_VIEW_OWN"),
                permissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED")));
    }

    /**
     * 只裁剪来源的实际处理正文，不补写或推断处理方法。
     * Unicode 字符按码点计数，避免将代理对截成半个字符。
     */
    private static List<PreviewSource> preparePreviewSources(List<TicketAiSourceRow> rows) {
        List<PreviewSource> prepared = new ArrayList<>();
        int remaining = 2000;
        for (TicketAiSourceRow row : rows) {
            if (prepared.size() == 5 || remaining == 0) {
                break;
            }
            if (row.getTicketId() == null
                    || row.getTitle() == null
                    || row.getProcessContent() == null) {
                continue;
            }
            String content = redactTicketNo(
                    row.getProcessContent().strip(), row.getTicketNo());
            if (content.isEmpty()) {
                continue;
            }
            String excerpt = firstCodePoints(content, Math.min(500, remaining));
            if (excerpt.isBlank()) {
                continue;
            }
            prepared.add(new PreviewSource(
                    row.getTicketId(),
                    row.getTicketNo(),
                    "[S" + (prepared.size() + 1) + "]",
                    redactTicketNo(row.getTitle(), row.getTicketNo()),
                    excerpt));
            remaining -= excerpt.codePointCount(0, excerpt.length());
        }
        return List.copyOf(prepared);
    }

    /** 来源自身的真实编号不进入预览文本，也不随标题或摘录发给模型。 */
    private static String redactTicketNo(String text, String ticketNo) {
        return ticketNo == null || ticketNo.isBlank()
                ? text : text.replace(ticketNo, "[来源编号已省略]");
    }

    private static String firstCodePoints(String value, int limit) {
        int end = value.offsetByCodePoints(
                0, Math.min(limit, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }

    /**
     * 摘要覆盖来源顺序、内部 ID、标签、标题和实际摘录。
     * 使用已有的长度前缀编码，避免字段拼接歧义；空来源也有稳定摘要。
     */
    private static String sourceDigest(List<PreviewSource> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "flowdesk-ai-sources-v1");
            updateDigest(digest, Integer.toString(sources.size()));
            for (PreviewSource source : sources) {
                updateDigest(digest, Long.toString(source.ticketId()));
                updateDigest(digest, source.label());
                updateDigest(digest, source.title());
                updateDigest(digest, source.excerpt());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", impossible);
        }
    }

    /** 内部来源投影仅留在服务层，绝不序列化到预览响应。 */
    private record PreviewSource(long ticketId, String ticketNo,
                                 String label, String title, String excerpt) {
    }

    /** 校验当前用户与工单，按请求标识去重并占额；调用模型后复核工单，再保存建议。 */
    @Override
    public TicketAiSuggestionResult generate(String ticketNo, GenerateTicketAiSuggestionCommand command) {
        // 获取当前登录用户，并按现有工单可见性规则读取工单。
        long userId = currentRequesterPort.currentUserId();
        TicketDetailRow ticket = visibleTicket(ticketNo, userId);

        // 生成前校验功能开关、双重权限、负责人、状态和确认时的版本。
        if (!Boolean.TRUE.equals(aiProperties.enabled())) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_DISABLED",
                    "AI 处理辅助当前未启用");
        }

        if (!permissionPort.hasAuthority(TICKET_PROCESS)
                || !permissionPort.hasAuthority(TICKET_AI_ASSIST)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AI_GENERATION_FORBIDDEN",
                    "无 AI 处理辅助权限");
        }

        // 先按 requestId 判断重放。旧请求不能因今天的召回集合不同而重新占额或调用模型。
        TicketAiModelPort.Input baseInput = new TicketAiModelPort.Input(
                command.title(),
                command.description(),
                ticket.getCategoryName(),
                ticket.getPriority(),
                ticket.getStatus());
        TicketAiRequestRow existing = requestMapper.selectByUserAndRequestId(
                userId, command.requestId());
        if (existing != null) {
            return existingResult(existing, ticket, command, baseInput, userId);
        }

        checkGenerationState(ticket.getStatus(), ticket.getAssigneeId(),
                ticket.getVersion(), userId, command.version());

        // 仅新请求重算来源。空召回兼容原五项 v4 指纹；有来源必须持有预览摘要。
        List<PreviewSource> sources = visibleSources(ticket, userId);
        String digest = sources.isEmpty() ? null : sourceDigest(sources);
        if (!sources.isEmpty() && !Objects.equals(command.sourceDigest(), digest)) {
            throw sourcesChanged();
        }
        if (sources.isEmpty() && command.sourceDigest() != null
                && !Objects.equals(command.sourceDigest(), sourceDigest(List.of()))) {
            throw sourcesChanged();
        }
        TicketAiModelPort.Input input = sources.isEmpty() ? baseInput
                : new TicketAiModelPort.Input(
                        baseInput.title(), baseInput.description(), baseInput.category(),
                        baseInput.priority(), baseInput.status(),
                        sources.stream().map(source -> new TicketAiModelPort.Source(
                                source.label(), source.title(), source.excerpt())).toList());
        String inputHash = inputSha256(input, digest);

        // 延迟取得模型适配器：AI 关闭时服务仍可启动，也不要求此时存在供应商 Bean。
        TicketAiModelPort modelPort = modelPortProvider.getIfAvailable();
        if (modelPort == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_UNAVAILABLE", "AI 处理辅助暂不可用");
        }

        TicketAiRequestRow reservation;

        // 并发请求可能同时通过前置查询，最终由数据库唯一键决定胜者。
        try {
            reservation = reserve(ticket, userId, command, inputHash, digest);
        } catch (DuplicateKeyException duplicate) {
            // 并发的同一 requestId 已抢先提交；重新读原请求，绝不再调用模型。
            TicketAiRequestRow winner = requestMapper.selectByUserAndRequestId(
                    userId, command.requestId());
            if (winner == null) {
                throw duplicate;
            }
            return existingResult(winner, ticket, command, baseInput, userId);
        }

        // 模型调用必须位于两段写事务之外，避免长时间占用工单行锁。
        TicketAiModelPort.Output answer;
        try {
            answer = validateSourceReferences(
                    validatedOutput(modelPort.generate(input)), sources);
        } catch (TicketAiModelPort.ModelFailure failure) {
            throw recordModelFailure(reservation.getId(), failure.kind());
        } catch (RuntimeException failure) {
            throw recordModelFailure(
                    reservation.getId(),
                    TicketAiModelPort.FailureKind.PROVIDER_ERROR);
        }

        return saveAnswer(ticket, userId, command, reservation, input, sources, answer);
    }

    /** 只承认本次实际发送的规范标签；裸工单号不得进入任何建议字段。 */
    private static TicketAiModelPort.Output validateSourceReferences(
            TicketAiModelPort.Output answer, List<PreviewSource> sources) {
        if (sources.isEmpty()) {
            return answer;
        }
        List<String> texts = new ArrayList<>();
        texts.add(answer.summary());
        texts.addAll(answer.questions());
        texts.addAll(answer.suggestedSteps());
        for (String text : texts) {
            // 从每一个疑似来源标签起点校验完整语法；[S 1]、[s1]、[S7 等不能漏过。
            Matcher candidate = SOURCE_LABEL_START.matcher(text);
            while (candidate.find()) {
                Matcher matcher = SOURCE_LABEL.matcher(text);
                matcher.region(candidate.start(), text.length());
                if (!matcher.lookingAt()) {
                    throw invalidSourceOutput();
                }
                int number;
                try {
                    number = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException invalid) {
                    throw invalidSourceOutput();
                }
                if (number < 1 || number > sources.size()) {
                    throw invalidSourceOutput();
                }
            }
            // 来源可能提到另一张工单：不只拦截本次召回的几个编号。
            if (BARE_TICKET_NO.matcher(text).find()) {
                throw invalidSourceOutput();
            }
            for (PreviewSource source : sources) {
                if (source.ticketNo() != null && !source.ticketNo().isBlank()
                        && text.contains(source.ticketNo())) {
                    throw invalidSourceOutput();
                }
            }
        }
        return answer;
    }

    private static TicketAiModelPort.ModelFailure invalidSourceOutput() {
        return new TicketAiModelPort.ModelFailure(
                TicketAiModelPort.FailureKind.INVALID_OUTPUT, null);
    }

    /** 查询受限的 AI 建议历史；要求工单可见且用户仍为当前或终态最后负责人。 */
    @Override
    @Transactional(readOnly = true)
    public PageResult<TicketAiSuggestionResult> history(String ticketNo, TicketAiSuggestionQuery query) {
        // 先沿用工单详情的可见性判定。
        long userId = currentRequesterPort.currentUserId();
        TicketDetailRow ticket = visibleTicket(ticketNo, userId);

        // 历史权限独立于新生成权限；撤销 AI 生成权限不影响合规历史读取。
        if (!Objects.equals(ticket.getAssigneeId(), userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AI_HISTORY_FORBIDDEN",
                    "无权查看该工单的 AI 建议历史");
        }

        // 即使定时清理滞后，也在读取时立即执行结束后 30 天的可见期限。
        if (ticket.getEndedAt() != null
                && !ticket.getEndedAt().plusDays(30)
                .isAfter(utc(clock.instant()))) {
            return new PageResult<>(
                    List.of(), query.getCurrent(), query.getSize(), 0, 0);
        }

        // 固定倒序分页读取，不接收客户端自定义排序。
        Page<TicketAiSuggestionRow> page =
                new Page<>(query.getCurrent(), query.getSize());
        Page<TicketAiSuggestionRow> found =
                suggestionMapper.selectTicketPage(
                        page, ticket.getId(), userId,
                        permissionPort.hasAuthority("TICKET_VIEW_OWN"),
                        permissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));


        return PageResult.from(found, row -> toResult(row, ticketNo));
    }

    /** 按现有工单可见性规则读取详情；不可见与不存在统一返回工单不存在。 */
    private TicketDetailRow visibleTicket(String ticketNo, long userId) {
        TicketDetailRow ticket = ticketMapper.selectVisibleDetail(
                ticketNo,
                userId,
                permissionPort.hasAuthority("TICKET_VIEW_OWN"),
                permissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                permissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (ticket == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }
        return ticket;
    }

    /** 校验工单仍在处理中、当前用户仍为负责人，且版本与确认时一致。 */
    private static void checkGenerationState(
            String status,
            Long assigneeId,
            Long actualVersion,
            long userId,
            Long expectedVersion) {
        if (!PROCESSING.equals(status)
                || !Objects.equals(assigneeId, userId)
                || !Objects.equals(actualVersion, expectedVersion)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TICKET_CONFLICT",
                    "工单状态、负责人或版本已变化，请刷新后重试",
                    actualVersion,
                    status);
        }
    }

    /** 第一段短事务：锁定工单、复核状态、登记请求并原子占用北京时间当日额度。 */
    private TicketAiRequestRow reserve(
            TicketDetailRow ticket,
            long userId,
            GenerateTicketAiSuggestionCommand command,
            String inputHash,
            String sourceDigest) {
        return Objects.requireNonNull(writeTransaction.execute(ignored -> {
            // 第一段短事务：锁工单、复核、登记 requestId、原子占额。
            TicketAiTicketStateRow locked =
                    ticketMapper.selectAiStateForUpdate(ticket.getId());
            if (locked == null) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "TICKET_CONFLICT",
                        "工单已变化，请刷新后重试");
            }
            checkGenerationState(
                    locked.getStatus(),
                    locked.getAssigneeId(),
                    locked.getVersion(),
                    userId,
                    command.version());

            // 锁定目标工单后再次召回：若预览以来来源发生变化，事务回滚且不占额。
            List<PreviewSource> currentSources = visibleSources(ticket, userId);
            String currentDigest = currentSources.isEmpty() ? null : sourceDigest(currentSources);
            if (!Objects.equals(currentDigest, sourceDigest)) {
                throw sourcesChanged();
            }

            Instant instant = clock.instant();
            LocalDateTime now = utc(instant);

            TicketAiRequestRow row = new TicketAiRequestRow();
            row.setTicketId(ticket.getId());
            row.setUserId(userId);
            row.setRequestId(command.requestId());
            row.setTicketVersion(command.version());
            row.setInputSha256(inputHash);
            row.setSourceDigest(sourceDigest);
            row.setCreatedAt(now);
            if (requestMapper.insertReserved(row) != 1) {
                throw new IllegalStateException("AI 请求占位未能保存");
            }

            quotaMapper.ensureRow(
                    userId,
                    instant.atZone(QUOTA_ZONE).toLocalDate(),
                    now);
            int used = quotaMapper.tryUseOne(
                    userId,
                    instant.atZone(QUOTA_ZONE).toLocalDate(),
                    aiProperties.dailyLimit(),
                    now);
            if (used != 1) {
                // 抛异常回滚整个短事务，包括刚插入的 RESERVED 请求。
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "AI_DAILY_LIMIT_REACHED",
                        "今日 AI 调用次数已用完");
            }
            return row;
        }));
    }

    /** 处理已登记的同一请求：核对输入指纹，并按请求状态返回原结果或稳定错误。 */
    private TicketAiSuggestionResult existingResult(
            TicketAiRequestRow existing,
            TicketDetailRow ticket,
            GenerateTicketAiSuggestionCommand command,
            TicketAiModelPort.Input baseInput,
            long userId) {
        // 旧 v4 请求的五项指纹原样验证；v5 用保存的摘要核对，不重新召回今天的集合。
        String storedDigest = existing.getSourceDigest();
        String expectedHash = inputSha256(baseInput, storedDigest);
        boolean digestMatches = storedDigest == null
                ? command.sourceDigest() == null
                    || Objects.equals(command.sourceDigest(), sourceDigest(List.of()))
                : Objects.equals(storedDigest, command.sourceDigest());
        if (!Objects.equals(existing.getTicketId(), ticket.getId())
                || !Objects.equals(existing.getTicketVersion(), command.version())
                || !Objects.equals(existing.getInputSha256(), expectedHash)
                || !digestMatches) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AI_REQUEST_ID_REUSED",
                    "请求标识已用于其他输入，请重新确认后生成");
        }

        if (!Objects.equals(ticket.getAssigneeId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_HISTORY_FORBIDDEN",
                    "无权查看该工单的 AI 建议历史");
        }
        // 幂等读取与历史分页使用同一 30 天保留边界，避免清理任务滞后时重放旧内容。
        if (ticket.getEndedAt() != null
                && !ticket.getEndedAt().plusDays(30).isAfter(utc(clock.instant()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_HISTORY_FORBIDDEN",
                    "AI 建议历史已超过保留期限");
        }

        return switch (existing.getStatus()) {
            case "SUCCEEDED" -> {
                TicketAiSuggestionRow row =
                        suggestionMapper.selectByRequestRowId(existing.getId());
                if (row == null) {
                    throw new ApiException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "AI_RESULT_UNCERTAIN",
                            "建议结果暂不可读取");
                }
                if (!allSourcesVisible(row.getId(), userId)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "AI_HISTORY_FORBIDDEN",
                            "建议引用的来源已不可见");
                }
                yield toResult(row, ticket.getTicketNo());
            }
            case "RESERVED" -> throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AI_REQUEST_IN_PROGRESS",
                    "该请求仍在处理中");
            case "STALE" -> throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TICKET_CONFLICT",
                    "生成期间工单发生变化，请刷新后重新确认");
            case "FAILED" -> throw previousFailure(existing.getFailureCode());
            default -> throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_RESULT_UNCERTAIN",
                    "该请求结果不确定，请重新确认后使用新请求标识");
        };
    }

    /** 成功旧请求必须逐条重查来源当前权限；快照不能代替授权。 */
    private boolean allSourcesVisible(long suggestionId, long userId) {
        boolean own = permissionPort.hasAuthority("TICKET_VIEW_OWN");
        boolean participated = permissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED");
        for (TicketAiSuggestionSourceRow source :
                suggestionSourceMapper.selectBySuggestionId(suggestionId)) {
            if (!ticketMapper.existsVisibleTicketById(
                    source.getSourceTicketId(), userId, own, false, participated)) {
                return false;
            }
        }
        return true;
    }

    private static ApiException sourcesChanged() {
        return new ApiException(HttpStatus.CONFLICT, "AI_SOURCES_CHANGED",
                "来源已变化，请重新预览并确认");
    }

    /** 将已经记录的模型失败码转换为 API 错误，供重复请求取得一致结果。 */
    private static ApiException previousFailure(String code) {
        if ("AI_PROVIDER_TIMEOUT".equals(code)) {
            return new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    code,
                    "模型响应超时；如需重试，请重新确认");
        }
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                code == null ? "AI_PROVIDER_ERROR" : code,
                "模型未能生成可用建议；如需重试，请重新确认");
    }

    /** 把模型失败登记为 FAILED；登记结果无法确认时禁止同一请求再次调用模型。 */
    private ApiException recordModelFailure(
            long requestRowId,
            TicketAiModelPort.FailureKind kind) {
        String code = switch (kind) {
            case TIMEOUT -> "AI_PROVIDER_TIMEOUT";
            case INVALID_OUTPUT -> "AI_INVALID_OUTPUT";
            case PROVIDER_ERROR -> "AI_PROVIDER_ERROR";
        };

        try {
            Integer changed = writeTransaction.execute(ignored ->
                    requestMapper.finishIfReserved(
                            requestRowId,
                            "FAILED",
                            code,
                            utc(clock.instant())));
            if (!Objects.equals(changed, 1)) {
                throw new IllegalStateException("请求状态未能结束");
            }
        } catch (RuntimeException databaseFailure) {
            // 不能确定状态时不重放供应商请求；原 requestId 保持受唯一键保护。
            return new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_RESULT_UNCERTAIN",
                    "请求结果暂无法确认，请稍后查看");
        }

        return previousFailure(code);
    }

    /** 第二段短事务：重新锁定工单，过期则标记 STALE；有效则同时保存历史并标记 SUCCEEDED。 */
    private TicketAiSuggestionResult saveAnswer(
            TicketDetailRow ticket,
            long userId,
            GenerateTicketAiSuggestionCommand command,
            TicketAiRequestRow reservation,
            TicketAiModelPort.Input input,
            List<PreviewSource> sources,
            TicketAiModelPort.Output answer) {
        try {
            String questionsJson = objectMapper.writeValueAsString(
                    answer.questions());
            String stepsJson = objectMapper.writeValueAsString(
                    answer.suggestedSteps());

            TicketAiSuggestionResult saved = writeTransaction.execute(ignored -> {
                // 第二段短事务：模型已返回，此时才重新锁工单。
                TicketAiTicketStateRow locked =
                        ticketMapper.selectAiStateForUpdate(ticket.getId());

                List<PreviewSource> currentSources = locked == null
                        ? List.of() : visibleSources(ticket, userId);
                if (locked == null
                        || !PROCESSING.equals(locked.getStatus())
                        || !Objects.equals(locked.getAssigneeId(), userId)
                        || !Objects.equals(locked.getVersion(), command.version())
                        || !Objects.equals(sourceDigest(currentSources), sourceDigest(sources))) {
                    int changed = requestMapper.finishIfReserved(
                            reservation.getId(),
                            "STALE",
                            "TICKET_CONFLICT",
                            utc(clock.instant()));
                    if (changed != 1) {
                        throw new IllegalStateException("请求状态未能结束");
                    }
                    return null;
                }

                TicketAiSuggestionRow row = new TicketAiSuggestionRow();
                row.setRequestRowId(reservation.getId());
                row.setTicketId(ticket.getId());
                row.setRequestId(command.requestId());
                row.setInputTitle(input.title());
                row.setInputDescription(input.description());
                row.setInputCategory(input.category());
                row.setInputPriority(input.priority());
                row.setInputStatus(input.status());
                row.setSummary(answer.summary());
                row.setQuestionsJson(questionsJson);
                row.setSuggestedStepsJson(stepsJson);
                row.setModel(answer.model());
                row.setPromptVersion(answer.promptVersion());
                row.setInputTokens(answer.inputTokens());
                row.setOutputTokens(answer.outputTokens());
                row.setCreatedAt(utc(clock.instant()));

                if (suggestionMapper.insertSuggestion(row) != 1) {
                    throw new IllegalStateException("建议历史未能保存");
                }
                // 来源快照与成功建议同事务提交；仅保存确实发送给模型的来源。
                for (int i = 0; i < sources.size(); i++) {
                    PreviewSource source = sources.get(i);
                    TicketAiSuggestionSourceRow snapshot = new TicketAiSuggestionSourceRow();
                    snapshot.setSuggestionId(row.getId());
                    snapshot.setSequenceNo(i + 1);
                    snapshot.setSourceTicketId(source.ticketId());
                    snapshot.setTicketNo(source.ticketNo());
                    snapshot.setTitle(source.title());
                    snapshot.setProcessExcerpt(source.excerpt());
                    if (suggestionSourceMapper.insert(snapshot) != 1) {
                        throw new IllegalStateException("AI 来源快照未能保存");
                    }
                }
                if (requestMapper.finishIfReserved(
                        reservation.getId(),
                        "SUCCEEDED",
                        null,
                        utc(clock.instant())) != 1) {
                    throw new IllegalStateException("请求状态未能完成");
                }
                return toResult(row, ticket.getTicketNo());
            });

            if (saved == null) {
                // STALE 状态已提交；额度已用，不返回过时的模型内容。
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "TICKET_CONFLICT",
                        "生成期间工单发生变化，请刷新后重新确认");
            }
            return saved;
        } catch (ApiException conflict) {
            throw conflict;
        } catch (RuntimeException | JsonProcessingException saveFailure) {
            markUnknownIfPossible(reservation.getId());
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_SAVE_FAILED",
                    "建议已生成但未能确认保存，请查看历史后再决定是否重新生成");
        }
    }

    /** 尽力把保存结果不确定的请求标记为 UNKNOWN；失败时保留唯一键防止重放。 */
    private void markUnknownIfPossible(long requestRowId) {
        try {
            writeTransaction.execute(ignored -> {
                requestMapper.finishIfReserved(
                        requestRowId,
                        "UNKNOWN",
                        "AI_SAVE_FAILED",
                        utc(clock.instant()));
                return null;
            });
        } catch (RuntimeException ignored) {
            // 数据库仍不可用时保留 RESERVED；同一 requestId 依旧不会再次调用模型。
        }
    }

    /** 校验模型输出的结构、模型名、提示词版本、长度与 token 数，再规范化文本。 */
    private static TicketAiModelPort.Output validatedOutput(
            TicketAiModelPort.Output output) {
        if (output == null
                || output.summary() == null
                || output.summary().isBlank()
                || output.summary().strip().length() > 2000
                || output.model() == null
                || !"deepseek-flash".equals(output.model())
                || output.promptVersion() == null
                || output.promptVersion().isBlank()
                || output.promptVersion().length() > 32
                || (output.inputTokens() != null && output.inputTokens() < 0)
                || (output.outputTokens() != null && output.outputTokens() < 0)) {
            throw new TicketAiModelPort.ModelFailure(
                    TicketAiModelPort.FailureKind.INVALID_OUTPUT, null);
        }

        return new TicketAiModelPort.Output(
                output.summary().strip(),
                checkedItems(output.questions()),
                checkedItems(output.suggestedSteps()),
                output.model(),
                output.promptVersion(),
                output.inputTokens(),
                output.outputTokens());
    }

    /** 校验问题或步骤列表的条数及单项长度，并移除每项首尾空白。 */
    private static List<String> checkedItems(List<String> items) {
        if (items == null || items.isEmpty() || items.size() > 10) {
            throw new TicketAiModelPort.ModelFailure(
                    TicketAiModelPort.FailureKind.INVALID_OUTPUT, null);
        }
        List<String> checked = new ArrayList<>();
        for (String item : items) {
            if (item == null || item.isBlank() || item.strip().length() > 500) {
                throw new TicketAiModelPort.ModelFailure(
                        TicketAiModelPort.FailureKind.INVALID_OUTPUT, null);
            }
            checked.add(item.strip());
        }
        return List.copyOf(checked);
    }

    /** 将数据库历史行转换为 API 结果，恢复五项输入快照及 UTC 创建时间。 */
    private TicketAiSuggestionResult toResult(
            TicketAiSuggestionRow row,
            String ticketNo) {
        return new TicketAiSuggestionResult(
                row.getId(),
                ticketNo,
                row.getRequestId(),
                new TicketAiInputSnapshotResult(
                        row.getInputTitle(),
                        row.getInputDescription(),
                        row.getInputCategory(),
                        row.getInputPriority(),
                        row.getInputStatus()),
                row.getSummary(),
                readItems(row.getQuestionsJson()),
                readItems(row.getSuggestedStepsJson()),
                row.getModel(),
                row.getPromptVersion(),
                row.getInputTokens(),
                row.getOutputTokens(),
                row.getCreatedAt().atOffset(ZoneOffset.UTC),
                suggestionSourceMapper.selectBySuggestionId(row.getId()).stream()
                        .map(source -> new TicketAiSuggestionResult.Source(
                                "[S" + source.getSequenceNo() + "]",
                                source.getTicketNo(), source.getTitle(),
                                source.getProcessExcerpt()))
                        .toList());
    }

    /** 解析历史中保存的 JSON 字符串数组；历史损坏时抛出内部数据错误。 */
    private List<String> readItems(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException malformedHistory) {
            throw new IllegalStateException("AI 建议历史格式错误", malformedHistory);
        }
    }

    /** 对五项实际发送内容计算 SHA-256，用于判断同一请求标识是否对应相同输入。 */
    private static String inputSha256(TicketAiModelPort.Input input) {
        return inputSha256(input, null);
    }

    /** v4 保持原指纹；v5 在相同五项后加入版本域与预览来源摘要。 */
    private static String inputSha256(TicketAiModelPort.Input input, String sourceDigest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, input.title());
            updateDigest(digest, input.description());
            updateDigest(digest, input.category());
            updateDigest(digest, input.priority());
            updateDigest(digest, input.status());
            if (sourceDigest != null) {
                updateDigest(digest, "flowdesk-ai-input-v5");
                updateDigest(digest, sourceDigest);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", impossible);
        }
    }

    /** 按 UTF-8 字节长度及内容更新摘要，避免不同字段拼接后产生相同字节序列。 */
    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** 将时钟时刻转换为 UTC 的 LocalDateTime，统一写入数据库时间列。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
