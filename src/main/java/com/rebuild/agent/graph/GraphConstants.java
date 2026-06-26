package com.rebuild.agent.graph;

/**
 * 图谱常量：Neo4j 节点标签与关系类型定义
 */
public final class GraphConstants {

    private GraphConstants() {
    }

    /** 节点标签：类 */
    public static final String LABEL_CLASS = "Class";
    /** 节点标签：方法 */
    public static final String LABEL_METHOD = "Method";
    /** 节点标签：字段 */
    public static final String LABEL_FIELD = "Field";

    /** 关系类型：方法调用 */
    public static final String REL_METHOD_CALL = "METHOD_CALL";
    /** 关系类型：字段访问 */
    public static final String REL_FIELD_ACCESS = "FIELD_ACCESS";
    /** 关系类型：继承 */
    public static final String REL_INHERITANCE = "INHERITANCE";
    /** 关系类型：实现 */
    public static final String REL_IMPLEMENTATION = "IMPLEMENTATION";
    /** 关系类型：组合 */
    public static final String REL_COMPOSITION = "COMPOSITION";
    /** 关系类型：参数类型 */
    public static final String REL_PARAMETER_TYPE = "PARAMETER_TYPE";
    /** 关系类型：实例化 */
    public static final String REL_INSTANTIATION = "INSTANTIATION";
    /** 关系类型：注解使用 */
    public static final String REL_ANNOTATION_USAGE = "ANNOTATION_USAGE";

    /** 属性：全限定名（主键） */
    public static final String PROP_QUALIFIED_NAME = "qualifiedName";
    /** 属性：简单名 */
    public static final String PROP_SIMPLE_NAME = "simpleName";
    /** 属性：包名 */
    public static final String PROP_PACKAGE = "packageName";
    /** 属性：源文件路径 */
    public static final String PROP_SOURCE_FILE = "sourceFile";
    /** 属性：类类型 */
    public static final String PROP_CLASS_TYPE = "classType";
    /** 属性：代码行数 */
    public static final String PROP_LINE_COUNT = "lineCount";
    /** 属性：入度 */
    public static final String PROP_IN_DEGREE = "inDegree";
    /** 属性：出度 */
    public static final String PROP_OUT_DEGREE = "outDegree";
    /** 属性：圈复杂度 */
    public static final String PROP_COMPLEXITY = "complexity";
    /** 属性：依赖类型 */
    public static final String PROP_DEP_TYPE = "depType";
    /** 属性：详情 */
    public static final String PROP_DETAIL = "detail";
    /** 属性：行号 */
    public static final String PROP_LINE = "line";
}
