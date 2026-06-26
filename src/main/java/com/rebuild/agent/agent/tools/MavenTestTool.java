package com.rebuild.agent.agent.tools;

import com.rebuild.agent.executor.FailureAnalyzer;
import com.rebuild.agent.executor.MavenInvoker;
import com.rebuild.agent.model.TestResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 测试工具：供 Agent 调用，执行 mvn test 并返回结构化结果
 * <p>
 * 第三步（增量执行 + 回滚保障）的测试验证工具。
 */
@Component
public class MavenTestTool {

    private static final Logger log = LoggerFactory.getLogger(MavenTestTool.class);

    private final MavenInvoker mavenInvoker;
    private final FailureAnalyzer failureAnalyzer;

    public MavenTestTool(MavenInvoker mavenInvoker, FailureAnalyzer failureAnalyzer) {
        this.mavenInvoker = mavenInvoker;
        this.failureAnalyzer = failureAnalyzer;
    }

    /**
     * 运行测试
     */
    @Tool("在指定项目目录执行 mvn test，返回测试结果。每次代码修改后都应调用此工具验证。")
    public String runTests(@P("项目根目录路径") String projectDir) {
        log.info("Agent 调用 runTests: project={}", projectDir);
        TestResult result = mavenInvoker.runTests(projectDir);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("测试执行结果:%n"));
        sb.append(String.format("- 成功: %s%n", result.isSuccess() ? "是" : "否"));
        sb.append(String.format("- 总测试数: %d%n", result.getTotalTests()));
        sb.append(String.format("- 通过: %d%n", result.getPassed()));
        sb.append(String.format("- 失败: %d%n", result.getFailures()));
        sb.append(String.format("- 错误: %d%n", result.getErrors()));
        sb.append(String.format("- 跳过: %d%n", result.getSkipped()));
        sb.append(String.format("- 耗时: %d ms%n", result.getDurationMs()));

        if (!result.isSuccess()) {
            // 自动分析失败原因
            FailureAnalyzer.FailureAnalysis analysis = failureAnalyzer.analyze(result);
            sb.append(String.format("%n失败分析:%n"));
            sb.append(String.format("- 失败类型: %s%n", analysis.getFailureType()));
            sb.append(String.format("- 摘要: %s%n", analysis.getSummary()));

            if (analysis.getExceptionInfo() != null) {
                sb.append(String.format("- 异常类型: %s%n", analysis.getExceptionInfo().getExceptionType()));
                sb.append(String.format("- 异常消息: %s%n", analysis.getExceptionInfo().getMessage()));
            }

            if (analysis.getPrimaryFile() != null) {
                sb.append(String.format("- 主要定位: %s:%d (%s)%n",
                        analysis.getPrimaryFile(), analysis.getPrimaryLine(), analysis.getPrimaryMethod()));
            }

            if (!analysis.getCompileErrors().isEmpty()) {
                sb.append(String.format("%n编译错误:%n"));
                for (FailureAnalyzer.CompileError err : analysis.getCompileErrors()) {
                    sb.append(String.format("  - %s:%d %s%n", err.getFile(), err.getLine(), err.getMessage()));
                }
            }

            if (!analysis.getFailedTests().isEmpty()) {
                sb.append(String.format("%n失败用例:%n"));
                for (TestResult.FailedTest ft : analysis.getFailedTests()) {
                    sb.append(String.format("  - %s.%s [%s]: %s%n",
                            ft.getTestClass(), ft.getTestMethod(), ft.getFailureType(), ft.getMessage()));
                }
            }

            sb.append(String.format("%n建议: 根据以上失败原因修正代码后重新运行测试。%n"));
        }

        return sb.toString();
    }
}
