package com.rebuild.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 配置属性：LLM 连接参数
 * <p>
 * 支持通义千问、Claude、OpenAI 等兼容 OpenAI 接口的 LLM 服务。
 */
@Configuration
public class AgentConfig {

    /** LLM API 的 Base URL（通义千问/Claude/OpenAI 兼容接口） */
    @Value("${rebuild.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    /** API Key */
    @Value("${rebuild.llm.api-key:}")
    private String apiKey;

    /** 模型名称，例如 qwen-plus / claude-3-sonnet / gpt-4o */
    @Value("${rebuild.llm.model-name:qwen-plus}")
    private String modelName;

    /** 温度参数，0-1，越低越确定 */
    @Value("${rebuild.llm.temperature:0.3}")
    private double temperature;

    /** 最大 token 数 */
    @Value("${rebuild.llm.max-tokens:4096}")
    private int maxTokens;

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }
}
