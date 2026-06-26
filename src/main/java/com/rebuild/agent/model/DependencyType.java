package com.rebuild.agent.model;

/**
 * 依赖关系类型枚举
 * 描述图谱中节点之间不同维度的依赖关系
 */
public enum DependencyType {

    /** 类 A 调用了类 B 的方法 */
    METHOD_CALL,

    /** 类 A 使用了类 B 的字段 */
    FIELD_ACCESS,

    /** 类 A 继承了类 B */
    INHERITANCE,

    /** 类 A 实现了接口 I */
    IMPLEMENTATION,

    /** 类 A 持有类 B 类型的字段（组合/聚合） */
    COMPOSITION,

    /** 类 A 的方法参数/返回值类型为类 B */
    PARAMETER_TYPE,

    /** 类 A 内部创建了类 B 的实例 */
    INSTANTIATION,

    /** 类 A 注解使用了类 B */
    ANNOTATION_USAGE
}
