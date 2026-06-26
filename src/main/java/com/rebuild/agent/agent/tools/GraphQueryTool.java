package com.rebuild.agent.agent.tools;

import com.rebuild.agent.graph.GraphQueryService;
import com.rebuild.agent.model.SubGraph;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 图谱查询工具：供 Agent 调用，从 Neo4j 查询子图和度量指标
 * <p>
 * 第二步 Agent 分析阶段的核心工具，让 LLM 能"看见"代码依赖结构。
 */
@Component
public class GraphQueryTool {

    private static final Logger log = LoggerFactory.getLogger(GraphQueryTool.class);

    private final GraphQueryService graphQueryService;

    public GraphQueryTool(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    /**
     * 查询以指定类为中心的依赖子图
     * @param centerClass 中心类全限定名
     * @param depth 扩展深度（1=直接依赖，2=间接依赖，建议用 2）
     * @return 子图摘要信息（类节点数、边数、涉及的文件列表）
     */
    @Tool("查询代码依赖图谱中以指定类为中心、向外扩展 N 层的子图。返回子图中的类节点、依赖关系和涉及文件。")
    public String querySubGraph(
            @P("中心类的全限定名，例如 com.example.OrderService") String centerClass,
            @P("扩展深度，1 表示直接依赖，2 表示包含间接依赖") int depth) {
        log.info("Agent 调用 querySubGraph: class={}, depth={}", centerClass, depth);
        SubGraph subGraph = graphQueryService.querySubGraph(centerClass, depth);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("子图查询结果（中心类: %s，深度: %d）：%n", centerClass, depth));
        sb.append(String.format("- 类节点数: %d%n", subGraph.getClassNodes().size()));
        sb.append(String.format("- 依赖边数: %d%n", subGraph.getEdges().size()));
        sb.append(String.format("- 涉及文件数: %d%n", subGraph.getInvolvedFiles().size()));
        sb.append("\n类节点列表（按入度降序）:\n");
        for (String cls : subGraph.getClassesByIncomingDegree()) {
            sb.append("  - ").append(cls).append("\n");
        }
        sb.append("\n依赖关系:\n");
        for (var edge : subGraph.getEdges()) {
            sb.append("  - ").append(edge).append("\n");
        }
        return sb.toString();
    }

    /**
     * 计算指定类的度量指标
     */
    @Tool("计算指定类的度量指标：入度、出度、代码行数、圈复杂度、风险等级。用于评估类的复杂度和重构风险。")
    public String computeMetrics(@P("类的全限定名") String className) {
        log.info("Agent 调用 computeMetrics: class={}", className);
        var metrics = graphQueryService.computeMetrics(className);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("类 %s 的度量指标:%n", className));
        sb.append(String.format("- 入度（被依赖数）: %d%n", metrics.getIncomingCount()));
        sb.append(String.format("- 出度（依赖他人数）: %d%n", metrics.getOutgoingCount()));
        sb.append(String.format("- 代码行数: %d%n", metrics.getLineCount()));
        sb.append(String.format("- 圈复杂度: %d%n", metrics.getTotalComplexity()));
        sb.append(String.format("- 风险等级: %s%n", metrics.getRiskLevel()));
        sb.append(String.format("- 风险评分: %d/100%n", metrics.getRiskScore()));
        if (metrics.getDependedBy() != null && !metrics.getDependedBy().isEmpty()) {
            sb.append(String.format("- 被以下类依赖: %s%n", String.join(", ", metrics.getDependedBy())));
        }
        if (metrics.getDependsOn() != null && !metrics.getDependsOn().isEmpty()) {
            sb.append(String.format("- 依赖以下类: %s%n", String.join(", ", metrics.getDependsOn())));
        }
        return sb.toString();
    }

    /**
     * 影响范围分析
     */
    @Tool("分析指定类的影响范围：返回所有直接或间接依赖该类的类列表。用于重构前评估波及范围。")
    public String analyzeImpactScope(@P("目标类的全限定名") String className) {
        log.info("Agent 调用 analyzeImpactScope: class={}", className);
        var impacted = graphQueryService.analyzeImpactScope(className);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("影响范围分析（目标类: %s）:%n", className));
        sb.append(String.format("共 %d 个类受影响:%n", impacted.size()));
        for (int i = 0; i < impacted.size() && i < 50; i++) {
            sb.append("  - ").append(impacted.get(i)).append("\n");
        }
        if (impacted.size() > 50) {
            sb.append(String.format("  ... 及其他 %d 个类%n", impacted.size() - 50));
        }
        return sb.toString();
    }
}
