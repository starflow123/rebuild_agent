package com.rebuild.agent.model;

/**
 * 重构步骤：计划中的单个原子操作
 * 每个步骤执行后都会触发测试验证
 */
public class RefactorStep {

    /** 步骤序号 */
    private int stepIndex;

    /** 步骤描述，例如 "将 createOrder 方法迁移到 OrderCreator 类" */
    private String description;

    /** 操作类型 */
    private StepType stepType;

    /** 涉及的源文件路径 */
    private String targetFile;

    /** 涉及的类名 */
    private String targetClass;

    /** 涉及的方法名（若适用） */
    private String methodName;

    /** 该步骤的风险等级 */
    private RiskLevel riskLevel;

    /** 执行状态 */
    private StepStatus status = StepStatus.PENDING;

    /** 执行结果描述 */
    private String resultMessage;

    public RefactorStep() {
    }

    public RefactorStep(int stepIndex, String description, StepType stepType) {
        this.stepIndex = stepIndex;
        this.description = description;
        this.stepType = stepType;
    }

    /**
     * 操作类型枚举
     */
    public enum StepType {
        /** 提取新类 */
        EXTRACT_CLASS,
        /** 提取方法 */
        EXTRACT_METHOD,
        /** 迁移方法到新类 */
        MOVE_METHOD,
        /** 迁移字段到新类 */
        MOVE_FIELD,
        /** 重命名 */
        RENAME,
        /** 修改方法签名 */
        CHANGE_SIGNATURE,
        /** 内联 */
        INLINE,
        /** 更新引用方代码 */
        UPDATE_REFERENCE
    }

    /**
     * 步骤执行状态
     */
    public enum StepStatus {
        PENDING, RUNNING, SUCCESS, FAILED, ROLLED_BACK, SKIPPED
    }

    // ---- getters / setters ----

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public StepType getStepType() {
        return stepType;
    }

    public void setStepType(StepType stepType) {
        this.stepType = stepType;
    }

    public String getTargetFile() {
        return targetFile;
    }

    public void setTargetFile(String targetFile) {
        this.targetFile = targetFile;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public void setTargetClass(String targetClass) {
        this.targetClass = targetClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public void setResultMessage(String resultMessage) {
        this.resultMessage = resultMessage;
    }
}
