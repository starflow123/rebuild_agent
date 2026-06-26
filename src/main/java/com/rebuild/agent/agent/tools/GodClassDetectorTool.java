package com.rebuild.agent.agent.tools;

import com.rebuild.agent.graph.GraphQueryService;
import com.rebuild.agent.model.ClassNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上帝类检测工具：识别依赖数过多、代码行数过大的"上帝类"
 * <p>
 * 第二步 Agent 分析阶段的入口工具，帮助 LLM 定位最需要重构的类。
 */
@Component
public class GodClassDetectorTool {

    private static final Logger log = LoggerFactory.getLogger(GodClassDetectorTool.class);

    private final GraphQueryService graphQueryService;

    public GodClassDetectorTool(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    /**
     * 检测上帝类
     */
    @Tool("识别项目中的'上帝类'：依赖数超过阈值且代码行数过大的类。这是重构的首要目标。")
    public String findGodClasses(
            @P("依赖数阈值，默认 50，超过此值的类视为依赖过多") int dependencyThreshold,
            @P("代码行数阈值，默认 500，超过此值的类视为代码过多") int lineThreshold) {
        log.info("Agent 调用 findGodClasses: depThreshold={}, lineThreshold={}",
                dependencyThreshold, lineThreshold);

        List<ClassNode> godClasses = graphQueryService.findGodClasses(dependencyThreshold, lineThreshold);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("上帝类检测结果（依赖阈值=%d, 行数阈值=%d）:%n",
                dependencyThreshold, lineThreshold));
        sb.append(String.format("共识别到 %d 个上帝类:%n%n", godClasses.size()));

        for (int i = 0; i < godClasses.size(); i++) {
            ClassNode cls = godClasses.get(i);
            sb.append(String.format("%d. %s%n", i + 1, cls.getQualifiedName()));
            sb.append(String.format("   - 类型: %s%n", cls.getClassType()));
            sb.append(String.format("   - 代码行数: %d%n", cls.getLineCount()));
            sb.append(String.format("   - 入度（被依赖数）: %d%n", cls.getIncomingDependencyCount()));
            sb.append(String.format("   - 出度（依赖他人数）: %d%n", cls.getOutgoingDependencyCount()));
            sb.append(String.format("   - 总依赖数: %d%n",
                    cls.getIncomingDependencyCount() + cls.getOutgoingDependencyCount()));
            sb.append(String.format("   - 圈复杂度: %d%n", cls.getCyclomaticComplexity()));
            sb.append(String.format("   - 源文件: %s%n", cls.getSourceFilePath()));
            sb.append("\n");
        }

        if (godClasses.isEmpty()) {
            sb.append("未发现上帝类，项目代码结构良好。\n");
        } else {
            sb.append("建议：优先重构总依赖数最高、代码行数最大的类。\n");
        }
        return sb.toString();
    }

    /**
     * 列出所有类（按依赖数排序）
     */
    @Tool("列出项目中所有类，按总依赖数降序排列。用于了解项目整体结构和发现潜在问题类。")
    public String listAllClasses() {
        log.info("Agent 调用 listAllClasses");
        List<ClassNode> allClasses = graphQueryService.findAllClasses();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("项目共 %d 个类（按依赖数降序）:%n%n", allClasses.size()));
        sb.append(String.format("%-60s %8s %8s %8s %8s%n",
                "类名", "行数", "入度", "出度", "复杂度"));
        sb.append("-".repeat(100)).append("\n");
        for (int i = 0; i < allClasses.size() && i < 100; i++) {
            ClassNode cls = allClasses.get(i);
            sb.append(String.format("%-60s %8d %8d %8d %8d%n",
                    cls.getQualifiedName(), cls.getLineCount(),
                    cls.getIncomingDependencyCount(), cls.getOutgoingDependencyCount(),
                    cls.getCyclomaticComplexity()));
        }
        if (allClasses.size() > 100) {
            sb.append(String.format("... 及其他 %d 个类%n", allClasses.size() - 100));
        }
        return sb.toString();
    }
}
