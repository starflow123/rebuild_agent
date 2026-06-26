package com.rebuild.agent.model;

/**
 * 字段节点：描述类中的成员变量
 * 对应 Neo4j 中的 :Field 节点
 */
public class FieldNode {

    /** 所属类的全限定名 */
    private String ownerClass;

    /** 字段名 */
    private String fieldName;

    /** 字段类型全限定名 */
    private String fieldType;

    /** 访问修饰符 */
    private String accessModifier;

    /** 是否静态字段 */
    private boolean isStatic;

    /** 是否 final */
    private boolean isFinal;

    /** 字段声明所在行号 */
    private int line;

    /** 该字段被读取的次数（跨方法） */
    private int readCount;

    /** 该字段被写入的次数（跨方法） */
    private int writeCount;

    public FieldNode() {
    }

    public FieldNode(String ownerClass, String fieldName, String fieldType) {
        this.ownerClass = ownerClass;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
    }

    /** 生成唯一标识：ownerClass#fieldName */
    public String getUniqueId() {
        return ownerClass + "#" + fieldName;
    }

    // ---- getters / setters ----

    public String getOwnerClass() {
        return ownerClass;
    }

    public void setOwnerClass(String ownerClass) {
        this.ownerClass = ownerClass;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getAccessModifier() {
        return accessModifier;
    }

    public void setAccessModifier(String accessModifier) {
        this.accessModifier = accessModifier;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public void setFinal(boolean aFinal) {
        isFinal = aFinal;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getReadCount() {
        return readCount;
    }

    public void setReadCount(int readCount) {
        this.readCount = readCount;
    }

    public int getWriteCount() {
        return writeCount;
    }

    public void setWriteCount(int writeCount) {
        this.writeCount = writeCount;
    }

    @Override
    public String toString() {
        return "FieldNode{" + ownerClass + "." + fieldName + ": " + fieldType + "}";
    }
}
