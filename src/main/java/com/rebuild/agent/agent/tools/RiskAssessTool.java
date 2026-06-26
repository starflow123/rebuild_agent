package com.rebuild.agent.agent.tools;

import com.rebuild.agent.graph.GraphQueryService;
import com.rebuild.agent.model.GraphMetrics;
import com.rebuild.agent.model.RiskLevel;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 风险评估工具：供 Agent 调用，评估重构步骤的风险等级
 * <p>
 * 第二步 Agent 分析阶段的风险评估工具，帮助 LLM 判断重构的安全性。
 */
@Component
public class RiskAssessTool {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessTool.class);

    private final GraphQueryService graphQueryService;

    public RiskAssessTool(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    /**
     * 评估指定类的重构风险
     */
    @Tool("评估指定类的重构风险等级。基于入度、出度、代码行数、复杂度综合计算风险评分（0-100）。")
    public String assessRisk(@P("类的全限定名") String className) {
        log.info("Agent 调用 assessRisk: class={}", className);
        GraphMetrics metrics = graphQueryService.computeMetrics(className);

        int riskScore = metrics.getRiskScore();
        RiskLevel level = metrics.getRiskLevel();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("风险评估结果（类: %s）:%n", className));
        sb.append(String.format("- 风险评分: %d/100%n", riskScore));
        sb.append(String.format("- 风险等级: %s（%s）%n", level, level.getDescription()));
        sb.append(String.format("- 入度（被依赖数）: %d%n", metrics.getIncomingCount()));
        sb.append(String.format("- 出度（依赖他人数）: %d%n", metrics.getOutgoingCount()));
        sb.append(String.format("- 代码行数: %d%n", metrics.getLineCount()));
        sb.append(String.format("- 圈复杂度: %d%n%n", metrics.getTotalComplexity()));

        // 风险解读
        sb.append("风险解读:%n");
        if (level == RiskLevel.LOW) {
            sb.append("- 低风险：该类依赖关系简单，重构影响面小，可放心修改。%n");
        } else if (level == RiskLevel.MEDIUM) {
            sb.append("- 中风险：该类有一定数量的依赖者，重构需谨慎，确保调用方引用同步更新。%n");
            sb.append("- 建议：分步迁移，每步后运行测试验证。%n");
        } else {
            sb.append("- 高风险：该类被大量类依赖，是核心 API，改动影响面大。%n");
            sb.append("- 建议：保持原类作为门面（Facade），新类作为委托，逐步迁移调用方。%n");
            sb.append("- 必须确保完整的影响范围分析后再动手。%n");
        }

        // 影响范围
        var impacted = graphQueryService.analyzeImpactScope(className);
        sb.append(String.format("%n影响范围: %d 个类直接或间接依赖此类%n", impacted.size()));
        if (impacted.size() > 20) {
            sb.append("警告：影响范围较大，建议采用渐进式重构策略。%n");
        }

        return sb.toString().replace("%n", "\n");
    }

    /**
     * 比较两个类的风险，帮助决定重构优先级
     */
    @Tool("比较两个类的重构风险，帮助决定重构优先级。返回风险评分对比。")
    public String compareRisk(
            @P("第一个类的全限定名") String class1,
            @P("第二个类的全限定名") String class2) {
        log.info("Agent 调用 compareRisk: {} vs {}", class1, class2);
        GraphMetrics m1 = graphQueryService.computeMetrics(class1);
        GraphMetrics m2 = graphQueryService.computeMetrics(class2);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("风险对比:%n"));
        sb.append(String.format("%-50s %10s %10s%n", "指标", class1, class2));
        sb.append("-".repeat(80)).append("\n");
        sb.append(String.format("%-50s %10d %10d%n", "入度", m1.getIncomingCount(), m2.getIncomingCount()));
        sb.append(String.format("%-50s %10d %10d%n", "出度", m1.getOutgoingCount(), m2.getOutgoingCount()));
        sb.append(String.format("%-50s %10d %10d%n", "代码行数", m1.getLineCount(), m2.getLineCount()));
        sb.append(String.format("%-50s %10d %10d%n", "圈复杂度", m1.getTotalComplexity(), m2.getTotalComplexity()));
        sb.append(String.format("%-50s %10d %10d%n", "风险评分", m1.getRiskScore(), m2.getRiskScore()));
        sb.append(String.format("%-50s %10s %10s%n", "风险等级", m1.getRiskLevel(), m2.getRiskLevel()));

        sb.append(String.format("%n建议: 优先重构风险评分较低（%s）的类。%n",
                m1.getRiskScore() <= m2.getRiskScore() ? class1 : class2));
        return sb.toString();
    }
}
