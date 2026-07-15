package com.rebuild.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 配置属性：LLM 连接参数
 * <p>
 * 支持所有 OpenAI 兼容接口的 LLM 服务，配置示例：
 * <p>
 * <strong>通义千问（默认）</strong>
 * <pre>
 * base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 * model-name: qwen-plus
 * </pre>
 * <p>
 * <strong>DeepSeek</strong>
 * <pre>
 * base-url: https://api.deepseek.com/v1
 * model-name: deepseek-chat
 * </pre>
 * <p>
 * <strong>Claude（Anthropic）</strong>
 * <pre>
 * base-url: https://api.anthropic.com/v1
 * model-name: claude-3-sonnet-20240229
 * </pre>
 * <p>
 * <strong>OpenAI</strong>
 * <pre>
 * base-url: https://api.openai.com/v1
 * model-name: gpt-4o
 * </pre>
 * <p>
 * <strong>Ollama（本地部署）</strong>
 * <pre>
 * base-url: http://localhost:11434/v1
 * api-key: ollama
 * model-name: llama3.3:70b
 * </pre>
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

    /** 验证模式：test / compile / skip */
    @Value("${rebuild.executor.test-mode:test}")
    private String testMode;

    /** 最大重试次数 */
    @Value("${rebuild.executor.max-retries:3}")
    private int maxRetries;

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

    public String getTestMode() {
        return testMode;
    }

    public int getMaxRetries() {
        return maxRetries;
    }
}
