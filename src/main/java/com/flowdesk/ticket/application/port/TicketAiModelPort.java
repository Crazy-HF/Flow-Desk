package com.flowdesk.ticket.application.port;

import java.util.List;

/**
 * 模型调用端口
 * Input 只有已确认的五项白名单字段，避免把整张 TicketDetailResult、附件或人员信息误传给模型。
 * 端口由后续 infrastructure 中的 DeepSeek 适配器实现；
 * ServiceImpl 只依赖此接口。Output 仍要在服务层校验，不能因“适配器已经解析”就直接保存
 */
public interface TicketAiModelPort {

    /** 使用白名单中的五项输入调用模型；失败时抛出可分类的 ModelFailure。 */
    Output generate(Input input);

    /** 唯一允许送往模型的五项内容。 */
    record Input(
            String title,
            String description,
            String category,
            String priority,
            String status,
            List<Source> sources) {

        /** 无召回时保留原 v4 的五项输入调用方式。 */
        public Input(String title, String description, String category,
                     String priority, String status) {
            this(title, description, category, priority, status, List.of());
        }

        public Input {
            sources = List.copyOf(sources);
        }
    }

    /** 模型只接收标签、标题和摘录；内部 ID 与真实工单号留在服务端。 */
    record Source(String label, String title, String processExcerpt) {
    }

    /** 适配器解析后的结果；应用服务仍须校验非空、条数和长度。 */
    record Output(
            String summary,
            List<String> questions,
            List<String> suggestedSteps,
            String model,
            String promptVersion,
            Integer inputTokens,
            Integer outputTokens) {
    }

    /** 供应商调用失败、超时或响应结构无效的稳定分类。 */
    enum FailureKind {
        TIMEOUT,
        PROVIDER_ERROR,
        INVALID_OUTPUT
    }

    /** 对外只暴露可分类的失败，不携带供应商原始响应或工单正文。 */
    final class ModelFailure extends RuntimeException {
        private final FailureKind kind;

        /** 保存稳定失败类别和原始原因；原始供应商内容不进入 API 响应。 */
        public ModelFailure(FailureKind kind, Throwable cause) {
            super(kind.name(), cause);
            this.kind = kind;
        }

        /** 返回供应用服务映射 HTTP 状态和业务码的失败类别。 */
        public FailureKind kind() {
            return kind;
        }
    }
}
