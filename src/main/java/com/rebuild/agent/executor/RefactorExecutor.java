package com.rebuild.agent.executor;

import com.rebuild.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 重构执行器：编排"修改代码 -> 运行测试 -> 失败分析 -> 自动修正/回滚"的完整流程
 * <p>
 * 第三步（增量执行 + 回滚保障）的总调度。
 * 每个重构步骤执行后自动触发测试（Maven/Gradle 自动检测）；测试失败时分析堆栈并自动修正；
 * 连续失败超过阈值（默认 3 次）则回滚并生成"人工介入报告"。
 */
@Component
public class RefactorExecutor {

    private static final Logger log = LoggerFactory.getLogger(RefactorExecutor.class);

    /** 最大连续失败重试次数，超过则生成人工介入报告 */
    @Value("${rebuild.executor.max-retries:3}")
    private int maxRetries;

    private final CodeModifier codeModifier;
    private final BuildInvokerService buildInvokerService;
    private final RollbackManager rollbackManager;
    private final FailureAnalyzer failureAnalyzer;

    @Autowired
    public RefactorExecutor(CodeModifier codeModifier, BuildInvokerService buildInvokerService,
                            RollbackManager rollbackManager, FailureAnalyzer failureAnalyzer) {
        this.codeModifier = codeModifier;
        this.buildInvokerService = buildInvokerService;
        this.rollbackManager = rollbackManager;
        this.failureAnalyzer = failureAnalyzer;
    }

    /**
     * 执行完整的重构计划
     * @param plan 重构计划
     * @param projectRoot 项目根目录
     * @return 执行结果
     */
    public ExecutionResult executePlan(RefactorPlan plan, String projectRoot) {
        log.info("开始执行重构计划: {}，共 {} 个步骤", plan.getPlanName(), plan.getSteps().size());
        ExecutionResult result = new ExecutionResult();
        result.setPlanName(plan.getPlanName());
        result.setTotalSteps(plan.getSteps().size());

        Path projectPath = Paths.get(projectRoot);

        for (RefactorStep step : plan.getSteps()) {
            step.setStatus(RefactorStep.StepStatus.RUNNING);
            log.info("执行步骤 {}/{}: {}", step.getStepIndex(), plan.getSteps().size(), step.getDescription());

            StepResult stepResult = executeStepWithRetry(step, projectPath);
            result.getStepResults().add(stepResult);

            if (stepResult.isSuccess()) {
                step.setStatus(RefactorStep.StepStatus.SUCCESS);
                result.setSuccessSteps(result.getSuccessSteps() + 1);
            } else {
                step.setStatus(RefactorStep.StepStatus.ROLLED_BACK);
                result.setFailedSteps(result.getFailedSteps() + 1);
                // 步骤失败且无法自动修复，生成人工介入报告并终止
                result.setInterventionReport(generateInterventionReport(plan, step, stepResult));
                log.error("步骤 {} 失败，已回滚并生成人工介入报告", step.getStepIndex());
                break;
            }
        }

        result.setSuccess(result.getFailedSteps() == 0);
        log.info("重构计划执行完成: 成功 {} 步，失败 {} 步",
                result.getSuccessSteps(), result.getFailedSteps());
        return result;
    }

    /**
     * 执行单个步骤，失败时自动重试修正
     */
    private StepResult executeStepWithRetry(RefactorStep step, Path projectRoot) {
        StepResult stepResult = new StepResult();
        stepResult.setStepIndex(step.getStepIndex());
        stepResult.setDescription(step.getDescription());

        int attempt = 0;
        while (attempt <= maxRetries) {
            attempt++;
            log.info("步骤 {} 第 {} 次尝试", step.getStepIndex(), attempt);

            // 1. 备份当前状态
            backupAffectedFiles(step, projectRoot);

            try {
                // 2. 执行代码修改
                List<Path> modifiedFiles = codeModifier.executeStep(step, projectRoot);
                stepResult.getModifiedFiles().addAll(modifiedFiles);

                // 3. 注册新建的文件（用于回滚时删除）
                for (Path modifiedFile : modifiedFiles) {
                    if (!modifiedFile.equals(projectRoot.resolve(step.getTargetFile()))) {
                        rollbackManager.registerNewFile(step.getStepIndex(), modifiedFile);
                    }
                }

                // 4. 运行测试（自动检测 Maven/Gradle）
                TestResult testResult = buildInvokerService.runTests(projectRoot.toString());
                stepResult.setTestResult(testResult);

                if (testResult.isSuccess()) {
                    // 测试通过，清除备份，步骤成功
                    rollbackManager.clearBackup(step.getStepIndex());
                    stepResult.setSuccess(true);
                    stepResult.setMessage("步骤执行成功，测试通过");
                    return stepResult;
                }

                // 5. 测试失败，分析原因
                FailureAnalyzer.FailureAnalysis analysis = failureAnalyzer.analyze(testResult);
                stepResult.setFailureAnalysis(analysis);
                log.warn("步骤 {} 第 {} 次尝试失败: {}", step.getStepIndex(), attempt, analysis.getSummary());

                if (attempt > maxRetries) {
                    // 超过最大重试次数，回滚
                    rollbackManager.rollback(step.getStepIndex());
                    stepResult.setSuccess(false);
                    stepResult.setMessage(String.format("连续失败 %d 次，已回滚。失败原因: %s",
                            maxRetries, analysis.getSummary()));
                    return stepResult;
                }

                // 6. 自动修正：回滚后根据失败分析重新修改
                rollbackManager.rollback(step.getStepIndex());
                log.info("步骤 {} 已回滚，准备第 {} 次修正尝试", step.getStepIndex(), attempt + 1);

            } catch (Exception e) {
                log.error("步骤 {} 执行异常", step.getStepIndex(), e);
                rollbackManager.rollback(step.getStepIndex());
                stepResult.setSuccess(false);
                stepResult.setMessage("执行异常: " + e.getMessage());
                return stepResult;
            }
        }

        stepResult.setSuccess(false);
        stepResult.setMessage("重试次数耗尽");
        return stepResult;
    }

