package com.flowdesk.devtools;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.retry.support.RetryTemplate;

public final class DeepSeekManualProbe {

    private DeepSeekManualProbe() {
    }

    public static void main(String[] args) {
        // 必须显式允许本次付费调用；普通编译、测试不会运行这个 main 方法。
        if (!"YES".equals(System.getenv("FLOWDESK_AI_PROBE"))) {
            throw new IllegalStateException("先设置 FLOWDESK_AI_PROBE=YES");
        }

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 DEEPSEEK_API_KEY");
        }

        String fictionalTicket = """
                以下是一张完全虚构的演示工单：
                标题：会议室投影仪无法显示画面
                描述：员工将笔记本连接到三楼会议室投影仪后，投影幕布显示“无信号”。
                      笔记本自身屏幕正常；尚未检查视频线、输入源或显示模式。
                分类：办公设备
                优先级：中
                状态：处理中
                """;

        String instruction = """
                你是企业 IT 工单处理助手。只根据下面这张虚构工单给出建议。
                按“工单摘要”“待核实问题”“建议处理步骤”三个标题输出。
                不要声称已经完成检查、维修或联系用户；未知事项写成待核实。
                建议步骤应具体、可由负责人逐项执行，全文控制在 400 字以内。

                """ + fictionalTicket;

        DeepSeekApi api = DeepSeekApi.builder()
                .apiKey(apiKey)
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model("deepseek-flash")
                .maxTokens(600)
                .build();

        DeepSeekChatModel model = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        long start = System.nanoTime();
        // 整个程序只有这一处模型调用。
        ChatResponse response = model.call(new Prompt(instruction));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("模型：" + response.getMetadata().getModel());
        System.out.println("耗时毫秒：" + elapsedMs);

        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            System.out.println("输入 tokens：" + usage.getPromptTokens());
            System.out.println("输出 tokens：" + usage.getCompletionTokens());
            System.out.println("总 tokens：" + usage.getTotalTokens());
        }

        System.out.println("----- 模型回答 -----");
        System.out.println(response.getResult().getOutput().getText());
    }
}