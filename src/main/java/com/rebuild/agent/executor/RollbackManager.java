package com.rebuild.agent.executor;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 回滚管理器：在每次代码修改前备份原文件，失败时恢复
 * <p>
 * 第三步（增量执行 + 回滚保障）的核心保障机制。
 * 每个重构步骤执行前调用 backup，失败后调用 rollback 恢复到修改前状态。
 */
@Component
public class RollbackManager {

    private static final Logger log = LoggerFactory.getLogger(RollbackManager.class);

    /** 备份根目录 */
    private final Path backupRoot;

    /** 步骤序号 -> 该步骤备份的文件列表 */
    private final Map<Integer, List<BackupEntry>> stepBackups = new LinkedHashMap<>();

    public RollbackManager() {
        this.backupRoot = Paths.get(".rebuild-backup").toAbsolutePath();
        try {
            Files.createDirectories(backupRoot);
        } catch (IOException e) {
            log.error("创建备份目录失败: {}", backupRoot, e);
        }
    }

    /**
     * 备份单个文件，关联到指定步骤序号
     * @param stepIndex 步骤序号
     * @param sourceFile 源文件路径
     */
    public void backup(int stepIndex, Path sourceFile) {
        if (!Files.exists(sourceFile)) {
            log.warn("待备份文件不存在: {}", sourceFile);
            return;
        }
        try {
            Path backupDir = backupRoot.resolve("step-" + stepIndex);
            Files.createDirectories(backupDir);
            // 用文件名的 hash 作为备份文件名，避免路径冲突
            String backupName = Math.abs(sourceFile.toString().hashCode()) + "_" + sourceFile.getFileName();
            Path backupFile = backupDir.resolve(backupName);
            FileUtils.copyFile(sourceFile.toFile(), backupFile.toFile());

            stepBackups.computeIfAbsent(stepIndex, k -> new ArrayList<>())
                    .add(new BackupEntry(sourceFile, backupFile));
            log.debug("已备份文件: {} -> {}", sourceFile, backupFile);
        } catch (IOException e) {
            log.error("备份文件失败: {}", sourceFile, e);
            throw new RuntimeException("备份文件失败: " + sourceFile, e);
        }
    }

    /**
     * 回滚指定步骤的所有修改
     * @param stepIndex 步骤序号
     */
    public void rollback(int stepIndex) {
        List<BackupEntry> backups = stepBackups.get(stepIndex);
        if (backups == null || backups.isEmpty()) {
            log.warn("步骤 {} 无备份记录，无法回滚", stepIndex);
            return;
        }
        log.info("开始回滚步骤 {}，涉及 {} 个文件", stepIndex, backups.size());
        for (BackupEntry entry : backups) {
            try {
                FileUtils.copyFile(entry.backupFile.toFile(), entry.sourceFile.toFile());
                log.info("已恢复文件: {}", entry.sourceFile);
            } catch (IOException e) {
                log.error("恢复文件失败: {}", entry.sourceFile, e);
            }
        }
    }

    /**
     * 回滚到指定步骤之前（包含该步骤）的所有修改
     * @param toStepIndex 回滚到此步骤（含）
     */
    public void rollbackTo(int toStepIndex) {
        log.info("回滚到步骤 {}（含）之前的状态", toStepIndex);
        // 按步骤序号降序回滚
        stepBackups.keySet().stream()
                .filter(idx -> idx >= toStepIndex)
                .sorted(Comparator.reverseOrder())
                .forEach(this::rollback);
    }

    /**
     * 清理指定步骤的备份（成功后释放空间）
     */
    public void clearBackup(int stepIndex) {
        List<BackupEntry> backups = stepBackups.remove(stepIndex);
        if (backups != null) {
            for (BackupEntry entry : backups) {
                try {
                    Files.deleteIfExists(entry.backupFile);
                } catch (IOException e) {
                    log.debug("删除备份文件失败: {}", entry.backupFile);
                }
            }
        }
    }

    /**
     * 清理所有备份
     */
    public void clearAll() {
        stepBackups.clear();
        try {
            if (Files.exists(backupRoot)) {
                FileUtils.deleteDirectory(backupRoot.toFile());
                log.info("已清理所有备份");
            }
        } catch (IOException e) {
            log.error("清理备份目录失败", e);
        }
    }

    /**
     * 备份记录
     */
    private static class BackupEntry {
        final Path sourceFile;
        final Path backupFile;

        BackupEntry(Path sourceFile, Path backupFile) {
            this.sourceFile = sourceFile;
            this.backupFile = backupFile;
        }
    }
}
