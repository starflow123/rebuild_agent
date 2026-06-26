package com.rebuild.agent.executor;

import com.rebuild.agent.model.TestResult;
import org.apache.maven.shared.invoker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven 调用器：通过 Maven Invoker API 触发 mvn test
 * <p>
 * 第三步（增量执行 + 回滚保障）的测试验证环节。
 * 每次代码修改后调用 runTests 执行测试，返回结构化的 TestResult。
 */
@Component
public class MavenInvoker {

    private static final Logger log = LoggerFactory.getLogger(MavenInvoker.class);

    /** Maven home 目录，从配置读取 */
    @Value("${rebuild.maven.home:}")
    private String mavenHome;

    /** Maven 仓库地址（可选，用于加速依赖下载） */
    @Value("${rebuild.maven.repo:}")
    private String mavenRepo;

    /** 测试超时时间（毫秒），默认 10 分钟 */
    @Value("${rebuild.maven.timeout-ms:600000}")
    private long timeoutMs;

    /**
     * 在指定项目目录执行 mvn test
     * @param projectDir 项目根目录
     * @return 测试执行结果
     */
    public TestResult runTests(String projectDir) {
        log.info("执行 mvn test，项目目录: {}", projectDir);
        long startTime = System.currentTimeMillis();

        Invoker invoker = new DefaultInvoker();
        // 设置 Maven home
        if (mavenHome != null && !mavenHome.isEmpty()) {
            invoker.setMavenHome(new File(mavenHome));
        }

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(new File(projectDir, "pom.xml"));
        request.setGoals(Collections.singletonList("test"));
        // 离线模式可选
        request.setBatchMode(true);
        // 不显示颜色，便于解析输出
        request.addArg("-B");

        // 超时设置
        request.setShowErrors(true);

        // 捕获输出
        StringWriter outputWriter = new StringWriter();
        request.setOutputHandler(new PrintWriterConsumer(new PrintWriter(outputWriter, true)));

        TestResult result = new TestResult();
        try {
            InvocationResult invocationResult = invoker.execute(request);
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);

            String output = outputWriter.toString();
            result.setRawOutput(output.length() > 5000 ? output.substring(0, 5000) : output);

            // 解析测试结果
            parseTestOutput(output, result);

            // Maven 退出码 0 表示成功
            result.setSuccess(invocationResult.getExitCode() == 0 && result.getErrors() == 0 && result.getFailures() == 0);

            log.info("mvn test 完成，耗时 {} ms，成功: {}，通过: {}，失败: {}，错误: {}",
                    duration, result.isSuccess(), result.getPassed(), result.getFailures(), result.getErrors());

        } catch (MavenInvocationException e) {
            log.error("调用 Maven 失败", e);
            result.setSuccess(false);
            result.setFailureSummary("Maven 调用异常: " + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);
        }

        return result;
    }

    /**
     * 解析 Maven surefire 测试输出，提取测试统计与失败详情
     * 典型输出格式：
     *   Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
     *   Tests run: 10, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2.345 s <<< FAILURE! - in com.example.OrderTest
     *   OrderTest.testCreate:45 expected:<true> but was:<false>
     */
    private void parseTestOutput(String output, TestResult result) {
        // 匹配汇总行：Tests run: X, Failures: Y, Errors: Z, Skipped: W
        Pattern summaryPattern = Pattern.compile(
                "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
        Matcher matcher = summaryPattern.matcher(output);

        int totalRun = 0, totalFailures = 0, totalErrors = 0, totalSkipped = 0;
        boolean foundSummary = false;
        while (matcher.find()) {
            totalRun = Math.max(totalRun, Integer.parseInt(matcher.group(1)));
            totalFailures = Math.max(totalFailures, Integer.parseInt(matcher.group(2)));
            totalErrors = Math.max(totalErrors, Integer.parseInt(matcher.group(3)));
            totalSkipped = Math.max(totalSkipped, Integer.parseInt(matcher.group(4)));
            foundSummary = true;
        }

        if (foundSummary) {
            result.setTotalTests(totalRun);
            result.setFailures(totalFailures);
            result.setErrors(totalErrors);
            result.setSkipped(totalSkipped);
            result.setPassed(totalRun - totalFailures - totalErrors - totalSkipped);
        }

        // 匹配失败用例详情：TestClass.testMethod:line message
        Pattern failurePattern = Pattern.compile(
                "([\\w.]+)\\.([\\w]+)\\s*[:：]\\s*(?:\\d+\\s*)?[:：]?\\s*(.+?)(?:\\n|$)");
        Matcher failMatcher = failurePattern.matcher(output);
        StringBuilder summaryBuilder = new StringBuilder();
        int failCount = 0;
        while (failMatcher.find() && failCount < 20) {
            String testClass = failMatcher.group(1);
            String testMethod = failMatcher.group(2);
            String message = failMatcher.group(3).trim();
            // 过滤掉汇总行误匹配
            if (testClass.contains("Tests") || testClass.contains("run")) {
                continue;
            }
            String failureType = message.contains("ERROR") || message.contains("Exception") ? "ERROR" : "FAILURE";
            result.getFailedTests().add(new TestResult.FailedTest(testClass, testMethod, failureType, message));
            summaryBuilder.append(String.format("- %s.%s: %s%n", testClass, testMethod, message));
            failCount++;
        }

        if (totalFailures + totalErrors > 0) {
            result.setFailureSummary(summaryBuilder.length() > 0 ? summaryBuilder.toString() : "测试失败，详见原始输出");
        }
    }

    /**
     * 将输出行转发到 PrintWriter 的消费者
     */
    private static class PrintWriterConsumer implements InvocationOutputHandler {
        private final PrintWriter writer;

        PrintWriterConsumer(PrintWriter writer) {
            this.writer = writer;
        }

        @Override
        public void consumeLine(String line) {
            writer.println(line);
        }
    }
}
