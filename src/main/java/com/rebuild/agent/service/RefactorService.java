package com.rebuild.agent.service;

import com.rebuild.agent.analyzer.DependencyGraphBuilder;
import com.rebuild.agent.agent.RefactorAgent;
import com.rebuild.agent.agent.RefactorAgentFactory;
import com.rebuild.agent.agent.tools.RefactorPlanTool;
import com.rebuild.agent.executor.RefactorExecutor;
import com.rebuild.agent.graph.GraphQueryService;
import com.rebuild.agent.graph.Neo4jGraphStore;
import com.rebuild.agent.model.RefactorPlan;
import com.rebuild.agent.report.ImpactReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 重构主编排服务：串联四个阶段，提供端到端的重构流程
 * <p>
 * 阶段一：静态分析引擎构建依赖图谱并存入 Neo4j
 * 阶段二：Agent 读取子图，调用 LLM 分析并生成重构方案
 * 阶段三：增量执行重构计划，每步测试验证，失败自动修正/回滚
 * 阶段四：生成影响范围 HTML 报告
 */
@Service
public class RefactorService {

    private static final Logger log = LoggerFactory.getLogger(RefactorService.class);

    @Value("${rebuild.report.output-dir:./rebuild-reports}")
    private String reportOutputDir;

    private final DependencyGraphBuilder graphBuilder;
    private final Neo4jGraphStore graphStore;
    private final GraphQueryService graphQueryService;
    private final RefactorAgentFactory agentFactory;
    private final RefactorPlanTool planTool;
    private final RefactorExecutor refactorExecutor;
    private final ImpactReportGenerator reportGenerator;

    @Autowired
    public RefactorService(DependencyGraphBuilder graphBuilder,
                           Neo4jGraphStore graphStore,
                           GraphQueryService graphQueryService,
                           RefactorAgentFactory agentFactory,
                           RefactorPlanTool planTool,
                           RefactorExecutor refactorExecutor,
                           ImpactReportGenerator reportGenerator) {
        this.graphBuilder = graphBuilder;
        this.graphStore = graphStore;
        this.graphQueryService = graphQueryService;
        this.agentFactory = agentFactory;
        this.planTool = planTool;
        this.refactorExecutor = refactorExecutor;
        this.reportGenerator = reportGenerator;
    }

    /**
     * 阶段一：分析项目，构建并持久化依赖图谱
     * @param projectRoot 待分析项目根目录
     * @param includeTest 是否包含测试代码
     * @return 图谱统计信息
     */
    public String analyzeProject(String projectRoot, boolean includeTest) {
        log.info("=== 阶段一：静态分析引擎 ===");
        log.info("分析项目: {} (includeTest={})", projectRoot, includeTest);

        // 构建依赖图谱
        DependencyGraphBuilder.BuildResult buildResult = graphBuilder.buildGraph(projectRoot, includeTest);

        // 持久化到 Neo4j
        graphStore.persistGraph(buildResult);

        var stats = graphStore.getGraphStats();
        String summary = String.format("静态分析完成: %d 个类节点, %d 条依赖边",
                stats.get("classNodes"), stats.get("edges"));
        log.info(summary);
        return summary;
    }

    /**
     * 阶段二：Agent 分析并生成重构方案
     * @param targetClass 目标类全限定名（为空则自动识别上帝类）
     * @return Agent 的分析结果
     */
    public String analyzeWithAgent(String targetClass) {
        log.info("=== 阶段二：Agent 分析与重构方案生成 ===");

        if (!agentFactory.isLlmConfigured()) {
            log.warn("LLM 未配置 API Key，将使用规则引擎进行基础分析");
            return analyzeWithoutLlm(targetClass);
        }

        RefactorAgent agent = agentFactory.createAgent();
        planTool.resetPlan();

        String prompt;
        if (targetClass == null || targetClass.isEmpty()) {
            prompt = "请分析当前项目，识别所有上帝类（依赖数超过 50、代码行数超过 500 的类），"
                    + "选择最严重的一个，制定拆分重构方案。先调用 findGodClasses 工具开始分析。";
        } else {
            prompt = String.format("请分析类 %s 的依赖结构，制定拆分重构方案。"
                    + "先调用 querySubGraph 获取子图，再调用 computeMetrics 获取度量指标，"
                    + "然后制定拆分计划。", targetClass);
        }

        String response = agent.chat(prompt);
        log.info("Agent 分析完成");
        return response;
    }

