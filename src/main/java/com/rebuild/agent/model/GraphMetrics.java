package com.rebuild.agent.model;

import java.util.List;

/**
 * 图谱度量指标：描述某个类或子图的聚合统计信息
 * 供 Agent 判断"上帝类"和评估风险使用
 */
public class GraphMetrics {

    /** 目标类全限定名 */
    private String className;

    /** 直接依赖该类的类列表（入度方向） */
    private List<String> dependedBy;

    /** 该类直接依赖的类列表（出度方向） */
    private List<String> dependsOn;

    /** 被依赖总数（入度） */
    private int incomingCount;

    /** 依赖他人总数（出度） */
    private int outgoingCount;

    /** 代码行数 */
    private int lineCount;

    /** 公共方法数 */
    private int publicMethodCount;

    /** 字段数 */
    private int fieldCount;

    /** 圈复杂度总和 */
    private int totalComplexity;

    /** 综合风险等级 */
    private RiskLevel riskLevel;

    public GraphMetrics() {
    }

    /** 计算综合依赖度，用于排序识别上帝类 */
    public int getTotalDependencyCount() {
        return incomingCount + outgoingCount;
    }

    /** 计算风险评分（0-100），分数越高风险越大 */
    public int getRiskScore() {
        int score = 0;
        score += Math.min(incomingCount * 2, 40);   // 入度权重最高，被依赖越多越危险
        score += Math.min(outgoingCount, 20);        // 出度
        score += Math.min(lineCount / 50, 20);       // 代码行数
        score += Math.min(totalComplexity, 20);      // 复杂度
        return Math.min(score, 100);
    }

    // ---- getters / setters ----

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public List<String> getDependedBy() {
        return dependedBy;
    }

    public void setDependedBy(List<String> dependedBy) {
        this.dependedBy = dependedBy;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn;
    }

    public int getIncomingCount() {
        return incomingCount;
    }

    public void setIncomingCount(int incomingCount) {
        this.incomingCount = incomingCount;
    }

    public int getOutgoingCount() {
        return outgoingCount;
    }

    public void setOutgoingCount(int outgoingCount) {
        this.outgoingCount = outgoingCount;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }

    public int getPublicMethodCount() {
        return publicMethodCount;
    }

    public void setPublicMethodCount(int publicMethodCount) {
        this.publicMethodCount = publicMethodCount;
    }

    public int getFieldCount() {
        return fieldCount;
    }

    public void setFieldCount(int fieldCount) {
        this.fieldCount = fieldCount;
    }

    public int getTotalComplexity() {
        return totalComplexity;
    }

    public void setTotalComplexity(int totalComplexity) {
        this.totalComplexity = totalComplexity;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }
}
