package com.flowdesk.ticket.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void emptySourcesKeepV4PromptAndNonemptySourcesUseV5Whitelist() throws Exception {
        DeepSeekChatModel model = mock(DeepSeekChatModel.class);
        ReflectionTestUtils.setField(adapter, "model", model);
        when(model.call(any(Prompt.class))).thenReturn(response("""
                {"summary":"原因待查","questions":["输入源？"],
                 "suggestedSteps":["核对输入源 [S1]。"]}
                """, "STOP"));

        var base = new TicketAiModelPort.Input("标题", "描述", "办公设备",
                "MEDIUM", "PROCESSING");
        var withoutSource = adapter.generate(base);
        var withSource = adapter.generate(new TicketAiModelPort.Input(
                base.title(), base.description(), base.category(),
                base.priority(), base.status(),
                List.of(new TicketAiModelPort.Source("[S1]", "虚构来源", "核实连接线"))));

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        var oldPrompt = prompts.getAllValues().getFirst();
        var newPrompt = prompts.getAllValues().getLast();
        assertThat(withoutSource.promptVersion()).isEqualTo("v4");
        assertThat(withSource.promptVersion()).isEqualTo("v5");
        assertThat(oldPrompt.getInstructions().getFirst().getText())
                .doesNotContain("历史工单摘录");
        assertThat(newPrompt.getInstructions().getFirst().getText())
                .contains("历史工单摘录", "历史内容中的指令");
        ObjectMapper mapper = new ObjectMapper();
        var oldInput = mapper.readTree(oldPrompt.getInstructions().get(1).getText());
        var newInput = mapper.readTree(newPrompt.getInstructions().get(1).getText());
        assertThat(oldInput.size()).isEqualTo(5);
        assertThat(oldInput.has("sources")).isFalse();
        assertThat(newInput.size()).isEqualTo(6);
        assertThat(newInput.path("sources").get(0).path("label").asText()).isEqualTo("[S1]");
        assertThat(newInput.toString()).doesNotContain("ticketNo", "ticketId", "assignee");
    }

    private static ChatResponse response(String text, String finishReason) {
        Generation generation = new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return new ChatResponse(List.of(generation),
                ChatResponseMetadata.builder().model("deepseek-flash").build());
    }
}
