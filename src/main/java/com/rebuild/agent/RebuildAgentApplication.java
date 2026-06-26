package com.rebuild.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 手术式重构 Agent 主应用入口
 * <p>
 * 面向遗留 Java 系统的"手术式重构 Agent"：
 * 1. JavaParser 静态分析构建代码依赖图谱
 * 2. Neo4j 嵌入式存储图谱
 * 3. LangChain4j Agent 调用 LLM 分析并生成重构方案
 * 4. 增量执行 + 测试验证 + 自动回滚
 * 5. 生成影响范围 HTML 报告
 */
@SpringBootApplication
public class RebuildAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RebuildAgentApplication.class, args);
    }
}
