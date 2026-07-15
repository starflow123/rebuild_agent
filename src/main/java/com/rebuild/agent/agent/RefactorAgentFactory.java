package com.rebuild.agent.agent;

import com.rebuild.agent.agent.tools.*;
import com.rebuild.agent.config.AgentConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 重构 Agent 工厂：组装 LLM 与工具集，构建可用的 Agent 实例
 * <p>
 * 第二步的核心装配：将 LangChain4j 的 ChatLanguageModel 与所有工具（图谱查询、
 * 上帝类检测、重构计划、代码修改、测试、风险评估）注册到 Agent 中。
 */
@Component
public class RefactorAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(RefactorAgentFactory.class);

    private final AgentConfig agentConfig;
    private final GraphQueryTool graphQueryTool;
    private final GodClassDetectorTool godClassDetectorTool;
    private final RefactorPlanTool refactorPlanTool;
    private final CodeModifyTool codeModifyTool;
    private final BuildTestTool buildTestTool;
    private final RiskAssessTool riskAssessTool;

    @Autowired
    public RefactorAgentFactory(AgentConfig agentConfig,
                                GraphQueryTool graphQueryTool,
                                GodClassDetectorTool godClassDetectorTool,
                                RefactorPlanTool refactorPlanTool,
                                CodeModifyTool codeModifyTool,
                                BuildTestTool buildTestTool,
                                RiskAssessTool riskAssessTool) {
        this.agentConfig = agentConfig;
        this.graphQueryTool = graphQueryTool;
        this.godClassDetectorTool = godClassDetectorTool;
        this.refactorPlanTool = refactorPlanTool;
        this.codeModifyTool = codeModifyTool;
        this.buildTestTool = buildTestTool;
        this.riskAssessTool = riskAssessTool;
    }

    /**
     * 构建重构 Agent
     * @return 配置好 LLM 和工具的 Agent 实例
     */
    public RefactorAgent createAgent() {
        log.info("构建重构 Agent，LLM: {} @ {}", agentConfig.getModelName(), agentConfig.getBaseUrl());

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(agentConfig.getBaseUrl())
                .apiKey(agentConfig.getApiKey())
                .modelName(agentConfig.getModelName())
                .temperature(agentConfig.getTemperature())
                .maxTokens(agentConfig.getMaxTokens())
                .build();

        RefactorAgent agent = AiServices.builder(RefactorAgent.class)
                .chatModel(model)
                .tools(graphQueryTool, godClassDetectorTool, refactorPlanTool,
                        codeModifyTool, buildTestTool, riskAssessTool)
                .maxSequentialToolsInvocations(30)
                .build();

        log.info("重构 Agent 构建完成，已注册 6 个工具，最大工具调用次数: 30");
        return agent;
    }

    /**
     * 检查 LLM 配置是否就绪
     */
    public boolean isLlmConfigured() {
        return agentConfig.getApiKey() != null && !agentConfig.getApiKey().isEmpty();
    }
}
