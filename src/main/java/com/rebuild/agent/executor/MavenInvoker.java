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
import java.util.HashMap;
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

        String resolvedJdkHome = resolveJdkHome();
        log.info("解析到 JDK home: {}", resolvedJdkHome);

        TestResult result = new TestResult();

        if (resolvedMavenHome != null && !resolvedMavenHome.isEmpty()) {
            result = runWithMavenInvokerAPI(projectDir, resolvedMavenHome, goal, startTime);
            if (result.isSuccess()) {
                return result;
            }
            log.warn("Maven Invoker API 执行失败，尝试系统命令方式...");
        }

        result = runWithSystemCommand(projectDir, resolvedJdkHome, goal, startTime);
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

    private TestResult runWithSystemCommand(String projectDir, String jdkHomePath, String goal, long startTime) {
        TestResult result = new TestResult();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.directory(new File(projectDir));

            Map<String, String> env = processBuilder.environment();
            if (jdkHomePath != null && !jdkHomePath.isEmpty()) {
                env.put("JAVA_HOME", jdkHomePath);
                env.put("PATH", jdkHomePath + File.separator + "bin" + File.pathSeparator + env.getOrDefault("PATH", ""));
                log.info("设置 JAVA_HOME: {}", jdkHomePath);
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
            result.setRawOutput(output.length() > 100000 ? output.substring(0, 100000) : output);

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

    private String resolveJdkHome() {
        if (jdkHome != null && !jdkHome.isEmpty()) {
            return jdkHome;
        }

        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && !envJavaHome.isEmpty()) {
            if (isJava17(envJavaHome)) {
                return envJavaHome;
            }
            log.warn("JAVA_HOME 指向 {}，但不是 JDK17，尝试自动检测", envJavaHome);
        }

        String[] commonPaths = {
                "F:\\JAVA\\JDK\\JDK17",
                "C:\\Program Files\\Java\\jdk-17",
                "C:\\Program Files\\Java\\jdk17",
                System.getProperty("user.home") + "\\jdk-17",
                System.getProperty("user.home") + "\\jdk17"
        };

        for (String path : commonPaths) {
            if (isJava17(path)) {
                log.info("自动检测到 JDK17: {}", path);
                return path;
            }
        }

        log.warn("未找到 JDK17，使用系统默认 JAVA_HOME");
        return envJavaHome;
    }

    private boolean isJava17(String javaHome) {
        if (javaHome == null) {
            return false;
        }
        File javaExe = new File(javaHome + File.separator + "bin" + File.separator + "java.exe");
        if (!javaExe.exists()) {
            javaExe = new File(javaHome + File.separator + "bin" + File.separator + "java");
        }
        if (!javaExe.exists()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe.getAbsolutePath(), "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            return output.toString().contains("17");
        } catch (Exception e) {
            log.debug("检测 JDK 版本失败: {}", javaHome);
            return false;
        }
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
            StringBuilder summaryBuilder = new StringBuilder();
            summaryBuilder.append("构建失败！\n\n");

            Pattern compileErrorPattern = Pattern.compile(
                    "([^\\s]+\\.java):(\\d+):(\\d+):\\s*(error|警告|warning):\\s*(.+?)(?:\\n|$)");
            Matcher compileMatcher = compileErrorPattern.matcher(output);
            int compileErrorCount = 0;
            while (compileMatcher.find() && compileErrorCount < 15) {
                String file = compileMatcher.group(1);
                String line = compileMatcher.group(2);
                String col = compileMatcher.group(3);
                String type = compileMatcher.group(4);
                String message = compileMatcher.group(5).trim();
                summaryBuilder.append(String.format("[%s] %s:%s:%s %s%n", type.toUpperCase(), file, line, col, message));
                compileErrorCount++;
            }

            if (compileErrorCount == 0) {
                Pattern simpleErrorPattern = Pattern.compile(
                        "([^\\s]+\\.java):(\\d+):\\s*(error|警告|warning):\\s*(.+?)(?:\\n|$)");
                Matcher simpleMatcher = simpleErrorPattern.matcher(output);
                while (simpleMatcher.find() && compileErrorCount < 15) {
                    String file = simpleMatcher.group(1);
                    String line = simpleMatcher.group(2);
                    String type = simpleMatcher.group(3);
                    String message = simpleMatcher.group(4).trim();
                    summaryBuilder.append(String.format("[%s] %s:%s %s%n", type.toUpperCase(), file, line, message));
                    compileErrorCount++;
                }
            }

            if (compileErrorCount == 0) {
                Pattern errorPattern = Pattern.compile("\\[ERROR\\]\\s*(.+?)\\n");
                Matcher matcher = errorPattern.matcher(output);
                int errorCount = 0;
                while (matcher.find() && errorCount < 10) {
                    summaryBuilder.append("[ERROR] ").append(matcher.group(1)).append("\n");
                    errorCount++;
                }
            }

            summaryBuilder.append("\n完整输出前10000字符:\n");
            summaryBuilder.append(output.length() > 10000 ? output.substring(0, 10000) : output);

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