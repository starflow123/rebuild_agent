package com.rebuild.agent.graph;

import com.rebuild.agent.model.*;
import org.neo4j.graphdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.rebuild.agent.graph.GraphConstants.*;

/**
 * 图谱查询服务：从 Neo4j 中查询子图、识别上帝类、计算度量指标
 * <p>
 * 供 Agent 工具调用，是 Agent 读取图数据库的桥梁。
 * 第二步 Agent 分析时，通过本服务提取目标类的局部子图交给 LLM。
 */
@Component
public class GraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(GraphQueryService.class);

    private final Neo4jGraphStore graphStore;

    @Autowired
    public GraphQueryService(Neo4jGraphStore graphStore) {
        this.graphStore = graphStore;
    }

    /**
     * 提取以指定类为中心、向外扩展 N 层的子图
     * @param centerClass 中心类全限定名
     * @param depth 扩展深度（1=直接依赖，2=间接依赖）
     * @return 子图
     */
    public SubGraph querySubGraph(String centerClass, int depth) {
        log.info("查询子图：中心类={}, 深度={}", centerClass, depth);
        SubGraph subGraph = new SubGraph(centerClass);
        subGraph.setDepth(depth);

        try (Transaction tx = graphStore.getGraphDb().beginTx()) {
            // 查找中心类节点
            Node centerNode = findClassNode(tx, centerClass);
            if (centerNode == null) {
                log.warn("未找到中心类节点: {}", centerClass);
                return subGraph;
            }

            // BFS 扩展，收集 depth 层内的所有类节点
            Set<Long> visitedNodeIds = new HashSet<>();
            Queue<long[]> queue = new ArrayDeque<>();  // {nodeId, currentDepth}
            queue.add(new long[]{centerNode.getId(), 0});
            visitedNodeIds.add(centerNode.getId());

            Map<Long, Node> nodeMap = new HashMap<>();
            nodeMap.put(centerNode.getId(), centerNode);

            while (!queue.isEmpty()) {
                long[] current = queue.poll();
                long nodeId = current[0];
                int currentDepth = (int) current[1];
                Node node = tx.getNodeById(nodeId);

                if (currentDepth < depth) {
                    // 遍历出边和入边
                    for (Relationship rel : node.getRelationships()) {
                        Node neighbor = rel.getOtherNode(node);
                        if (!visitedNodeIds.contains(neighbor.getId())
                                && neighbor.hasLabel(Label.label(LABEL_CLASS))) {
                            visitedNodeIds.add(neighbor.getId());
                            nodeMap.put(neighbor.getId(), neighbor);
                            queue.add(new long[]{neighbor.getId(), currentDepth + 1});
                        }
                    }
                }
            }

            // 将节点转换为 ClassNode 并加入子图
            for (Node node : nodeMap.values()) {
                subGraph.addClassNode(toClassNode(node));
            }

            // 收集子图内节点之间的所有边
            for (Node node : nodeMap.values()) {
                for (Relationship rel : node.getRelationships(Direction.OUTGOING)) {
                    Node target = rel.getEndNode();
                    if (nodeMap.containsKey(target.getId())) {
                        subGraph.addEdge(toDependencyEdge(rel));
                    }
                }
            }

            tx.commit();
        }

        log.info("子图查询完成：{} 个类节点，{} 条边",
                subGraph.getClassNodes().size(), subGraph.getEdges().size());
        return subGraph;
    }

    /**
     * 识别"上帝类"：依赖数超过阈值且代码行数过大的类
     * @param dependencyThreshold 依赖数阈值
     * @param lineThreshold 行数阈值
     * @return 上帝类列表（按依赖数降序）
     */
    public List<ClassNode> findGodClasses(int dependencyThreshold, int lineThreshold) {
        log.info("识别上帝类：依赖阈值={}, 行数阈值={}", dependencyThreshold, lineThreshold);
        List<ClassNode> godClasses = new ArrayList<>();

        try (Transaction tx = graphStore.getGraphDb().beginTx()) {
            ResourceIterator<Node> allNodes = tx.findNodes(Label.label(LABEL_CLASS));
            while (allNodes.hasNext()) {
                Node node = allNodes.next();
                int inDegree = (int) node.getProperty(PROP_IN_DEGREE, 0);
                int outDegree = (int) node.getProperty(PROP_OUT_DEGREE, 0);
                int lineCount = (int) node.getProperty(PROP_LINE_COUNT, 0);
                if (inDegree + outDegree >= dependencyThreshold && lineCount >= lineThreshold) {
                    godClasses.add(toClassNode(node));
                }
            }
            tx.commit();
        }

        // 按总依赖数降序排序
        godClasses.sort((a, b) ->
                Integer.compare(b.getIncomingDependencyCount() + b.getOutgoingDependencyCount(),
                        a.getIncomingDependencyCount() + a.getOutgoingDependencyCount()));
        log.info("识别到 {} 个上帝类", godClasses.size());
        return godClasses;
    }

    /**
     * 计算指定类的度量指标
     */
    public GraphMetrics computeMetrics(String className) {
        GraphMetrics metrics = new GraphMetrics();
        metrics.setClassName(className);

        try (Transaction tx = graphStore.getGraphDb().beginTx()) {
            Node node = findClassNode(tx, className);
            if (node == null) {
                return metrics;
            }

            int inDegree = (int) node.getProperty(PROP_IN_DEGREE, 0);
            int outDegree = (int) node.getProperty(PROP_OUT_DEGREE, 0);
            int lineCount = (int) node.getProperty(PROP_LINE_COUNT, 0);
            int complexity = (int) node.getProperty(PROP_COMPLEXITY, 0);

            metrics.setIncomingCount(inDegree);
            metrics.setOutgoingCount(outDegree);
            metrics.setLineCount(lineCount);
            metrics.setTotalComplexity(complexity);
            metrics.setRiskLevel(RiskLevel.fromDependencyCount(inDegree));

            // 查询被哪些类依赖（入边来源）
            List<String> dependedBy = new ArrayList<>();
            for (Relationship rel : node.getRelationships(Direction.INCOMING)) {
                Node source = rel.getStartNode();
                dependedBy.add((String) source.getProperty(PROP_QUALIFIED_NAME));
            }
            metrics.setDependedBy(dependedBy);

            // 查询依赖了哪些类（出边目标）
            List<String> dependsOn = new ArrayList<>();
            for (Relationship rel : node.getRelationships(Direction.OUTGOING)) {
                Node target = rel.getEndNode();
                dependsOn.add((String) target.getProperty(PROP_QUALIFIED_NAME));
            }
            metrics.setDependsOn(dependsOn);

            tx.commit();
        }

        return metrics;
    }

    /**
     * 影响范围分析：查询所有直接或间接依赖指定类的类
     * 用于重构前的"波及范围"评估
     * @param className 目标类
     * @return 受影响的类全限定名列表
     */
    public List<String> analyzeImpactScope(String className) {
        log.info("分析影响范围：{}", className);
        Set<String> impacted = new LinkedHashSet<>();

        try (Transaction tx = graphStore.getGraphDb().beginTx()) {
            Node target = findClassNode(tx, className);
            if (target == null) {
                return new ArrayList<>();
            }

            // BFS 反向遍历：找到所有依赖（直接或间接）该类的类
            Set<Long> visited = new HashSet<>();
            Queue<Long> queue = new ArrayDeque<>();
            queue.add(target.getId());
            visited.add(target.getId());

            while (!queue.isEmpty()) {
                long currentId = queue.poll();
                Node current = tx.getNodeById(currentId);
                for (Relationship rel : current.getRelationships(Direction.INCOMING)) {
                    Node source = rel.getStartNode();
                    if (visited.add(source.getId())) {
                        String qName = (String) source.getProperty(PROP_QUALIFIED_NAME);
                        impacted.add(qName);
                        queue.add(source.getId());
                    }
                }
            }
            tx.commit();
        }

        log.info("影响范围分析完成：{} 个类受影响", impacted.size());
        return new ArrayList<>(impacted);
    }

    /**
     * 查询所有类节点（按依赖数降序）
     */
    public List<ClassNode> findAllClasses() {
        List<ClassNode> all = new ArrayList<>();
        try (Transaction tx = graphStore.getGraphDb().beginTx()) {
            ResourceIterator<Node> nodes = tx.findNodes(Label.label(LABEL_CLASS));
            while (nodes.hasNext()) {
                all.add(toClassNode(nodes.next()));
            }
            tx.commit();
        }
        all.sort((a, b) -> Integer.compare(
                b.getIncomingDependencyCount() + b.getOutgoingDependencyCount(),
                a.getIncomingDependencyCount() + a.getOutgoingDependencyCount()));
        return all;
    }

    /**
     * 按全限定名查找类节点
     */
    private Node findClassNode(Transaction tx, String qualifiedName) {
        try (ResourceIterator<Node> nodes = tx.findNodes(Label.label(LABEL_CLASS),
                PROP_QUALIFIED_NAME, qualifiedName)) {
            return nodes.hasNext() ? nodes.next() : null;
        }
    }

    /**
     * Neo4j Node 转换为 ClassNode
     */
    private ClassNode toClassNode(Node node) {
        String qName = (String) node.getProperty(PROP_QUALIFIED_NAME);
        ClassNode classNode = new ClassNode(qName);
        classNode.setSimpleName((String) node.getProperty(PROP_SIMPLE_NAME, qName));
        classNode.setPackageName((String) node.getProperty(PROP_PACKAGE, ""));
        classNode.setSourceFilePath((String) node.getProperty(PROP_SOURCE_FILE, ""));
        classNode.setClassType((String) node.getProperty(PROP_CLASS_TYPE, "CLASS"));
        classNode.setLineCount((int) node.getProperty(PROP_LINE_COUNT, 0));
        classNode.setIncomingDependencyCount((int) node.getProperty(PROP_IN_DEGREE, 0));
        classNode.setOutgoingDependencyCount((int) node.getProperty(PROP_OUT_DEGREE, 0));
        classNode.setCyclomaticComplexity((int) node.getProperty(PROP_COMPLEXITY, 0));
        return classNode;
    }

    /**
     * Neo4j Relationship 转换为 DependencyEdge
     */
    private DependencyEdge toDependencyEdge(Relationship rel) {
        String source = (String) rel.getStartNode().getProperty(PROP_QUALIFIED_NAME);
        String target = (String) rel.getEndNode().getProperty(PROP_QUALIFIED_NAME);
        DependencyType type = DependencyType.valueOf(
                (String) rel.getProperty(PROP_DEP_TYPE, DependencyType.METHOD_CALL.name()));
        String detail = rel.hasProperty(PROP_DETAIL) ? (String) rel.getProperty(PROP_DETAIL) : null;
        int line = (int) rel.getProperty(PROP_LINE, 0);
        return new DependencyEdge(source, target, type, detail, line);
    }
}
