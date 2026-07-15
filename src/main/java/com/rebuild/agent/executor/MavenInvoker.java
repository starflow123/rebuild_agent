package com.rebuild.agent.executor;

import com.rebuild.agent.model.TestResult;
import org.apache.maven.shared.invoker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MavenInvoker {

    private static final Logger log = LoggerFactory.getLogger(MavenInvoker.class);

    @Value("${rebuild.build.maven-home:}")
    private String mavenHome;

    @Value("${rebuild.build.jdk-home:}")
    private String jdkHome;

    @Value("${rebuild.build.timeout-ms:600000}")
    private long timeoutMs;

    public TestResult runTests(String projectDir) {
        return runBuild(projectDir, "test");
    }

    public TestResult runBuild(String projectDir, String goal) {
        log.info("执行 mvn {}，项目目录: {}", goal, projectDir);
        long startTime = System.currentTimeMillis();

        String resolvedMavenHome = resolveMavenHome();
        log.info("解析到 Maven home: {}", resolvedMavenHome);

        TestResult result = new TestResult();

        if (resolvedMavenHome != null && !resolvedMavenHome.isEmpty()) {
            result = runWithMavenInvokerAPI(projectDir, resolvedMavenHome, goal, startTime);
            if (result.isSuccess()) {
                return result;
            }
            log.warn("Maven Invoker API 执行失败，尝试系统命令方式...");
        }

        result = runWithSystemCommand(projectDir, goal, startTime);
        return result;
    }

    private TestResult runWithMavenInvokerAPI(String projectDir, String mavenHomePath, String goal, long startTime) {
        TestResult result = new TestResult();
        try {
            Invoker invoker = new DefaultInvoker();
            invoker.setMavenHome(new File(mavenHomePath));

            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(new File(projectDir, "pom.xml"));
            request.setGoals(Collections.singletonList(goal));
            request.setBatchMode(true);
            request.addArg("-B");
            request.setShowErrors(true);

            StringWriter outputWriter = new StringWriter();
            request.setOutputHandler(new PrintWriterConsumer(new PrintWriter(outputWriter, true)));

            InvocationResult invocationResult = invoker.execute(request);
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);

            String output = outputWriter.toString();
            result.setRawOutput(output.length() > 5000 ? output.substring(0, 5000) : output);

            parseBuildOutput(output, result, goal);
            result.setSuccess(invocationResult.getExitCode() == 0 && result.getErrors() == 0 && result.getFailures() == 0);

            log.info("mvn {} 完成，耗时 {} ms，成功: {}", goal, duration, result.isSuccess());

        } catch (MavenInvocationException e) {
            log.error("Maven Invoker API 调用失败", e);
            result.setSuccess(false);
            result.setFailureSummary("Maven 调用异常: " + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);
        }
        return result;
    }

    private TestResult runWithSystemCommand(String projectDir, String goal, long startTime) {
        TestResult result = new TestResult();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.directory(new File(projectDir));

            Map<String, String> env = processBuilder.environment();
            if (jdkHome != null && !jdkHome.isEmpty()) {
                env.put("JAVA_HOME", jdkHome);
                env.put("PATH", jdkHome + File.separator + "bin" + File.pathSeparator + env.getOrDefault("PATH", ""));
                log.info("设置 JAVA_HOME: {}", jdkHome);
            }

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                processBuilder.command("cmd", "/c", "mvn", goal, "-B");
            } else {
                processBuilder.command("mvn", goal, "-B");
            }

            Process process = processBuilder.start();

            StringBuilder outputBuilder = new StringBuilder();
            StringBuilder errorBuilder = new StringBuilder();

            Thread outputThread = readStream(process.getInputStream(), outputBuilder);
            Thread errorThread = readStream(process.getErrorStream(), errorBuilder);

            boolean completed = process.waitFor(timeoutMs / 1000, java.util.concurrent.TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                result.setSuccess(false);
                result.setFailureSummary("Maven 执行超时");
                result.setDurationMs(System.currentTimeMillis() - startTime);
                return result;
            }

            outputThread.join(5000);
            errorThread.join(5000);

            String output = outputBuilder.toString() + errorBuilder.toString();
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);
            result.setRawOutput(output.length() > 5000 ? output.substring(0, 5000) : output);

            parseBuildOutput(output, result, goal);

            int exitCode = process.exitValue();
            result.setSuccess(exitCode == 0 && result.getErrors() == 0 && result.getFailures() == 0);

            log.info("mvn {} 完成，退出码: {}，成功: {}", goal, exitCode, result.isSuccess());

        } catch (IOException | InterruptedException e) {
            log.error("系统命令执行失败", e);
            result.setSuccess(false);
            result.setFailureSummary("执行 mvn 命令失败: " + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);
        }
        return result;
    }

    private Thread readStream(InputStream is, StringBuilder builder) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append("\n");
                }
            } catch (IOException e) {
                log.debug("读取输出流失败", e);
            }
        });
        thread.start();
        return thread;
    }

    private String resolveMavenHome() {
        if (mavenHome != null && !mavenHome.isEmpty()) {
            return mavenHome;
        }

        String envMavenHome = System.getenv("MAVEN_HOME");
        if (envMavenHome != null && !envMavenHome.isEmpty()) {
            return envMavenHome;
        }

        String envM2Home = System.getenv("M2_HOME");
        if (envM2Home != null && !envM2Home.isEmpty()) {
            return envM2Home;
        }

        String userHome = System.getProperty("user.home");
        String commonPath = userHome + File.separator + "apache-maven";
        if (new File(commonPath).exists()) {
            return commonPath;
        }

        String programFiles = System.getenv("PROGRAMFILES");
        if (programFiles != null) {
            String mavenPath = programFiles + File.separator + "Apache" + File.separator + "Maven";
            if (new File(mavenPath).exists()) {
                return mavenPath;
            }
        }

        return null;
    }

    private void parseBuildOutput(String output, TestResult result, String goal) {
        if ("test".equals(goal)) {
            parseTestOutput(output, result);
            return;
        }

        if (output.contains("BUILD SUCCESS")) {
            result.setSuccess(true);
        } else if (output.contains("BUILD FAILURE")) {
            result.setSuccess(false);
            Pattern errorPattern = Pattern.compile("\\[ERROR\\]\\s*(.+?)\\n");
            Matcher matcher = errorPattern.matcher(output);
            StringBuilder summaryBuilder = new StringBuilder();
            int errorCount = 0;
            while (matcher.find() && errorCount < 5) {
                summaryBuilder.append(matcher.group(1)).append("\n");
                errorCount++;
            }
            result.setFailureSummary(summaryBuilder.toString().trim());
        }
    }

    private void parseTestOutput(String output, TestResult result) {
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

        Pattern failurePattern = Pattern.compile(
                "([\\w.]+)\\.([\\w]+)\\s*[:：]\\s*(?:\\d+\\s*)?[:：]?\\s*(.+?)(?:\\n|$)");
        Matcher failMatcher = failurePattern.matcher(output);
        StringBuilder summaryBuilder = new StringBuilder();
        int failCount = 0;
        while (failMatcher.find() && failCount < 20) {
            String testClass = failMatcher.group(1);
            String testMethod = failMatcher.group(2);
            String message = failMatcher.group(3).trim();
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