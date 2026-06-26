package com.rebuild.agent.analyzer;

import com.rebuild.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖图谱构建器：整合 ProjectScanner + AstAnalyzer 的输出，构建完整的代码依赖图谱
 * <p>
 * 第一步（静态分析引擎）的收尾：将散落的类节点通过依赖边连接成图，
 * 并计算每个类的入度/出度，为"上帝类"识别和风险评级提供数据基础。
 */
@Component
public class DependencyGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    private final ProjectScanner projectScanner;
    private final AstAnalyzer astAnalyzer;

    @Autowired
    public DependencyGraphBuilder(ProjectScanner projectScanner, AstAnalyzer astAnalyzer) {
        this.projectScanner = projectScanner;
        this.astAnalyzer = astAnalyzer;
    }

    /**
     * 构建整个项目的依赖图谱
     * @param projectRoot 项目根目录
     * @return 构建结果，包含所有类节点和依赖边
     */
    public BuildResult buildGraph(String projectRoot) {
        return buildGraph(projectRoot, false);
    }

    /**
     * 构建整个项目的依赖图谱
     * @param projectRoot 项目根目录
     * @param includeTest 是否包含测试代码
     * @return 构建结果
     */
    public BuildResult buildGraph(String projectRoot, boolean includeTest) {
        log.info("开始构建依赖图谱，项目根目录: {}", projectRoot);
        long startTime = System.currentTimeMillis();

        // 1. 扫描源文件
        List<Path> sourceFiles = projectScanner.scanSourceFiles(projectRoot, includeTest);
        if (sourceFiles.isEmpty()) {
            log.warn("未找到任何 Java 源文件，图谱构建终止");
            return new BuildResult(Collections.emptyList(), Collections.emptyList());
        }

        // 2. 解析所有源文件，提取类节点
        Map<String, ClassNode> classNodeMap = new ConcurrentHashMap<>();
        for (Path sourceFile : sourceFiles) {
            List<ClassNode> nodes = astAnalyzer.analyzeFile(sourceFile);
            for (ClassNode node : nodes) {
                classNodeMap.put(node.getQualifiedName(), node);
            }
        }
        log.info("解析完成，共提取 {} 个类节点", classNodeMap.size());

        // 3. 构建依赖边
        List<DependencyEdge> edges = buildEdges(classNodeMap);
        log.info("依赖边构建完成，共 {} 条边", edges.size());

        // 4. 计算入度/出度
        computeDependencyCounts(classNodeMap, edges);

        long duration = System.currentTimeMillis() - startTime;
        log.info("依赖图谱构建完成，耗时 {} ms", duration);

        return new BuildResult(new ArrayList<>(classNodeMap.values()), edges);
    }

    /**
     * 构建所有依赖边
     */
    private List<DependencyEdge> buildEdges(Map<String, ClassNode> classNodeMap) {
        List<DependencyEdge> edges = new ArrayList<>();
        Set<String> knownClasses = classNodeMap.keySet();

        for (ClassNode node : classNodeMap.values()) {
            String source = node.getQualifiedName();

            // 继承关系
            if (node.getSuperClass() != null && knownClasses.contains(node.getSuperClass())) {
                edges.add(new DependencyEdge(source, node.getSuperClass(),
                        DependencyType.INHERITANCE, "extends", 0));
            }

            // 实现关系
            for (String iface : node.getInterfaces()) {
                if (knownClasses.contains(iface)) {
                    edges.add(new DependencyEdge(source, iface,
                            DependencyType.IMPLEMENTATION, "implements", 0));
                }
            }

            // 注解使用
            for (String ann : node.getAnnotations()) {
                if (knownClasses.contains(ann)) {
                    edges.add(new DependencyEdge(source, ann,
                            DependencyType.ANNOTATION_USAGE, "@" + ann, 0));
                }
            }

            // 字段组合关系（持有其他类的引用）
            for (FieldNode field : node.getFields()) {
                if (knownClasses.contains(field.getFieldType())) {
                    edges.add(new DependencyEdge(source, field.getFieldType(),
                            DependencyType.COMPOSITION, field.getFieldName(), field.getLine()));
                }
            }

            // 方法级依赖：方法调用、字段访问、实例化、参数类型
            for (MethodNode method : node.getMethods()) {
                // 返回值类型依赖
                if (method.getReturnType() != null && knownClasses.contains(method.getReturnType())) {
                    edges.add(new DependencyEdge(source, method.getReturnType(),
                            DependencyType.PARAMETER_TYPE, "return:" + method.getMethodName(), method.getStartLine()));
                }
                // 参数类型依赖
                for (String paramType : method.getParameterTypes()) {
                    if (knownClasses.contains(paramType)) {
                        edges.add(new DependencyEdge(source, paramType,
                                DependencyType.PARAMETER_TYPE, "param:" + method.getMethodName(), method.getStartLine()));
                    }
                }
                // 方法调用依赖
                for (String called : method.getCalledMethods()) {
                    String targetClass = resolveCallTarget(called, node, classNodeMap);
                    if (targetClass != null && knownClasses.contains(targetClass) && !targetClass.equals(source)) {
                        edges.add(new DependencyEdge(source, targetClass,
                                DependencyType.METHOD_CALL, called, method.getStartLine()));
                    }
                }
                // 对象实例化依赖
                for (String instantiated : method.getInstantiatedClasses()) {
                    if (knownClasses.contains(instantiated) && !instantiated.equals(source)) {
                        edges.add(new DependencyEdge(source, instantiated,
                                DependencyType.INSTANTIATION, "new " + instantiated, method.getStartLine()));
                    }
                }
            }
        }

        // 去重：相同 source-target-type-detail 的边只保留一条
        return deduplicateEdges(edges);
    }

    /**
     * 解析方法调用目标所属的类
     * 调用形如 "targetExpr.methodName"，需要推断 targetExpr 对应的类
     */
    private String resolveCallTarget(String calledMethod, ClassNode owner, Map<String, ClassNode> classNodeMap) {
        int dotIdx = calledMethod.indexOf('.');
        if (dotIdx <= 0) {
            return null;
        }
        String targetExpr = calledMethod.substring(0, dotIdx);

        // 情况1：targetExpr 是字段名 -> 查找字段类型
        for (FieldNode field : owner.getFields()) {
            if (field.getFieldName().equals(targetExpr)) {
                return field.getFieldType();
            }
        }
        // 情况2：targetExpr 是类名（静态调用）
        if (classNodeMap.containsKey(targetExpr)) {
            return targetExpr;
        }
        // 情况3：targetExpr 是简单类名，尝试匹配全限定名
        for (String qName : classNodeMap.keySet()) {
            if (qName.endsWith("." + targetExpr) || qName.equals(targetExpr)) {
                return qName;
            }
        }
        // 情况4：this 调用，属于本类内部
        if ("this".equals(targetExpr)) {
            return owner.getQualifiedName();
        }
        return null;
    }

    /**
     * 计算每个类的入度（被依赖数）和出度（依赖他人数）
     */
    private void computeDependencyCounts(Map<String, ClassNode> classNodeMap, List<DependencyEdge> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        for (DependencyEdge edge : edges) {
            outDegree.merge(edge.getSource(), 1, Integer::sum);
            inDegree.merge(edge.getTarget(), 1, Integer::sum);
        }
        for (ClassNode node : classNodeMap.values()) {
            node.setIncomingDependencyCount(inDegree.getOrDefault(node.getQualifiedName(), 0));
            node.setOutgoingDependencyCount(outDegree.getOrDefault(node.getQualifiedName(), 0));
        }
    }

    /**
     * 依赖边去重
     */
    private List<DependencyEdge> deduplicateEdges(List<DependencyEdge> edges) {
        Set<String> seen = new HashSet<>();
        List<DependencyEdge> unique = new ArrayList<>();
        for (DependencyEdge edge : edges) {
            String key = edge.getSource() + "|" + edge.getTarget() + "|" + edge.getType()
                    + "|" + (edge.getDetail() == null ? "" : edge.getDetail());
            if (seen.add(key)) {
                unique.add(edge);
            }
        }
        return unique;
    }

    /**
     * 构建结果：包含所有类节点和依赖边
     */
    public static class BuildResult {
        private final List<ClassNode> classNodes;
        private final List<DependencyEdge> edges;

        public BuildResult(List<ClassNode> classNodes, List<DependencyEdge> edges) {
            this.classNodes = classNodes;
            this.edges = edges;
        }

        public List<ClassNode> getClassNodes() {
            return classNodes;
        }

        public List<DependencyEdge> getEdges() {
            return edges;
        }
    }
}