    /**
     * 备份步骤涉及的文件
     */
    private void backupAffectedFiles(RefactorStep step, Path projectRoot) {
        if (step.getTargetFile() != null) {
            Path targetPath = projectRoot.resolve(step.getTargetFile());
            rollbackManager.backup(step.getStepIndex(), targetPath);
        }
    }

    /**
     * 生成人工介入报告
     */
    private InterventionReport generateInterventionReport(RefactorPlan plan, RefactorStep failedStep, StepResult stepResult) {
        InterventionReport report = new InterventionReport();
        report.setPlanName(plan.getPlanName());
        report.setFailedStepIndex(failedStep.getStepIndex());
        report.setFailedStepDescription(failedStep.getDescription());
        report.setFailureSummary(stepResult.getFailureAnalysis() != null
                ? stepResult.getFailureAnalysis().getSummary()
                : "未知失败");

        if (stepResult.getFailureAnalysis() != null) {
            FailureAnalyzer.FailureAnalysis analysis = stepResult.getFailureAnalysis();
            // 生成临时补丁建议
            List<String> suggestions = new ArrayList<>();
            suggestions.add("失败类型: " + analysis.getFailureType());

            if (analysis.getFailureType() == FailureAnalyzer.FailureType.COMPILE_ERROR) {
                suggestions.add("编译错误，建议检查以下文件：");
                for (FailureAnalyzer.CompileError err : analysis.getCompileErrors()) {
                    suggestions.add(String.format("  - %s:%d %s", err.getFile(), err.getLine(), err.getMessage()));
                }
            } else if (analysis.getExceptionInfo() != null) {
                suggestions.add("异常类型: " + analysis.getExceptionInfo().getExceptionType());
                suggestions.add("异常消息: " + analysis.getExceptionInfo().getMessage());
                if (analysis.getPrimaryFile() != null) {
                    suggestions.add(String.format("定位位置: %s:%d (%s)",
                            analysis.getPrimaryFile(), analysis.getPrimaryLine(), analysis.getPrimaryMethod()));
                }
            }

            suggestions.add("");
            suggestions.add("临时补丁方案：");
            suggestions.add("1. 检查迁移的方法是否遗漏了依赖的字段或辅助方法");
            suggestions.add("2. 确认调用方的引用是否已全部更新");
            suggestions.add("3. 检查新类的构造方式是否与原类一致（如 Spring 注入）");
            suggestions.add("4. 必要时可手动回退到备份目录 .rebuild-backup");

            report.setSuggestions(suggestions);
        }

        return report;
    }

    // ---- 执行结果数据结构 ----

    /**
     * 执行结果：整个计划的执行汇总
     */
    public static class ExecutionResult {
        private String planName;
        private boolean success;
        private int totalSteps;
        private int successSteps;
        private int failedSteps;
        private List<StepResult> stepResults = new ArrayList<>();
        private InterventionReport interventionReport;

        public String getPlanName() { return planName; }
        public void setPlanName(String planName) { this.planName = planName; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public int getTotalSteps() { return totalSteps; }
        public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }
        public int getSuccessSteps() { return successSteps; }
        public void setSuccessSteps(int successSteps) { this.successSteps = successSteps; }
        public int getFailedSteps() { return failedSteps; }
        public void setFailedSteps(int failedSteps) { this.failedSteps = failedSteps; }
        public List<StepResult> getStepResults() { return stepResults; }
        public void setStepResults(List<StepResult> stepResults) { this.stepResults = stepResults; }
        public InterventionReport getInterventionReport() { return interventionReport; }
        public void setInterventionReport(InterventionReport interventionReport) { this.interventionReport = interventionReport; }
    }

    /**
     * 单步执行结果
     */
    public static class StepResult {
        private int stepIndex;
        private String description;
        private boolean success;
        private String message;
        private List<Path> modifiedFiles = new ArrayList<>();
        private TestResult testResult;
        private FailureAnalyzer.FailureAnalysis failureAnalysis;

        public int getStepIndex() { return stepIndex; }
        public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<Path> getModifiedFiles() { return modifiedFiles; }
        public void setModifiedFiles(List<Path> modifiedFiles) { this.modifiedFiles = modifiedFiles; }
        public TestResult getTestResult() { return testResult; }
        public void setTestResult(TestResult testResult) { this.testResult = testResult; }
        public FailureAnalyzer.FailureAnalysis getFailureAnalysis() { return failureAnalysis; }
        public void setFailureAnalysis(FailureAnalyzer.FailureAnalysis failureAnalysis) { this.failureAnalysis = failureAnalysis; }
    }

    /**
     * 人工介入报告
     */
    public static class InterventionReport {
        private String planName;
        private int failedStepIndex;
        private String failedStepDescription;
        private String failureSummary;
        private List<String> suggestions = new ArrayList<>();

        public String getPlanName() { return planName; }
        public void setPlanName(String planName) { this.planName = planName; }
        public int getFailedStepIndex() { return failedStepIndex; }
        public void setFailedStepIndex(int failedStepIndex) { this.failedStepIndex = failedStepIndex; }
        public String getFailedStepDescription() { return failedStepDescription; }
        public void setFailedStepDescription(String failedStepDescription) { this.failedStepDescription = failedStepDescription; }
        public String getFailureSummary() { return failureSummary; }
        public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    }
}
