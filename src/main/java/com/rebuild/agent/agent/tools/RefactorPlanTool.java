package com.rebuild.agent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rebuild.agent.model.RefactorPlan;
import com.rebuild.agent.model.RefactorStep;
import com.rebuild.agent.model.RiskLevel;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 重构计划工具：供 Agent 生成和管理结构化的重构方案
 * <p>
 * 第二步 Agent 分析阶段的输出工具，将 LLM 的拆分建议转化为可执行的计划。
 */
@Component
public class RefactorPlanTool {

    private static final Logger log = LoggerFactory.getLogger(RefactorPlanTool.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前正在构建的计划（单例，简化处理） */
    private RefactorPlan currentPlan;

    /**
     * 创建重构计划
     */
    @Tool("创建一个新的重构计划。指定计划名称、目标类和重构原因。")
    public String createPlan(
            @P("计划名称，例如 'OrderService 拆分计划'") String planName,
            @P("待重构的目标类全限定名") String targetClass,
            @P("重构原因，说明为何需要重构") String reason) {
        log.info("Agent 调用 createPlan: name={}, target={}", planName, targetClass);
        currentPlan = new RefactorPlan();
        currentPlan.setPlanName(planName);
        currentPlan.setTargetClass(targetClass);
        currentPlan.setReason(reason);
        return String.format("已创建重构计划 '%s'，目标类: %s。请继续添加拆分建议和执行步骤。", planName, targetClass);
    }

    /**
     * 添加拆分建议
     */
    @Tool("向当前重构计划添加拆分建议：描述一个新拆分出的类及其职责、迁移的方法和字段。")
    public String addSplitSuggestion(
            @P("新类名") String newClassName,
            @P("新类职责描述") String responsibility,
            @P("迁移到新类的方法名列表，逗号分隔") String methodsToMove,
            @P("迁移到新类的字段名列表，逗号分隔，可为空") String fieldsToMove) {
        log.info("Agent 调用 addSplitSuggestion: newClass={}", newClassName);
        if (currentPlan == null) {
            return "错误：请先调用 createPlan 创建计划";
        }

        RefactorPlan.SplitClassSuggestion suggestion = new RefactorPlan.SplitClassSuggestion();
        suggestion.setNewClassName(newClassName);
        suggestion.setResponsibility(responsibility);
        suggestion.setMethodsToMove(parseList(methodsToMove));
        suggestion.setFieldsToMove(parseList(fieldsToMove));
        currentPlan.getSplitSuggestions().add(suggestion);

        return String.format("已添加拆分建议: 新类 %s（职责: %s），迁移 %d 个方法、%d 个字段",
                newClassName, responsibility, suggestion.getMethodsToMove().size(), suggestion.getFieldsToMove().size());
    }

    /**
     * 添加执行步骤
     */
    @Tool("向当前重构计划添加一个执行步骤。步骤按添加顺序执行。")
    public String addStep(
            @P("步骤描述") String description,
            @P("操作类型: EXTRACT_CLASS / MOVE_METHOD / MOVE_FIELD / UPDATE_REFERENCE") String stepType,
            @P("目标文件路径") String targetFile,
            @P("目标类名") String targetClass,
            @P("风险等级: LOW / MEDIUM / HIGH") String riskLevel) {
        log.info("Agent 调用 addStep: desc={}, type={}", description, stepType);
        if (currentPlan == null) {
            return "错误：请先调用 createPlan 创建计划";
        }

        RefactorStep step = new RefactorStep();
        step.setStepIndex(currentPlan.getSteps().size() + 1);
        step.setDescription(description);
        try {
            step.setStepType(RefactorStep.StepType.valueOf(stepType));
        } catch (IllegalArgumentException e) {
            return "错误：无效的操作类型 " + stepType;
        }
        step.setTargetFile(targetFile);
        step.setTargetClass(targetClass);
        try {
            step.setRiskLevel(RiskLevel.valueOf(riskLevel));
        } catch (IllegalArgumentException e) {
            step.setRiskLevel(RiskLevel.MEDIUM);
        }
        currentPlan.getSteps().add(step);

        return String.format("已添加步骤 %d: %s（类型: %s，风险: %s）",
                step.getStepIndex(), description, stepType, riskLevel);
    }

    /**
     * 获取当前计划的完整内容（JSON 格式）
     */
    @Tool("获取当前重构计划的完整内容，包括拆分建议和所有执行步骤。返回 JSON 格式。")
    public String getPlan() {
        if (currentPlan == null) {
            return "当前没有正在进行的重构计划";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentPlan);
        } catch (Exception e) {
            log.error("序列化计划失败", e);
            return "序列化失败: " + e.getMessage();
        }
    }

    /**
     * 获取当前计划对象（供执行器使用）
     */
    public RefactorPlan getCurrentPlan() {
        return currentPlan;
    }

    /**
     * 重置当前计划
     */
    public void resetPlan() {
        currentPlan = null;
    }

    private List<String> parseList(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
