package com.rebuild.agent.executor;

import com.rebuild.agent.model.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 失败分析器：解析测试失败输出，提取错误根因与定位信息
 * <p>
 * 第三步（增量执行 + 回滚保障）的智能修正环节。
 * Agent 连续失败时，通过本服务分析报错堆栈，定位到具体类/方法/行号，
 * 为自动修正代码或生成"人工介入报告"提供依据。
 */
@Component
public class FailureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalyzer.class);

    /** 匹配堆栈中的源码行：at com.example.OrderService.create(OrderService.java:125) */
    private static final Pattern STACK_TRACE_PATTERN =
            Pattern.compile("at\\s+([\\w.$]+)\\.([\\w$]+)\\(([\\w.]+):(\\d+)\\)");

    /** 匹配异常类型与消息：java.lang.NullPointerException: message */
    private static final Pattern EXCEPTION_PATTERN =
            Pattern.compile("([\\w.]+(?:Exception|Error|Throwable)):\\s*(.*)");

    /** 匹配编译错误：F:\path\File.java:123: error: message */
    private static final Pattern COMPILE_ERROR_PATTERN =
            Pattern.compile("([^\\s]+\\.java):(\\d+):(?:(\\d+):)?\\s*(error|警告|warning):\\s*(.+)");

    /**
     * 分析测试结果，提取失败根因
     * @param testResult 测试结果
     * @return 失败分析报告
     */
    public FailureAnalysis analyze(TestResult testResult) {
        FailureAnalysis analysis = new FailureAnalysis();
        analysis.setSuccess(testResult.isSuccess());

        if (testResult.isSuccess()) {
            analysis.setSummary("测试全部通过，无需分析");
            return analysis;
        }

        String output = testResult.getRawOutput() == null ? "" : testResult.getRawOutput();
        log.info("开始分析失败原因，输出长度: {}", output.length());

        // 1. 提取编译错误（优先级最高，编译失败时测试根本无法运行）
        List<CompileError> compileErrors = extractCompileErrors(output);
        if (!compileErrors.isEmpty()) {
            analysis.setFailureType(FailureType.COMPILE_ERROR);
            analysis.setCompileErrors(compileErrors);
            analysis.setSummary(String.format("编译失败，共 %d 处错误，首处: %s",
                    compileErrors.size(), compileErrors.get(0).getMessage()));
            analysis.setPrimaryLocation(compileErrors.get(0).getFile(),
                    compileErrors.get(0).getLine(), "");
            return analysis;
        }

        // 2. 提取异常类型
        ExceptionInfo exceptionInfo = extractException(output);
        if (exceptionInfo != null) {
            analysis.setExceptionInfo(exceptionInfo);
            analysis.setFailureType(FailureType.RUNTIME_EXCEPTION);
        }

        // 3. 提取堆栈中的源码定位
        List<StackLocation> stackLocations = extractStackLocations(output);
        analysis.setStackLocations(stackLocations);

        // 4. 确定主要失败位置（第一个非测试框架的源码位置）
        for (StackLocation loc : stackLocations) {
            if (!isTestFramework(loc.getClassName())) {
                analysis.setPrimaryLocation(loc.getFile(), loc.getLine(), loc.getMethodName());
                break;
            }
        }

        // 5. 汇总失败用例
        analysis.setFailedTests(testResult.getFailedTests());

        // 6. 生成摘要
        if (exceptionInfo != null) {
            analysis.setSummary(String.format("%s: %s",
                    exceptionInfo.getExceptionType(), exceptionInfo.getMessage()));
        } else if (!testResult.getFailedTests().isEmpty()) {
            TestResult.FailedTest first = testResult.getFailedTests().get(0);
            analysis.setSummary(String.format("测试失败: %s.%s - %s",
                    first.getTestClass(), first.getTestMethod(), first.getMessage()));
        } else {
            analysis.setSummary("测试失败，原因未知，请查看原始输出");
        }

        log.info("失败分析完成: type={}, summary={}", analysis.getFailureType(), analysis.getSummary());
        return analysis;
    }

    /**
     * 提取编译错误
     */
    private List<CompileError> extractCompileErrors(String output) {
        List<CompileError> errors = new ArrayList<>();
        Matcher matcher = COMPILE_ERROR_PATTERN.matcher(output);
        while (matcher.find() && errors.size() < 10) {
            CompileError error = new CompileError();
            error.setFile(matcher.group(1));
            error.setLine(Integer.parseInt(matcher.group(2)));
            if (matcher.group(3) != null) {
                error.setColumn(Integer.parseInt(matcher.group(3)));
            } else {
                error.setColumn(0);
            }
            error.setMessage(matcher.group(5).trim());
            errors.add(error);
        }
        return errors;
    }

    /**
     * 提取异常信息
     */
    private ExceptionInfo extractException(String output) {
        Matcher matcher = EXCEPTION_PATTERN.matcher(output);
        while (matcher.find()) {
            String exceptionType = matcher.group(1);
            String message = matcher.group(2).trim();
            // 过滤掉堆栈中重复出现的异常行，只取第一个
            ExceptionInfo info = new ExceptionInfo();
            info.setExceptionType(exceptionType);
            info.setMessage(message);
            return info;
        }
        return null;
    }

    /**
     * 提取堆栈中的源码定位
     */
    private List<StackLocation> extractStackLocations(String output) {
        List<StackLocation> locations = new ArrayList<>();
        Matcher matcher = STACK_TRACE_PATTERN.matcher(output);
        while (matcher.find() && locations.size() < 30) {
            StackLocation loc = new StackLocation();
            loc.setClassName(matcher.group(1));
            loc.setMethodName(matcher.group(2));
            loc.setFile(matcher.group(3));
            loc.setLine(Integer.parseInt(matcher.group(4)));
            locations.add(loc);
        }
        return locations;
    }

    /**
     * 判断是否为测试框架类（junit/testng/mockito 等）
     */
    private boolean isTestFramework(String className) {
        return className.startsWith("org.junit.")
                || className.startsWith("org.testng.")
                || className.startsWith("org.mockito.")
                || className.startsWith("junit.")
                || className.startsWith("java.lang.reflect.")
                || className.contains("sun.reflect");
    }

    // ---- 分析报告数据结构 ----

    /**
     * 失败类型
     */
    public enum FailureType {
        COMPILE_ERROR, RUNTIME_EXCEPTION, ASSERTION_FAILURE, UNKNOWN
    }

    /**
     * 失败分析报告
     */
    public static class FailureAnalysis {
        private boolean success;
        private FailureType failureType;
        private String summary;
        private ExceptionInfo exceptionInfo;
        private List<StackLocation> stackLocations = new ArrayList<>();
        private List<CompileError> compileErrors = new ArrayList<>();
        private List<TestResult.FailedTest> failedTests = new ArrayList<>();
        /** 主要失败位置 */
        private String primaryFile;
        private int primaryLine;
        private String primaryMethod;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public FailureType getFailureType() { return failureType; }
        public void setFailureType(FailureType failureType) { this.failureType = failureType; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public ExceptionInfo getExceptionInfo() { return exceptionInfo; }
        public void setExceptionInfo(ExceptionInfo exceptionInfo) { this.exceptionInfo = exceptionInfo; }
        public List<StackLocation> getStackLocations() { return stackLocations; }
        public void setStackLocations(List<StackLocation> stackLocations) { this.stackLocations = stackLocations; }
        public List<CompileError> getCompileErrors() { return compileErrors; }
        public void setCompileErrors(List<CompileError> compileErrors) { this.compileErrors = compileErrors; }
        public List<TestResult.FailedTest> getFailedTests() { return failedTests; }
        public void setFailedTests(List<TestResult.FailedTest> failedTests) { this.failedTests = failedTests; }
        public String getPrimaryFile() { return primaryFile; }
        public int getPrimaryLine() { return primaryLine; }
        public String getPrimaryMethod() { return primaryMethod; }
        public void setPrimaryLocation(String file, int line, String method) {
            this.primaryFile = file;
            this.primaryLine = line;
            this.primaryMethod = method;
        }
    }

    public static class ExceptionInfo {
        private String exceptionType;
        private String message;
        public String getExceptionType() { return exceptionType; }
        public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class StackLocation {
        private String className;
        private String methodName;
        private String file;
        private int line;
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
    }

    public static class CompileError {
        private String file;
        private int line;
        private int column;
        private String message;
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
        public int getColumn() { return column; }
        public void setColumn(int column) { this.column = column; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
