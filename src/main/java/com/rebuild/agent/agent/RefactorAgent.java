package com.rebuild.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 重构 Agent 接口：基于 LangChain4j 的 AI 服务定义
 * <p>
 * 第二步（Agent 分析并生成重构方案）的入口。
 * 通过 AiServices 构建后，LLM 会自动根据用户指令调用注册的工具完成分析。
 */
public interface RefactorAgent {

    /**
     * 与 Agent 对话，触发分析与重构流程
     * @param userMessage 用户指令
     * @return Agent 的回复（包含分析结果、工具调用过程、重构建议）
     */
    @SystemMessage(PromptTemplates.SYSTEM_PROMPT)
    String chat(@UserMessage String userMessage);
}
