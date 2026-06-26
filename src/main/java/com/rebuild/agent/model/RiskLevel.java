package com.rebuild.agent.model;

/**
 * 重构风险等级
 * 用于评估每个重构步骤的风险，指导执行顺序与回滚策略
 */
public enum RiskLevel {

    /** 低风险：独立工具类、无外部依赖的纯函数 */
    LOW("低风险"),

    /** 中风险：内部服务，被少量类引用 */
    MEDIUM("中风险"),

    /** 高风险：核心 API，被大量类依赖，改动影响面大 */
    HIGH("高风险");

    private final String description;

    RiskLevel(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据依赖数量自动推断风险等级
     * @param dependencyCount 该类被其他类依赖的数量
     * @return 对应的风险等级
     */
    public static RiskLevel fromDependencyCount(int dependencyCount) {
        if (dependencyCount <= 5) {
            return LOW;
        } else if (dependencyCount <= 20) {
            return MEDIUM;
        } else {
            return HIGH;
        }
    }
}
