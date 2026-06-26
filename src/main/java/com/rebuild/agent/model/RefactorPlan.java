package com.rebuild.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 重构计划：Agent 分析后生成的完整重构方案
 * 包含目标类、拆分建议、执行步骤序列
 */
public class RefactorPlan {

    /** 计划名称，例如 "OrderService 拆分计划" */
    private String planName;

    /** 待重构的目标类全限定名 */
    private String targetClass;

    /** 重构原因（为何需要重构） */
    private String reason;

    /** 拆分建议：拆分后的新类列表 */
    private List<SplitClassSuggestion> splitSuggestions = new ArrayList<>();

    /** 按顺序执行的重构步骤 */
    private List<RefactorStep> steps = new ArrayList<>();

    /** 整体风险等级 */
    private RiskLevel overallRisk;

    /** 预计影响的文件数量 */
    private int estimatedImpactFiles;

    public RefactorPlan() {
    }

    // ---- getters / setters ----

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public void setTargetClass(String targetClass) {
        this.targetClass = targetClass;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<SplitClassSuggestion> getSplitSuggestions() {
        return splitSuggestions;
    }

    public void setSplitSuggestions(List<SplitClassSuggestion> splitSuggestions) {
        this.splitSuggestions = splitSuggestions;
    }

    public List<RefactorStep> getSteps() {
        return steps;
    }

    public void setSteps(List<RefactorStep> steps) {
        this.steps = steps;
    }

    public RiskLevel getOverallRisk() {
        return overallRisk;
    }

    public void setOverallRisk(RiskLevel overallRisk) {
        this.overallRisk = overallRisk;
    }

    public int getEstimatedImpactFiles() {
        return estimatedImpactFiles;
    }

    public void setEstimatedImpactFiles(int estimatedImpactFiles) {
        this.estimatedImpactFiles = estimatedImpactFiles;
    }

    /**
     * 拆分建议：描述从原类拆分出的新类
     */
    public static class SplitClassSuggestion {
        /** 新类名 */
        private String newClassName;
        /** 新类职责描述 */
        private String responsibility;
        /** 迁移到新类的方法名列表 */
        private List<String> methodsToMove = new ArrayList<>();
        /** 迁移到新类的字段名列表 */
        private List<String> fieldsToMove = new ArrayList<>();

        public String getNewClassName() {
            return newClassName;
        }

        public void setNewClassName(String newClassName) {
            this.newClassName = newClassName;
        }

        public String getResponsibility() {
            return responsibility;
        }

        public void setResponsibility(String responsibility) {
            this.responsibility = responsibility;
        }

        public List<String> getMethodsToMove() {
            return methodsToMove;
        }

        public void setMethodsToMove(List<String> methodsToMove) {
            this.methodsToMove = methodsToMove;
        }

        public List<String> getFieldsToMove() {
            return fieldsToMove;
        }

        public void setFieldsToMove(List<String> fieldsToMove) {
            this.fieldsToMove = fieldsToMove;
        }
    }
}
