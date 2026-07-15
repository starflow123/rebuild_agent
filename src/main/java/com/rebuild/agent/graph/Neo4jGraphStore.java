package com.rebuild.agent.graph;

import com.rebuild.agent.analyzer.DependencyGraphBuilder;
import com.rebuild.agent.model.*;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rebuild.agent.graph.GraphConstants.*;

/**
 * Neo4j 嵌入式图存储：管理嵌入式数据库生命周期，将代码依赖图谱持久化到 Neo4j
 * <p>
 * 第一步的存储层：接收 DependencyGraphBuilder 的构建结果，写入 Neo4j 图数据库。
 * 使用嵌入式模式，无需额外部署 Neo4j 服务，数据落盘到本地目录。
 */
@Component
public class Neo4jGraphStore {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphStore.class);

    /** 默认数据库名 */
    private static final String DEFAULT_DB = "neo4j";

    private final DatabaseManagementService managementService;
    private final GraphDatabaseService graphDb;

    /**
     * 构造时启动嵌入式 Neo4j 实例
     * @param dataPath Neo4j 数据存储目录，从配置读取
     */
    public Neo4jGraphStore(@Value("${rebuild.graph.neo4j.data-path:./neo4j-data}") String dataPath) {
        Path dbPath = Paths.get(dataPath).toAbsolutePath().normalize();
        log.info("启动嵌入式 Neo4j，数据目录: {}", dbPath);
        this.managementService = new DatabaseManagementServiceBuilder(dbPath).build();
        this.graphDb = managementService.database(DEFAULT_DB);
        createIndex();
        log.info("嵌入式 Neo4j 启动完成");
    }

    /**
     * 创建全限定名索引，加速查询
     */
    private void createIndex() {
        try (Transaction tx = graphDb.beginTx()) {
            // Neo4j 5.x 使用 schema 创建索引，检查是否已存在
            boolean indexExists = false;
            for (org.neo4j.graphdb.schema.IndexDefinition idx : tx.schema().getIndexes(Label.label(LABEL_CLASS))) {
                for (String key : idx.getPropertyKeys()) {
                    if (PROP_QUALIFIED_NAME.equals(key)) {
                        indexExists = true;
                        break;
                    }
                }
                if (indexExists) break;
            }
            if (!indexExists) {
                tx.schema().indexFor(Label.label(LABEL_CLASS))
                        .on(PROP_QUALIFIED_NAME)
                        .create();
                log.info("创建 Class 节点 qualifiedName 索引");
            }
            tx.commit();
        } catch (Exception e) {
            // 索引可能已存在，忽略
            log.debug("创建索引时: {}", e.getMessage());
        }
    }

    /**
     * 将构建结果（类节点 + 依赖边）持久化到 Neo4j
     * @param buildResult 图谱构建结果
     */
    public void persistGraph(DependencyGraphBuilder.BuildResult buildResult) {
        log.info("开始持久化图谱：{} 个类节点，{} 条边",
                buildResult.getClassNodes().size(), buildResult.getEdges().size());

        try (Transaction tx = graphDb.beginTx()) {
            // 先清空旧数据（全量重建模式）
            tx.execute("MATCH (n) DETACH DELETE n");
            log.info("已清空旧图谱数据");

            // 1. 写入类节点
            Map<String, Node> nodeCache = new HashMap<>();
            for (ClassNode classNode : buildResult.getClassNodes()) {
                Node node = tx.createNode(Label.label(LABEL_CLASS));
                node.setProperty(PROP_QUALIFIED_NAME, classNode.getQualifiedName());
                node.setProperty(PROP_SIMPLE_NAME, classNode.getSimpleName());
                node.setProperty(PROP_PACKAGE, classNode.getPackageName());
                node.setProperty(PROP_SOURCE_FILE, classNode.getSourceFilePath() == null ? "" : classNode.getSourceFilePath());
                node.setProperty(PROP_CLASS_TYPE, classNode.getClassType() == null ? "CLASS" : classNode.getClassType());
                node.setProperty(PROP_LINE_COUNT, classNode.getLineCount());
                node.setProperty(PROP_IN_DEGREE, classNode.getIncomingDependencyCount());
                node.setProperty(PROP_OUT_DEGREE, classNode.getOutgoingDependencyCount());
                node.setProperty(PROP_COMPLEXITY, classNode.getCyclomaticComplexity());
                nodeCache.put(classNode.getQualifiedName(), node);
            }
            log.info("类节点写入完成");

            // 2. 写入依赖边
            int edgeCount = 0;
            for (DependencyEdge edge : buildResult.getEdges()) {
                Node sourceNode = nodeCache.get(edge.getSource());
                Node targetNode = nodeCache.get(edge.getTarget());
                if (sourceNode == null || targetNode == null) {
                    continue;
                }
                RelationshipType relType = RelationshipType.withName(toRelType(edge.getType()));
                Relationship rel = sourceNode.createRelationshipTo(targetNode, relType);
                rel.setProperty(PROP_DEP_TYPE, edge.getType().name());
                if (edge.getDetail() != null) {
                    rel.setProperty(PROP_DETAIL, edge.getDetail());
                }
                rel.setProperty(PROP_LINE, edge.getLineNumber());
                edgeCount++;
            }
            log.info("依赖边写入完成：{} 条", edgeCount);

            tx.commit();
            log.info("图谱持久化完成");
        }
    }

    /**
     * 将 DependencyType 映射为 Neo4j 关系类型字符串
     */
    private String toRelType(DependencyType type) {
        return switch (type) {
            case METHOD_CALL -> REL_METHOD_CALL;
            case FIELD_ACCESS -> REL_FIELD_ACCESS;
            case INHERITANCE -> REL_INHERITANCE;
            case IMPLEMENTATION -> REL_IMPLEMENTATION;
            case COMPOSITION -> REL_COMPOSITION;
            case PARAMETER_TYPE -> REL_PARAMETER_TYPE;
            case INSTANTIATION -> REL_INSTANTIATION;
            case ANNOTATION_USAGE -> REL_ANNOTATION_USAGE;
        };
    }

    /**
     * 获取 GraphDatabaseService（供查询服务使用）
     */
    public GraphDatabaseService getGraphDb() {
        return graphDb;
    }

    /**
     * 统计当前图谱中的节点数和边数
     */
    public Map<String, Long> getGraphStats() {
        try (Transaction tx = graphDb.beginTx()) {
            long nodeCount = (long) tx.execute("MATCH (n:Class) RETURN count(n) AS cnt")
                    .next().get("cnt");
            long edgeCount = (long) tx.execute("MATCH ()-[r]->() RETURN count(r) AS cnt")
                    .next().get("cnt");
            tx.commit();
            return Map.of("classNodes", nodeCount, "edges", edgeCount);
        }
    }

    /**
     * 关闭嵌入式数据库
     */
    @PreDestroy
    public void shutdown() {
        log.info("关闭嵌入式 Neo4j");
        if (managementService != null) {
            managementService.shutdown();
        }
    }
}
