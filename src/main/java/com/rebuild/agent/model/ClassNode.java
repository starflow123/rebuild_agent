package com.rebuild.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 类节点：代码依赖图谱中的"类"节点
 * 对应 Neo4j 中的 :Class 节点
 */
public class ClassNode {

    /** 全限定类名，作为唯一标识，例如 com.example.UserService */
    private String qualifiedName;

    /** 简单类名，例如 UserService */
    private String simpleName;

    /** 包名，例如 com.example */
    private String packageName;

    /** 源文件相对路径，例如 src/main/java/com/example/UserService.java */
    private String sourceFilePath;

    /** 类类型：CLASS / INTERFACE / ENUM / ABSTRACT */
    private String classType;

    /** 父类全限定名（若有） */
    private String superClass;

    /** 实现的接口全限定名列表 */
    private List<String> interfaces = new ArrayList<>();

    /** 类上的注解全限定名列表 */
    private List<String> annotations = new ArrayList<>();

    /** 该类包含的方法节点 */
    private List<MethodNode> methods = new ArrayList<>();

    /** 该类包含的字段节点 */
    private List<FieldNode> fields = new ArrayList<>();

    /** 代码行数 */
    private int lineCount;

    /** 圈复杂度（近似：方法数 + 分支语句数） */
    private int cyclomaticComplexity;

    /** 被其他类依赖的数量（入度），用于识别"上帝类" */
    private int incomingDependencyCount;

    /** 该类依赖其他类的数量（出度） */
    private int outgoingDependencyCount;

    public ClassNode() {
    }

    public ClassNode(String qualifiedName) {
        this.qualifiedName = qualifiedName;
        this.simpleName = extractSimpleName(qualifiedName);
        this.packageName = extractPackageName(qualifiedName);
    }

    private String extractSimpleName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(idx + 1) : qualifiedName;
    }

    private String extractPackageName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(0, idx) : "";
    }

    /**
     * 判断是否为"上帝类"：依赖数超过阈值且代码行数过大
     * @param dependencyThreshold 依赖数阈值，默认 50
     * @param lineThreshold 行数阈值，默认 500
     */
    public boolean isGodClass(int dependencyThreshold, int lineThreshold) {
        return (incomingDependencyCount + outgoingDependencyCount) >= dependencyThreshold
                && lineCount >= lineThreshold;
    }

    /** 默认阈值判断上帝类 */
    public boolean isGodClass() {
        return isGodClass(50, 500);
    }

    // ---- getters / setters ----

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public void setSimpleName(String simpleName) {
        this.simpleName = simpleName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getSourceFilePath() {
        return sourceFilePath;
    }

    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    public String getClassType() {
        return classType;
    }

    public void setClassType(String classType) {
        this.classType = classType;
    }

    public String getSuperClass() {
        return superClass;
    }

    public void setSuperClass(String superClass) {
        this.superClass = superClass;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(List<String> interfaces) {
        this.interfaces = interfaces;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
    }

    public List<MethodNode> getMethods() {
        return methods;
    }

    public void setMethods(List<MethodNode> methods) {
        this.methods = methods;
    }

    public List<FieldNode> getFields() {
        return fields;
    }

    public void setFields(List<FieldNode> fields) {
        this.fields = fields;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public void setCyclomaticComplexity(int cyclomaticComplexity) {
        this.cyclomaticComplexity = cyclomaticComplexity;
    }

    public int getIncomingDependencyCount() {
        return incomingDependencyCount;
    }

    public void setIncomingDependencyCount(int incomingDependencyCount) {
        this.incomingDependencyCount = incomingDependencyCount;
    }

    public int getOutgoingDependencyCount() {
        return outgoingDependencyCount;
    }

    public void setOutgoingDependencyCount(int outgoingDependencyCount) {
        this.outgoingDependencyCount = outgoingDependencyCount;
    }

    @Override
    public String toString() {
        return "ClassNode{" + qualifiedName + ", lines=" + lineCount
                + ", inDeg=" + incomingDependencyCount + ", outDeg=" + outgoingDependencyCount + "}";
    }
}
