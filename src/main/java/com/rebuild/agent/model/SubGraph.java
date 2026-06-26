package com.rebuild.agent.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 子图：以某个类为中心，按深度提取的局部依赖图
 * Agent 读取子图后交给 LLM 分析
 */
public class SubGraph {

    /** 子图中心类（分析目标） */
    private String centerClass;

    /** 子图包含的所有类节点 */
    private List<ClassNode> classNodes = new ArrayList<>();

    /** 子图包含的所有依赖边 */
    private List<DependencyEdge> edges = new ArrayList<>();

    /** 提取深度（从中心类向外扩展几层） */
    private int depth;

    /** 子图涉及的文件路径集合（用于影响范围分析） */
    private Set<String> involvedFiles = new HashSet<>();

    public SubGraph() {
    }

    public SubGraph(String centerClass) {
        this.centerClass = centerClass;
    }

    /** 添加类节点并记录其源文件路径 */
    public void addClassNode(ClassNode node) {
        classNodes.add(node);
        if (node.getSourceFilePath() != null) {
            involvedFiles.add(node.getSourceFilePath());
        }
    }

    public void addEdge(DependencyEdge edge) {
        edges.add(edge);
    }

    /** 统计子图中各类的入度，返回按入度降序排列的类名列表（用于识别上帝类） */
    public List<String> getClassesByIncomingDegree() {
        java.util.Map<String, Integer> inDegree = new java.util.HashMap<>();
        for (ClassNode node : classNodes) {
            inDegree.put(node.getQualifiedName(), 0);
        }
        for (DependencyEdge edge : edges) {
            inDegree.merge(edge.getTarget(), 1, Integer::sum);
        }
        return inDegree.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    // ---- getters / setters ----

    public String getCenterClass() {
        return centerClass;
    }

    public void setCenterClass(String centerClass) {
        this.centerClass = centerClass;
    }

    public List<ClassNode> getClassNodes() {
        return classNodes;
    }

    public void setClassNodes(List<ClassNode> classNodes) {
        this.classNodes = classNodes;
    }

    public List<DependencyEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<DependencyEdge> edges) {
        this.edges = edges;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public Set<String> getInvolvedFiles() {
        return involvedFiles;
    }

    public void setInvolvedFiles(Set<String> involvedFiles) {
        this.involvedFiles = involvedFiles;
    }
}
