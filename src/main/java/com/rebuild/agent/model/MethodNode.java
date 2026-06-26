package com.rebuild.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法节点：描述类中的单个方法
 * 对应 Neo4j 中的 :Method 节点
 */
public class MethodNode {

    /** 所属类的全限定名 */
    private String ownerClass;

    /** 方法名 */
    private String methodName;

    /** 方法签名，例如 "createOrder(String, int):Order" */
    private String signature;

    /** 返回值类型全限定名 */
    private String returnType;

    /** 参数类型全限定名列表 */
    private List<String> parameterTypes = new ArrayList<>();

    /** 访问修饰符：public / private / protected / default */
    private String accessModifier;

    /** 是否静态方法 */
    private boolean isStatic;

    /** 方法体起始行号 */
    private int startLine;

    /** 方法体结束行号 */
    private int endLine;

    /** 方法代码行数 */
    private int lineCount;

    /** 该方法调用的其他方法（ownerClass.methodName 格式） */
    private List<String> calledMethods = new ArrayList<>();

    /** 该方法引用的字段 */
    private List<String> accessedFields = new ArrayList<>();

    /** 该方法内部实例化的类 */
    private List<String> instantiatedClasses = new ArrayList<>();

    /** 方法圈复杂度 */
    private int cyclomaticComplexity;

    public MethodNode() {
    }

    public MethodNode(String ownerClass, String methodName) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
    }

    /** 生成唯一标识：ownerClass#methodName(signature) */
    public String getUniqueId() {
        return ownerClass + "#" + methodName + "(" + String.join(",", parameterTypes) + ")";
    }

    // ---- getters / setters ----

    public String getOwnerClass() {
        return ownerClass;
    }

    public void setOwnerClass(String ownerClass) {
        this.ownerClass = ownerClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(List<String> parameterTypes) {
        this.parameterTypes = parameterTypes;
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

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }

    public List<String> getCalledMethods() {
        return calledMethods;
    }

    public void setCalledMethods(List<String> calledMethods) {
        this.calledMethods = calledMethods;
    }

    public List<String> getAccessedFields() {
        return accessedFields;
    }

    public void setAccessedFields(List<String> accessedFields) {
        this.accessedFields = accessedFields;
    }

    public List<String> getInstantiatedClasses() {
        return instantiatedClasses;
    }

    public void setInstantiatedClasses(List<String> instantiatedClasses) {
        this.instantiatedClasses = instantiatedClasses;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public void setCyclomaticComplexity(int cyclomaticComplexity) {
        this.cyclomaticComplexity = cyclomaticComplexity;
    }

    @Override
    public String toString() {
        return "MethodNode{" + ownerClass + "." + methodName + ", lines=" + lineCount + "}";
    }
}
