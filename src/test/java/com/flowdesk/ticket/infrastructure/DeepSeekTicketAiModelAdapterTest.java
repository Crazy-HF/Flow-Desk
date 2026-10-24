package com.flowdesk.ticket.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 只验证响应解析，不发起 DeepSeek 请求。 */
class DeepSeekTicketAiModelAdapterTest {

    private final DeepSeekTicketAiModelAdapter adapter =
            new DeepSeekTicketAiModelAdapter("test-key", new ObjectMapper());

    @Test
    void extractsOnlyRequiredFieldsWhenModelAddsAnExtraField() {
        String json = """
                {"summary":"原因待查", "questions":["输入源是否正确？"],
                 "suggestedSteps":["核对输入源。"], "extra":"不会保存"}
                """;

        TicketAiModelPort.Output output = ReflectionTestUtils.invokeMethod(
                adapter, "parseResponse", response(json, "STOP"));

        assertThat(output.summary()).isEqualTo("原因待查");
        assertThat(output.questions()).containsExactly("输入源是否正确？");
        assertThat(output.suggestedSteps()).containsExactly("核对输入源。");
        assertThat(output.promptVersion()).isEqualTo("v4");
    }

    @Test
    void stillRejectsMissingFieldsAndTruncatedResponses() {
        var missing = catchThrowableOfType(
                () -> ReflectionTestUtils.invokeMethod(adapter,
                        "parseResponse", response("{\"summary\":\"原因待查\"}", "STOP")),
                TicketAiModelPort.ModelFailure.class);
        assertThat(missing.kind()).isEqualTo(TicketAiModelPort.FailureKind.INVALID_OUTPUT);
        assertThat(missing.getCause()).hasMessage("MISSING_REQUIRED_FIELDS");

        var truncated = catchThrowableOfType(
                () -> ReflectionTestUtils.invokeMethod(adapter,
                        "parseResponse", response("{\"summary\":\"原因待查\"}", "LENGTH")),
                TicketAiModelPort.ModelFailure.class);
        assertThat(truncated.kind()).isEqualTo(TicketAiModelPort.FailureKind.INVALID_OUTPUT);
        assertThat(truncated.getCause()).hasMessage("FINISH_REASON_LENGTH");
    }

    private static ChatResponse response(String text, String finishReason) {
        Generation generation = new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return new ChatResponse(List.of(generation),
                ChatResponseMetadata.builder().model("deepseek-flash").build());
    }
}
