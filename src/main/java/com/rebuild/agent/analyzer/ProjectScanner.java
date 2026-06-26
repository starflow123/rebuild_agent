package com.rebuild.agent.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目扫描器：扫描 Maven/Gradle 项目的源码目录，收集所有 .java 源文件路径
 * <p>
 * 第一步（静态分析引擎）的入口：负责定位待分析的源码文件集合。
 * 支持 Maven 标准目录结构（src/main/java, src/test/java）。
 */
@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    /** Maven 主源码目录 */
    private static final String MAIN_SOURCE_DIR = "src/main/java";
    /** Maven 测试源码目录 */
    private static final String TEST_SOURCE_DIR = "src/test/java";

    /**
     * 扫描项目根目录，返回所有主源码 .java 文件路径
     * @param projectRoot 项目根目录绝对路径
     * @return 源文件路径列表（绝对路径）
     */
    public List<Path> scanSourceFiles(String projectRoot) {
        return scanSourceFiles(projectRoot, false);
    }

    /**
     * 扫描项目根目录，返回源码 .java 文件路径
     * @param projectRoot 项目根目录绝对路径
     * @param includeTest 是否包含测试源码
     * @return 源文件路径列表（绝对路径）
     */
    public List<Path> scanSourceFiles(String projectRoot, boolean includeTest) {
        Path rootPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("项目根目录不存在或不是目录: " + projectRoot);
        }

        List<Path> sourceFiles = new ArrayList<>();
        // 扫描主源码目录
        Path mainSourcePath = rootPath.resolve(MAIN_SOURCE_DIR);
        if (Files.isDirectory(mainSourcePath)) {
            sourceFiles.addAll(collectJavaFiles(mainSourcePath));
            log.info("扫描主源码目录 {}，找到 {} 个 Java 文件", mainSourcePath, sourceFiles.size());
        } else {
            log.warn("未找到主源码目录: {}", mainSourcePath);
        }

        // 可选：扫描测试源码目录
        if (includeTest) {
            Path testSourcePath = rootPath.resolve(TEST_SOURCE_DIR);
            if (Files.isDirectory(testSourcePath)) {
                List<Path> testFiles = collectJavaFiles(testSourcePath);
                sourceFiles.addAll(testFiles);
                log.info("扫描测试源码目录 {}，找到 {} 个 Java 文件", testSourcePath, testFiles.size());
            }
        }

        log.info("项目扫描完成，共找到 {} 个 Java 源文件", sourceFiles.size());
        return sourceFiles;
    }

    /**
     * 递归收集指定目录下所有 .java 文件
     */
    private List<Path> collectJavaFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        files.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("访问文件失败: {} - {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历目录失败: {}", directory, e);
            throw new RuntimeException("扫描源码目录失败: " + directory, e);
        }
        return files;
    }

    /**
     * 判断给定路径是否为 Maven 项目（存在 pom.xml）
     */
    public boolean isMavenProject(String projectRoot) {
        return Files.exists(Paths.get(projectRoot, "pom.xml"));
    }

    /**
     * 判断给定路径是否为 Gradle 项目（存在 build.gradle）
     */
    public boolean isGradleProject(String projectRoot) {
        return Files.exists(Paths.get(projectRoot, "build.gradle"))
                || Files.exists(Paths.get(projectRoot, "build.gradle.kts"));
    }

    /**
     * 将源文件绝对路径转换为相对于项目根目录的相对路径
     * 用于在图谱中记录可读的文件位置
     */
    public String toRelativePath(String projectRoot, Path absolutePath) {
        Path root = Paths.get(projectRoot).toAbsolutePath().normalize();
        Path abs = absolutePath.toAbsolutePath().normalize();
        return root.relativize(abs).toString().replace('\\', '/');
    }
}
