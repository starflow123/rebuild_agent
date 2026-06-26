package com.rebuild.agent.model;

/**
 * 依赖边：描述图谱中两个节点之间的依赖关系
 * 对应 Neo4j 中的关系（边）
 */
public class DependencyEdge {

    /** 依赖发起方（源节点）的全限定名 */
    private String source;

    /** 被依赖方（目标节点）的全限定名 */
    private String target;

    /** 依赖类型 */
    private DependencyType type;

    /** 附加描述信息，例如调用的具体方法名 */
    private String detail;

    /** 发生依赖的源码行号 */
    private int lineNumber;

    public DependencyEdge() {
    }

    public DependencyEdge(String source, String target, DependencyType type) {
        this.source = source;
        this.target = target;
        this.type = type;
    }

    public DependencyEdge(String source, String target, DependencyType type, String detail, int lineNumber) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.detail = detail;
        this.lineNumber = lineNumber;
    }

    // ---- getters / setters ----

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public DependencyType getType() {
        return type;
    }

    public void setType(DependencyType type) {
        this.type = type;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    @Override
    public String toString() {
        return source + " --[" + type + "]--> " + target
                + (detail != null ? " (" + detail + ")" : "");
    }
}
