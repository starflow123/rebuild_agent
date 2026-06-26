package com.rebuild.agent.agent.tools;

import com.rebuild.agent.executor.CodeModifier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 代码修改工具：供 Agent 调用，通过 JavaParser 改写源码
 * <p>
 * 第三步（增量执行）的代码操作工具，让 LLM 能"动手"修改代码。
 */
@Component
public class CodeModifyTool {

    private static final Logger log = LoggerFactory.getLogger(CodeModifyTool.class);

    private final CodeModifier codeModifier;

    public CodeModifyTool(CodeModifier codeModifier) {
        this.codeModifier = codeModifier;
    }

    /**
     * 提取新类
     */
    @Tool("从原类中提取一个新类，将指定方法和字段迁移到新类中。返回新创建的源文件路径。")
    public String extractClass(
            @P("原类源文件的绝对路径") String sourceFilePath,
            @P("原类简单名") String sourceClassName,
            @P("新类简单名") String newClassName,
            @P("新类所在包名") String packageName,
            @P("待迁移的方法名列表，逗号分隔") String methodsToMove,
            @P("待迁移的字段名列表，逗号分隔，可为空") String fieldsToMove) {
        log.info("Agent 调用 extractClass: {} -> {}", sourceClassName, newClassName);
        try {
            Path sourceFile = Paths.get(sourceFilePath);
            List<String> methods = parseList(methodsToMove);
            List<String> fields = parseList(fieldsToMove);
            Path newFile = codeModifier.extractClass(sourceFile, sourceClassName,
                    newClassName, packageName, methods, fields);
            return String.format("新类提取成功: %s -> %s。新文件: %s",
                    sourceClassName, newClassName, newFile);
        } catch (Exception e) {
            log.error("提取新类失败", e);
            return "提取新类失败: " + e.getMessage();
        }
    }

    /**
     * 迁移方法
     */
    @Tool("将指定方法从源类迁移到目标类。")
    public String moveMethod(
            @P("源类源文件路径") String sourceFilePath,
            @P("源类简单名") String sourceClassName,
            @P("方法名") String methodName,
            @P("目标类源文件路径") String targetFilePath,
            @P("目标类简单名") String targetClassName) {
        log.info("Agent 调用 moveMethod: {}.{} -> {}", sourceClassName, methodName, targetClassName);
        try {
            codeModifier.moveMethod(Paths.get(sourceFilePath), sourceClassName, methodName,
                    Paths.get(targetFilePath), targetClassName);
            return String.format("方法迁移成功: %s.%s -> %s", sourceClassName, methodName, targetClassName);
        } catch (Exception e) {
            log.error("迁移方法失败", e);
            return "迁移方法失败: " + e.getMessage();
        }
    }

    /**
     * 迁移字段
     */
    @Tool("将指定字段从源类迁移到目标类。")
    public String moveField(
            @P("源类源文件路径") String sourceFilePath,
            @P("源类简单名") String sourceClassName,
            @P("字段名") String fieldName,
            @P("目标类源文件路径") String targetFilePath,
            @P("目标类简单名") String targetClassName) {
        log.info("Agent 调用 moveField: {}.{} -> {}", sourceClassName, fieldName, targetClassName);
        try {
            codeModifier.moveField(Paths.get(sourceFilePath), sourceClassName, fieldName,
                    Paths.get(targetFilePath), targetClassName);
            return String.format("字段迁移成功: %s.%s -> %s", sourceClassName, fieldName, targetClassName);
        } catch (Exception e) {
            log.error("迁移字段失败", e);
            return "迁移字段失败: " + e.getMessage();
        }
    }

    /**
     * 更新调用引用
     */
    @Tool("更新源文件中的方法调用引用，将旧调用目标名替换为新调用目标名。用于重构后更新调用方代码。")
    public String updateReferences(
            @P("待更新的源文件路径") String filePath,
            @P("旧的调用目标名（字段名或类名）") String oldTargetName,
            @P("新的调用目标名") String newTargetName) {
        log.info("Agent 调用 updateReferences: {} -> {} (file: {})", oldTargetName, newTargetName, filePath);
        try {
            codeModifier.updateMethodCallReferences(Paths.get(filePath), oldTargetName, newTargetName);
            return String.format("引用更新完成: %s -> %s (文件: %s)", oldTargetName, newTargetName, filePath);
        } catch (Exception e) {
            log.error("更新引用失败", e);
            return "更新引用失败: " + e.getMessage();
        }
    }

    /**
     * 添加字段（用于注入新拆分出的类依赖）
     */
    @Tool("在指定类中添加一个字段，通常用于注入新拆分出的类依赖。可指定注解如 @Autowired。")
    public String addField(
            @P("源文件路径") String filePath,
            @P("类简单名") String className,
            @P("字段类型全限定名") String fieldType,
            @P("字段名") String fieldName,
            @P("注解全限定名，可为空，例如 org.springframework.beans.factory.annotation.Autowired") String annotation) {
        log.info("Agent 调用 addField: {} {} (class: {})", fieldType, fieldName, className);
        try {
            codeModifier.addField(Paths.get(filePath), className, fieldType, fieldName, annotation);
            return String.format("字段添加成功: %s %s (类: %s)", fieldType, fieldName, className);
        } catch (Exception e) {
            log.error("添加字段失败", e);
            return "添加字段失败: " + e.getMessage();
        }
    }

    private List<String> parseList(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
