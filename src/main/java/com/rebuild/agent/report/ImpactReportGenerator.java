package com.rebuild.agent.report;

import com.rebuild.agent.executor.RefactorExecutor;
import com.rebuild.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 影响范围报告生成器：输出 HTML 格式的可视化报告
 * <p>
 * 第四步（生成"影响范围报告"）：用树状图展示改动波及的所有文件，
 * 用颜色标注风险等级，让开发者敢点"合并"按钮。
 */
@Component
public class ImpactReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImpactReportGenerator.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成完整的影响范围报告
     * @param plan 重构计划
     * @param executionResult 执行结果
     * @param impactedFiles 受影响文件列表
     * @param outputPath 输出 HTML 文件路径
     * @return 生成的报告文件路径
     */
    public Path generateReport(RefactorPlan plan,
                               RefactorExecutor.ExecutionResult executionResult,
                               List<String> impactedFiles,
                               String outputPath) {
        log.info("生成影响范围报告: {}", outputPath);

        String html = buildHtml(plan, executionResult, impactedFiles);
        Path reportPath = Paths.get(outputPath);
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, html);
            log.info("报告已生成: {}", reportPath.toAbsolutePath());
            return reportPath;
        } catch (IOException e) {
            log.error("写入报告失败", e);
            throw new RuntimeException("生成报告失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 HTML 报告内容
     */
    private String buildHtml(RefactorPlan plan, RefactorExecutor.ExecutionResult result, List<String> impactedFiles) {
        StringBuilder html = new StringBuilder();
        String timestamp = LocalDateTime.now().format(FORMATTER);

        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>重构影响范围报告 - ").append(escapeHtml(plan.getPlanName())).append("</title>\n");
        html.append("<style>\n");
        appendStyles(html);
        html.append("</style>\n</head>\n<body>\n");

        // 报告头部
        html.append("<div class=\"container\">\n");
        html.append("<h1>重构影响范围报告</h1>\n");
        html.append("<div class=\"meta\">\n");
        html.append("<span>计划: <strong>").append(escapeHtml(plan.getPlanName())).append("</strong></span>\n");
        html.append("<span>目标类: <code>").append(escapeHtml(plan.getTargetClass())).append("</code></span>\n");
        html.append("<span>生成时间: ").append(timestamp).append("</span>\n");
        html.append("<span class=\"status ").append(result.isSuccess() ? "status-success" : "status-failed").append("\">")
            .append(result.isSuccess() ? "执行成功" : "执行失败").append("</span>\n");
        html.append("</div>\n");

        // 概览统计
        appendOverview(html, plan, result, impactedFiles);

        // 拆分建议
        appendSplitSuggestions(html, plan);

        // 执行步骤树状图
        appendStepsTree(html, result);

        // 受影响文件列表
        appendImpactedFiles(html, impactedFiles);

        // 人工介入报告（若有）
        if (result.getInterventionReport() != null) {
            appendInterventionReport(html, result.getInterventionReport());
        }

        // 重构原因
        html.append("<div class=\"section\">\n");
        html.append("<h2>重构原因</h2>\n");
        html.append("<p>").append(escapeHtml(plan.getReason() == null ? "" : plan.getReason())).append("</p>\n");
        html.append("</div>\n");

        html.append("</div>\n</body>\n</html>\n");
        return html.toString();
    }

    private void appendOverview(StringBuilder html, RefactorPlan plan,
                                RefactorExecutor.ExecutionResult result, List<String> impactedFiles) {
        html.append("<div class=\"section overview\">\n");
        html.append("<h2>概览</h2>\n");
        html.append("<div class=\"stats-grid\">\n");
        html.append(statCard("总步骤数", String.valueOf(result.getTotalSteps()), "blue"));
        html.append(statCard("成功步骤", String.valueOf(result.getSuccessSteps()), "green"));
        html.append(statCard("失败步骤", String.valueOf(result.getFailedSteps()), "red"));
        html.append(statCard("受影响文件", String.valueOf(impactedFiles.size()), "orange"));
        html.append(statCard("拆分新类数", String.valueOf(plan.getSplitSuggestions().size()), "purple"));
        html.append(statCard("整体风险", plan.getOverallRisk() == null ? "未评估" : plan.getOverallRisk().getDescription(),
                riskColor(plan.getOverallRisk())));
        html.append("</div>\n");
        html.append("</div>\n");
    }

    private void appendSplitSuggestions(StringBuilder html, RefactorPlan plan) {
        html.append("<div class=\"section\">\n");
        html.append("<h2>拆分建议</h2>\n");
        if (plan.getSplitSuggestions().isEmpty()) {
            html.append("<p class=\"empty\">无拆分建议</p>\n");
        } else {
            for (RefactorPlan.SplitClassSuggestion s : plan.getSplitSuggestions()) {
                html.append("<div class=\"card\">\n");
                html.append("<h3 class=\"card-title\">").append(escapeHtml(s.getNewClassName())).append("</h3>\n");
                html.append("<p><strong>职责:</strong> ").append(escapeHtml(s.getResponsibility())).append("</p>\n");
                if (!s.getMethodsToMove().isEmpty()) {
                    html.append("<p><strong>迁移方法:</strong> ");
                    html.append(String.join(", ", s.getMethodsToMove().stream().map(this::escapeHtml).toList()));
                    html.append("</p>\n");
                }
                if (!s.getFieldsToMove().isEmpty()) {
                    html.append("<p><strong>迁移字段:</strong> ");
                    html.append(String.join(", ", s.getFieldsToMove().stream().map(this::escapeHtml).toList()));
                    html.append("</p>\n");
                }
                html.append("</div>\n");
            }
        }
        html.append("</div>\n");
    }

    private void appendStepsTree(StringBuilder html, RefactorExecutor.ExecutionResult result) {
        html.append("<div class=\"section\">\n");
        html.append("<h2>执行步骤（树状图）</h2>\n");
        html.append("<div class=\"tree\">\n");
        if (result.getStepResults().isEmpty()) {
            html.append("<p class=\"empty\">无执行步骤</p>\n");
        } else {
            for (RefactorExecutor.StepResult step : result.getStepResults()) {
                String statusClass = step.isSuccess() ? "node-success" : "node-failed";
                String riskColor = step.getTestResult() != null && step.getTestResult().isSuccess()
                        ? "#28a745" : "#dc3545";
                html.append("<div class=\"tree-node ").append(statusClass).append("\">\n");
                html.append("<span class=\"node-icon\">").append(step.isSuccess() ? "✓" : "✗").append("</span>\n");
                html.append("<span class=\"node-title\">步骤 ").append(step.getStepIndex())
                    .append(": ").append(escapeHtml(step.getDescription())).append("</span>\n");
                html.append("<span class=\"node-status\" style=\"color:").append(riskColor).append("\">")
                    .append(step.isSuccess() ? "成功" : "失败").append("</span>\n");
                if (step.getMessage() != null) {
                    html.append("<div class=\"node-detail\">").append(escapeHtml(step.getMessage())).append("</div>\n");
                }
                if (step.getTestResult() != null) {
                    TestResult tr = step.getTestResult();
                    html.append("<div class=\"node-detail\">测试: 通过 ").append(tr.getPassed())
                        .append(" / 失败 ").append(tr.getFailures())
                        .append(" / 错误 ").append(tr.getErrors())
                        .append("（").append(tr.getDurationMs()).append(" ms）</div>\n");
                }
                html.append("</div>\n");
            }
        }
        html.append("</div>\n");
        html.append("</div>\n");
    }

    private void appendImpactedFiles(StringBuilder html, List<String> impactedFiles) {
        html.append("<div class=\"section\">\n");
        html.append("<h2>受影响文件（").append(impactedFiles.size()).append("）</h2>\n");
        if (impactedFiles.isEmpty()) {
            html.append("<p class=\"empty\">无受影响文件</p>\n");
        } else {
            html.append("<ul class=\"file-list\">\n");
            for (String file : impactedFiles) {
                html.append("<li><code>").append(escapeHtml(file)).append("</code></li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</div>\n");
    }

    private void appendInterventionReport(StringBuilder html, RefactorExecutor.InterventionReport report) {
        html.append("<div class=\"section intervention\">\n");
        html.append("<h2>⚠ 人工介入报告</h2>\n");
        html.append("<p><strong>失败步骤:</strong> 步骤 ").append(report.getFailedStepIndex())
            .append(" - ").append(escapeHtml(report.getFailedStepDescription())).append("</p>\n");
        html.append("<p><strong>失败原因:</strong> ").append(escapeHtml(report.getFailureSummary())).append("</p>\n");
        html.append("<h3>建议处理方案:</h3>\n<ul>\n");
        for (String suggestion : report.getSuggestions()) {
            html.append("<li>").append(escapeHtml(suggestion)).append("</li>\n");
        }
        html.append("</ul>\n");
        html.append("</div>\n");
    }

    private void appendStyles(StringBuilder html) {
        html.append("""
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                       background: #f5f7fa; color: #333; line-height: 1.6; }
                .container { max-width: 1100px; margin: 0 auto; padding: 24px; }
                h1 { color: #1a1a2e; margin-bottom: 16px; border-bottom: 3px solid #4a6cf7; padding-bottom: 12px; }
                h2 { color: #1a1a2e; margin-bottom: 12px; margin-top: 8px; }
                .meta { display: flex; flex-wrap: wrap; gap: 16px; margin-bottom: 24px;
                        padding: 16px; background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                .meta span { font-size: 14px; }
                .status { padding: 4px 12px; border-radius: 12px; font-weight: 600; }
                .status-success { background: #d4edda; color: #155724; }
                .status-failed { background: #f8d7da; color: #721c24; }
                .section { background: #fff; border-radius: 8px; padding: 20px; margin-bottom: 20px;
                           box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                .overview { border-left: 4px solid #4a6cf7; }
                .stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
                .stat-card { padding: 16px; border-radius: 8px; text-align: center; color: #fff; }
                .stat-card .stat-value { font-size: 28px; font-weight: 700; }
                .stat-card .stat-label { font-size: 13px; opacity: 0.9; }
                .stat-blue { background: linear-gradient(135deg, #4a6cf7, #6a89ff); }
                .stat-green { background: linear-gradient(135deg, #28a745, #48c765); }
                .stat-red { background: linear-gradient(135deg, #dc3545, #f25565); }
                .stat-orange { background: linear-gradient(135deg, #fd7e14, #ff9e34); }
                .stat-purple { background: linear-gradient(135deg, #6f42c1, #8f62e1); }
                .stat-gray { background: linear-gradient(135deg, #6c757d, #8c959d); }
                .stat-yellow { background: linear-gradient(135deg, #ffc107, #ffe127); color: #333; }
                .card { border: 1px solid #e0e0e0; border-radius: 8px; padding: 16px; margin-bottom: 12px; }
                .card-title { color: #4a6cf7; margin-bottom: 8px; }
                .tree { padding-left: 8px; }
                .tree-node { padding: 12px 16px; margin-bottom: 8px; border-radius: 6px; border-left: 4px solid #ccc;
                             background: #fafafa; }
                .tree-node.node-success { border-left-color: #28a745; background: #f0fff4; }
                .tree-node.node-failed { border-left-color: #dc3545; background: #fff5f5; }
                .node-icon { display: inline-block; width: 24px; height: 24px; border-radius: 50%; text-align: center;
                             line-height: 24px; font-weight: 700; margin-right: 8px; }
                .node-success .node-icon { background: #28a745; color: #fff; }
                .node-failed .node-icon { background: #dc3545; color: #fff; }
                .node-title { font-weight: 600; }
                .node-status { float: right; font-weight: 600; }
                .node-detail { margin-top: 8px; padding-left: 32px; font-size: 13px; color: #666; }
                .file-list { list-style: none; }
                .file-list li { padding: 6px 12px; border-bottom: 1px solid #eee; }
                .file-list code { color: #4a6cf7; font-size: 13px; }
                .intervention { border-left: 4px solid #dc3545; background: #fff5f5; }
                .intervention h2 { color: #dc3545; }
                .empty { color: #999; font-style: italic; }
                code { background: #f0f0f0; padding: 2px 6px; border-radius: 3px; font-size: 13px; }
                """);
    }

    private String statCard(String label, String value, String colorClass) {
        return String.format("<div class=\"stat-card stat-%s\"><div class=\"stat-value\">%s</div>"
                + "<div class=\"stat-label\">%s</div></div>\n", colorClass, escapeHtml(value), escapeHtml(label));
    }

    private String riskColor(RiskLevel level) {
        if (level == null) return "gray";
        return switch (level) {
            case LOW -> "green";
            case MEDIUM -> "yellow";
            case HIGH -> "red";
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
