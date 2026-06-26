package com.rebuild.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试执行结果：封装 mvn test 的输出与解析结论
 */
public class TestResult {

    /** 是否构建/测试成功 */
    private boolean success;

    /** 测试运行总数 */
    private int totalTests;

    /** 通过数 */
    private int passed;

    /** 失败数 */
    private int failures;

    /** 错误数（非断言失败，而是异常） */
    private int errors;

    /** 跳过数 */
    private int skipped;

    /** 耗时（毫秒） */
    private long durationMs;

    /** 原始输出日志（截断后） */
    private String rawOutput;

    /** 失败的测试用例列表 */
    private List<FailedTest> failedTests = new ArrayList<>();

    /** 失败堆栈摘要 */
    private String failureSummary;

    public TestResult() {
    }

    /**
     * 失败的测试用例详情
     */
    public static class FailedTest {
        /** 测试类全限定名 */
        private String testClass;
        /** 测试方法名 */
        private String testMethod;
        /** 失败类型：FAILURE / ERROR */
        private String failureType;
        /** 失败原因摘要 */
        private String message;

        public FailedTest() {
        }

        public FailedTest(String testClass, String testMethod, String failureType, String message) {
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.failureType = failureType;
            this.message = message;
        }

        public String getTestClass() {
            return testClass;
        }

        public void setTestClass(String testClass) {
            this.testClass = testClass;
        }

        public String getTestMethod() {
            return testMethod;
        }

        public void setTestMethod(String testMethod) {
            this.testMethod = testMethod;
        }

        public String getFailureType() {
            return failureType;
        }

        public void setFailureType(String failureType) {
            this.failureType = failureType;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    // ---- getters / setters ----

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getTotalTests() {
        return totalTests;
    }

    public void setTotalTests(int totalTests) {
        this.totalTests = totalTests;
    }

    public int getPassed() {
        return passed;
    }

    public void setPassed(int passed) {
        this.passed = passed;
    }

    public int getFailures() {
        return failures;
    }

    public void setFailures(int failures) {
        this.failures = failures;
    }

    public int getErrors() {
        return errors;
    }

    public void setErrors(int errors) {
        this.errors = errors;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public void setRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
    }

    public List<FailedTest> getFailedTests() {
        return failedTests;
    }

    public void setFailedTests(List<FailedTest> failedTests) {
        this.failedTests = failedTests;
    }

    public String getFailureSummary() {
        return failureSummary;
    }

    public void setFailureSummary(String failureSummary) {
        this.failureSummary = failureSummary;
    }
}
