package com.rebuild.agent.cli;

import com.rebuild.agent.executor.RefactorExecutor;
import com.rebuild.agent.model.RefactorPlan;
import com.rebuild.agent.agent.tools.RefactorPlanTool;
import com.rebuild.agent.service.RefactorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Scanner;

/**
 * 命令行交互入口：提供交互式菜单驱动完整重构流程
 * <p>
 * 支持的命令：
 * - analyze <projectPath>     分析项目构建依赖图谱
 * - detect                    识别上帝类
 * - agent <targetClass>       Agent 分析并生成重构方案
 * - execute <projectPath>     执行重构计划
 * - report                    生成影响范围报告
 * - full <projectPath> [cls]  一键执行完整流程
 * - exit                      退出
 */
@Component
public class RebuildCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RebuildCliRunner.class);

    private final RefactorService refactorService;
    private final RefactorPlanTool planTool;

    @Autowired
    public RebuildCliRunner(RefactorService refactorService, RefactorPlanTool planTool) {
        this.refactorService = refactorService;
        this.planTool = planTool;
    }

    @Override
    public void run(String... args) {
        System.out.println();
        System.out.println("============================================");
        System.out.println("  手术式重构 Agent (Surgical Refactoring)");
        System.out.println("  面向遗留 Java 系统的智能重构工具");
        System.out.println("============================================");
        System.out.println();

        // 非交互模式：直接执行传入的命令
        if (args.length > 0 && !args[0].startsWith("--")) {
            executeCommand(args);
            return;
        }

        // 交互模式
        Scanner scanner = new Scanner(System.in);
        while (true) {
            printMenu();
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            String[] parts = input.split("\\s+");
            if ("exit".equalsIgnoreCase(parts[0]) || "quit".equalsIgnoreCase(parts[0])) {
                System.out.println("再见！");
                break;
            }
            try {
                executeCommand(parts);
            } catch (Exception e) {
                log.error("命令执行失败", e);
                System.out.println("错误: " + e.getMessage());
            }
            System.out.println();
        }
    }

    private void printMenu() {
        System.out.println("可用命令:");
        System.out.println("  analyze <项目路径>          分析项目，构建依赖图谱");
        System.out.println("  detect                     识别上帝类");
        System.out.println("  agent [目标类全限定名]      Agent 分析并生成重构方案");
        System.out.println("  execute <项目路径>          执行重构计划");
        System.out.println("  report                     生成影响范围报告");
        System.out.println("  full <项目路径> [目标类]    一键执行完整重构流程");
        System.out.println("  exit                       退出");
    }

    private void executeCommand(String[] parts) {
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "analyze" -> {
                if (parts.length < 2) {
                    System.out.println("用法: analyze <项目路径>");
                    return;
                }
                System.out.println(refactorService.analyzeProject(parts[1], false));
            }
            case "detect" -> {
                String result = refactorService.analyzeWithAgent(null);
                System.out.println(result);
            }
            case "agent" -> {
                String targetClass = parts.length >= 2 ? parts[1] : null;
                String result = refactorService.analyzeWithAgent(targetClass);
                System.out.println(result);
            }
            case "execute" -> {
                if (parts.length < 2) {
                    System.out.println("用法: execute <项目路径>");
                    return;
                }
                RefactorExecutor.ExecutionResult result = refactorService.executeRefactor(parts[1]);
                if (result != null) {
                    System.out.printf("执行完成: 成功 %d 步, 失败 %d 步%n",
                            result.getSuccessSteps(), result.getFailedSteps());
                    if (result.getInterventionReport() != null) {
                        System.out.println("\n⚠ 需要人工介入，请查看报告。");
                    }
                }
            }
            case "report" -> {
                RefactorPlan plan = planTool.getCurrentPlan();
                if (plan == null) {
                    System.out.println("无重构计划，请先执行 agent 命令");
                    return;
                }
                System.out.println("请先执行 execute 生成执行结果，或直接生成报告需要执行结果。");
                System.out.println("提示: 使用 full 命令可一键完成全流程并生成报告。");
            }
            case "full" -> {
                if (parts.length < 2) {
                    System.out.println("用法: full <项目路径> [目标类全限定名]");
                    return;
                }
                String targetClass = parts.length >= 3 ? parts[2] : null;
                String summary = refactorService.fullRefactor(parts[1], targetClass);
                System.out.println(summary);
            }
            default -> System.out.println("未知命令: " + cmd + "，输入 help 查看可用命令");
        }
    }
}