    /**
     * 无 LLM 时的规则引擎基础分析（降级方案）
     */
    private String analyzeWithoutLlm(String targetClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 规则引擎基础分析（未配置 LLM） ===\n\n");

        if (targetClass == null || targetClass.isEmpty()) {
            sb.append("识别上帝类（依赖阈值=50, 行数阈值=500）:\n");
            var godClasses = graphQueryService.findGodClasses(50, 500);
            if (godClasses.isEmpty()) {
                sb.append("未发现上帝类。\n");
            } else {
                for (int i = 0; i < godClasses.size(); i++) {
                    var cls = godClasses.get(i);
                    sb.append(String.format("%d. %s (入度=%d, 出度=%d, 行数=%d)%n",
                            i + 1, cls.getQualifiedName(), cls.getIncomingDependencyCount(),
                            cls.getOutgoingDependencyCount(), cls.getLineCount()));
                }
                sb.append("\n建议: 配置 LLM API Key 后使用 Agent 进行深度分析和自动重构。\n");
            }
        } else {
            var metrics = graphQueryService.computeMetrics(targetClass);
            sb.append(String.format("类 %s 的度量指标:%n", targetClass));
            sb.append(String.format("- 入度: %d, 出度: %d%n", metrics.getIncomingCount(), metrics.getOutgoingCount()));
            sb.append(String.format("- 行数: %d, 复杂度: %d%n", metrics.getLineCount(), metrics.getTotalComplexity()));
            sb.append(String.format("- 风险等级: %s (评分: %d)%n", metrics.getRiskLevel(), metrics.getRiskScore()));

            var impacted = graphQueryService.analyzeImpactScope(targetClass);
            sb.append(String.format("- 影响范围: %d 个类%n", impacted.size()));
        }
        return sb.toString();
    }

    /**
     * 阶段三：执行重构计划
     * @param projectRoot 项目根目录
     * @return 执行结果
     */
    public RefactorExecutor.ExecutionResult executeRefactor(String projectRoot) {
        log.info("=== 阶段三：增量执行 + 回滚保障 ===");

        RefactorPlan plan = planTool.getCurrentPlan();
        if (plan == null) {
            log.warn("无重构计划可执行，请先调用 analyzeWithAgent 生成计划");
            return null;
        }

        RefactorExecutor.ExecutionResult result = refactorExecutor.executePlan(plan, projectRoot);
        log.info("重构执行完成: 成功 {} 步, 失败 {} 步",
                result.getSuccessSteps(), result.getFailedSteps());
        return result;
    }

    /**
     * 阶段四：生成影响范围报告
     * @param plan 重构计划
     * @param executionResult 执行结果
     * @return 报告文件路径
     */
    public Path generateReport(RefactorPlan plan, RefactorExecutor.ExecutionResult executionResult) {
        log.info("=== 阶段四：生成影响范围报告 ===");

        List<String> impactedFiles = new ArrayList<>();
        if (plan.getTargetClass() != null) {
            impactedFiles = graphQueryService.analyzeImpactScope(plan.getTargetClass());
        }

        String reportPath = Paths.get(reportOutputDir,
                plan.getPlanName().replaceAll("[^\\w\\u4e00-\\u9fa5]", "_") + ".html").toString();

        return reportGenerator.generateReport(plan, executionResult, impactedFiles, reportPath);
    }

    /**
     * 一键执行完整重构流程（分析 -> 计划 -> 执行 -> 报告）
     * @param projectRoot 项目根目录
     * @param targetClass 目标类（可空）
     * @return 完整流程结果摘要
     */
    public String fullRefactor(String projectRoot, String targetClass) {
        StringBuilder summary = new StringBuilder();

        // 阶段一
        summary.append(analyzeProject(projectRoot, false)).append("\n\n");

        // 阶段二
        String analysis = analyzeWithAgent(targetClass);
        summary.append(analysis).append("\n\n");

        // 阶段三
        RefactorExecutor.ExecutionResult execResult = executeRefactor(projectRoot);
        if (execResult != null) {
            summary.append(String.format("执行结果: 成功 %d 步, 失败 %d 步%n%n",
                    execResult.getSuccessSteps(), execResult.getFailedSteps()));

            // 阶段四
            RefactorPlan plan = planTool.getCurrentPlan();
            if (plan != null) {
                Path reportPath = generateReport(plan, execResult);
                summary.append("影响范围报告已生成: ").append(reportPath.toAbsolutePath()).append("\n");
            }
        }

        return summary.toString();
    }
}
