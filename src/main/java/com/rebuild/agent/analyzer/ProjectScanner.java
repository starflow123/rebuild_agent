package com.rebuild.agent.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 项目扫描器：扫描各种项目结构的源码目录，收集所有 .java 源文件路径
 * <p>
 * 支持的项目类型：
 * - Maven/Gradle 标准项目（src/main/java）
 * - 多模块/微服务项目（递归查找所有 src/main/java）
 * - 老系统/非标准项目（Ant、Eclipse、手工构建）
 *   - 源码目录模式：src/, java/, source/, WebRoot/WEB-INF/src/, WebContent/WEB-INF/src/
 *   - Eclipse .classpath 文件解析（kind="src" 的 entry）
 *   - Ant build.xml 文件解析（<src> 或 srcdir 属性）
 * <p>
 * 当标准目录不存在时，自动尝试多种兜底策略。
 */
@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    /** Maven/Gradle 主源码目录 */
    private static final String MAIN_SOURCE_DIR = "src/main/java";
    /** Maven/Gradle 测试源码目录 */
    private static final String TEST_SOURCE_DIR = "src/test/java";

    /** 扫描深度限制，防止递归过深 */
    private static final int MAX_SCAN_DEPTH = 8;

    /** 老系统常见源码目录模式 */
    private static final List<String> LEGACY_SOURCE_PATTERNS = Arrays.asList(
            "src",
            "java",
            "source",
            "src/java",
            "src/main",
            "WebRoot/WEB-INF/src",
            "WebContent/WEB-INF/src",
            "WebRoot/WEB-INF/classes",
            "WebContent/WEB-INF/classes",
            "src/com",
            "src/org",
            "src/cn"
    );

    /** 需要排除的目录（编译输出、依赖、生成文件等） */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            "target", "build", ".git", ".gradle", ".idea",
            "bin", "classes", "out", "dist",
            "WEB-INF/classes", "WEB-INF/lib",
            "node_modules", "bower_components",
            ".settings", ".metadata",
            "generated-sources", "generated-test-sources"
    ));

    /**
     * 扫描项目根目录，返回所有主源码 .java 文件路径
     * 自动支持多模块项目和老系统非标准结构
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

        String projectType = detectProjectType(projectRoot);
        log.info("检测到项目类型: {}", projectType);

        List<Path> sourceDirs = new ArrayList<>();

        sourceDirs.addAll(findAllSourceDirs(rootPath, MAIN_SOURCE_DIR));

        if (sourceDirs.isEmpty()) {
            log.info("未找到标准 src/main/java，尝试老系统源码目录模式");
            sourceDirs.addAll(findLegacySourceDirs(rootPath));
        }

        if (sourceDirs.isEmpty()) {
            log.info("未找到老系统源码目录，尝试解析 Eclipse .classpath");
            sourceDirs.addAll(parseEclipseClasspath(rootPath));
        }

        if (sourceDirs.isEmpty()) {
            log.info("未找到 Eclipse 配置，尝试解析 Ant build.xml");
            sourceDirs.addAll(parseAntBuildXml(rootPath));
        }

        List<Path> testDirs = includeTest ? findAllSourceDirs(rootPath, TEST_SOURCE_DIR) : Collections.emptyList();

        if (sourceDirs.isEmpty()) {
            log.warn("未找到任何源码目录，在项目根目录下搜索所有 .java 文件（兜底模式）");
            List<Path> fallbackFiles = collectJavaFilesFallback(rootPath);
            log.info("兜底扫描找到 {} 个 Java 文件", fallbackFiles.size());
            return fallbackFiles;
        }

        sourceDirs = deduplicateAndFilter(sourceDirs, rootPath);

        List<Path> sourceFiles = new ArrayList<>();
        for (Path sourceDir : sourceDirs) {
            List<Path> files = collectJavaFiles(sourceDir);
            sourceFiles.addAll(files);
            log.info("扫描源码目录 {}，找到 {} 个 Java 文件", sourceDir, files.size());
        }

        if (includeTest) {
            for (Path testDir : testDirs) {
                List<Path> files = collectJavaFiles(testDir);
                sourceFiles.addAll(files);
                log.info("扫描测试目录 {}，找到 {} 个 Java 文件", testDir, files.size());
            }
        }

        sourceFiles = removeDuplicates(sourceFiles);

        log.info("项目扫描完成，共找到 {} 个 Java 源文件（{} 个源码目录）",
                sourceFiles.size(), sourceDirs.size());
        return sourceFiles;
    }

    /**
     * 递归查找项目根目录下所有指定类型的源码目录
     * 支持 Maven 多模块、Gradle 多项目、微服务项目结构
     */
    private List<Path> findAllSourceDirs(Path rootPath, String sourceDirName) {
        List<Path> sourceDirs = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                private int depth = 0;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    depth++;
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || isExcludedPath(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (depth > MAX_SCAN_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("访问文件失败: {} - {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    depth--;
                    Path sourcePath = dir.resolve(sourceDirName);
                    if (Files.isDirectory(sourcePath)) {
                        sourceDirs.add(sourcePath.toAbsolutePath().normalize());
                        log.debug("发现源码目录: {}", sourcePath);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历目录失败: {}", rootPath, e);
        }
        return sourceDirs;
    }

    /**
     * 查找老系统常见的源码目录模式
     * 支持：src/, java/, source/, WebRoot/WEB-INF/src/, WebContent/WEB-INF/src/ 等
     */
    private List<Path> findLegacySourceDirs(Path rootPath) {
        List<Path> sourceDirs = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                private int depth = 0;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    depth++;
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || isExcludedPath(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (depth > MAX_SCAN_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("访问文件失败: {} - {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    depth--;

                    for (String pattern : LEGACY_SOURCE_PATTERNS) {
                        Path sourcePath = dir.resolve(pattern);
                        if (Files.isDirectory(sourcePath)) {
                            boolean hasJavaFiles = hasJavaFiles(sourcePath);
                            if (hasJavaFiles) {
                                sourceDirs.add(sourcePath.toAbsolutePath().normalize());
                                log.debug("发现老系统源码目录: {}", sourcePath);
                            }
                        }
                    }

                    String relativePath = rootPath.relativize(dir).toString().replace('\\', '/');
                    for (String pattern : LEGACY_SOURCE_PATTERNS) {
                        if (relativePath.equals(pattern) || relativePath.endsWith("/" + pattern)) {
                            boolean hasJavaFiles = hasJavaFiles(dir);
                            if (hasJavaFiles) {
                                sourceDirs.add(dir.toAbsolutePath().normalize());
                                log.debug("发现老系统源码目录: {}", dir);
                            }
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历目录失败: {}", rootPath, e);
        }
        return sourceDirs;
    }

    /**
     * 解析 Eclipse .classpath 文件，提取源码路径
     * 格式示例：<classpathentry kind="src" path="src"/>
     */
    private List<Path> parseEclipseClasspath(Path rootPath) {
        List<Path> sourceDirs = new ArrayList<>();
        Path classpathFile = rootPath.resolve(".classpath");
        if (!Files.exists(classpathFile)) {
            return sourceDirs;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(classpathFile.toFile());

            org.w3c.dom.NodeList entries = doc.getElementsByTagName("classpathentry");
            for (int i = 0; i < entries.getLength(); i++) {
                org.w3c.dom.Node entry = entries.item(i);
                String kind = getAttributeValue(entry, "kind");
                String path = getAttributeValue(entry, "path");

                if ("src".equals(kind) && path != null && !path.isEmpty()) {
                    Path sourcePath = rootPath.resolve(path);
                    if (Files.isDirectory(sourcePath)) {
                        sourceDirs.add(sourcePath.toAbsolutePath().normalize());
                        log.debug("从 .classpath 解析出源码目录: {}", sourcePath);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 Eclipse .classpath 失败: {}", e.getMessage());
        }
        return sourceDirs;
    }

    /**
     * 解析 Ant build.xml 文件，提取源码路径
     * 格式示例：<javac srcdir="src" .../> 或 <src dir="src"/>
     */
    private List<Path> parseAntBuildXml(Path rootPath) {
        List<Path> sourceDirs = new ArrayList<>();

        Path buildXml = rootPath.resolve("build.xml");
        if (!Files.exists(buildXml)) {
            return sourceDirs;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(buildXml.toFile());

            Set<String> srcPaths = new HashSet<>();

            org.w3c.dom.NodeList javacNodes = doc.getElementsByTagName("javac");
            for (int i = 0; i < javacNodes.getLength(); i++) {
                String srcdir = getAttributeValue(javacNodes.item(i), "srcdir");
                if (srcdir != null && !srcdir.isEmpty()) {
                    srcPaths.add(srcdir);
                }
            }

            org.w3c.dom.NodeList srcNodes = doc.getElementsByTagName("src");
            for (int i = 0; i < srcNodes.getLength(); i++) {
                String dir = getAttributeValue(srcNodes.item(i), "dir");
                if (dir != null && !dir.isEmpty()) {
                    srcPaths.add(dir);
                }
            }

            org.w3c.dom.NodeList pathNodes = doc.getElementsByTagName("path");
            for (int i = 0; i < pathNodes.getLength(); i++) {
                org.w3c.dom.NodeList children = pathNodes.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    org.w3c.dom.Node child = children.item(j);
                    if ("pathelement".equals(child.getNodeName())) {
                        String location = getAttributeValue(child, "location");
                        if (location != null && location.contains("src")) {
                            srcPaths.add(location);
                        }
                    }
                }
            }

            for (String srcPath : srcPaths) {
                Path sourcePath = rootPath.resolve(srcPath);
                if (Files.isDirectory(sourcePath)) {
                    sourceDirs.add(sourcePath.toAbsolutePath().normalize());
                    log.debug("从 build.xml 解析出源码目录: {}", sourcePath);
                }
            }
        } catch (Exception e) {
            log.warn("解析 Ant build.xml 失败: {}", e.getMessage());
        }
        return sourceDirs;
    }

    /**
     * 去重并过滤源码目录列表
     */
    private List<Path> deduplicateAndFilter(List<Path> sourceDirs, Path rootPath) {
        log.info("去重前源码目录数: {}", sourceDirs.size());
        Set<Path> uniqueDirs = new LinkedHashSet<>(sourceDirs);
        log.info("去重后源码目录数: {}", uniqueDirs.size());

        List<Path> filtered = new ArrayList<>();
        for (Path dir : uniqueDirs) {
            boolean isExcluded = isExcludedPath(dir);
            boolean hasJava = hasJavaFiles(dir);
            if (!isExcluded && hasJava) {
                filtered.add(dir);
                log.debug("保留源码目录: {}", dir);
            } else {
                log.debug("过滤源码目录: {} (排除={}, 有Java文件={})", dir, isExcluded, hasJava);
            }
        }

        log.info("过滤后源码目录数: {}", filtered.size());

        filtered.sort(Comparator.comparing(p -> p.toString().length()));

        List<Path> result = new ArrayList<>();
        for (Path dir : filtered) {
            boolean isSubDir = false;
            for (Path existing : result) {
                if (dir.startsWith(existing)) {
                    isSubDir = true;
                    break;
                }
            }
            if (!isSubDir) {
                result.add(dir);
            } else {
                log.debug("跳过子目录: {} (已是 {} 的子目录)", dir, result.stream().filter(d -> dir.startsWith(d)).findFirst().orElse(null));
            }
        }
        log.info("去重过滤最终源码目录数: {}", result.size());
        return result;
    }

    /**
     * 兜底扫描：在项目根目录下搜索所有 .java 文件
     * 排除编译输出、依赖、生成文件等目录
     */
    private List<Path> collectJavaFilesFallback(Path rootPath) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || isExcludedPath(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        String relative = rootPath.relativize(file).toString().replace('\\', '/');
                        if (!isExcludedPath(file)) {
                            files.add(file.toAbsolutePath().normalize());
                        }
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
            log.error("遍历目录失败: {}", rootPath, e);
        }
        return removeDuplicates(files);
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
     * 判断目录是否包含 .java 文件
     */
    private boolean hasJavaFiles(Path directory) {
        try {
            if (!Files.exists(directory)) {
                log.debug("目录不存在: {}", directory);
                return false;
            }
            boolean hasJava = Files.walk(directory, 10)
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.toString().endsWith(".java"));
            log.debug("目录 {} 包含 Java 文件: {}", directory, hasJava);
            return hasJava;
        } catch (IOException e) {
            log.warn("检查目录是否包含 Java 文件时异常: {} - {}", directory, e.getMessage());
            return false;
        }
    }

    /**
     * 判断路径是否需要排除
     */
    private boolean isExcludedPath(Path path) {
        String pathStr = path.toString().replace('\\', '/');
        for (String excluded : EXCLUDED_DIRS) {
            if (pathStr.contains("/" + excluded + "/") || pathStr.endsWith("/" + excluded)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取 XML 节点的属性值
     */
    private String getAttributeValue(org.w3c.dom.Node node, String attrName) {
        if (node instanceof org.w3c.dom.Element) {
            org.w3c.dom.Element element = (org.w3c.dom.Element) node;
            return element.getAttribute(attrName);
        }
        return null;
    }

    /**
     * 移除重复路径
     */
    private List<Path> removeDuplicates(List<Path> paths) {
        return new ArrayList<>(new LinkedHashSet<>(paths));
    }

    /**
     * 检测项目类型
     * @return Maven / Gradle / Ant / Eclipse / Mixed / Unknown
     */
    public String detectProjectType(String projectRoot) {
        boolean hasMaven = isMavenProject(projectRoot);
        boolean hasGradle = isGradleProject(projectRoot);
        boolean hasAnt = isAntProject(projectRoot);
        boolean hasEclipse = isEclipseProject(projectRoot);

        List<String> types = new ArrayList<>();
        if (hasMaven) types.add("Maven");
        if (hasGradle) types.add("Gradle");
        if (hasAnt) types.add("Ant");
        if (hasEclipse) types.add("Eclipse");

        if (types.size() > 1) {
            return "Mixed (" + String.join(" + ", types) + ")";
        } else if (!types.isEmpty()) {
            return types.get(0);
        } else {
            return "Unknown (非标准项目结构)";
        }
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
     * 判断给定路径是否为 Ant 项目（存在 build.xml）
     */
    public boolean isAntProject(String projectRoot) {
        return Files.exists(Paths.get(projectRoot, "build.xml"));
    }

    /**
     * 判断给定路径是否为 Eclipse 项目（存在 .classpath 或 .project）
     */
    public boolean isEclipseProject(String projectRoot) {
        return Files.exists(Paths.get(projectRoot, ".classpath"))
                || Files.exists(Paths.get(projectRoot, ".project"));
    }

    /**
     * 判断给定路径是否为多模块项目
     */
    public boolean isMultiModuleProject(String projectRoot) {
        Path root = Paths.get(projectRoot);
        try {
            return Files.walk(root, 2)
                    .filter(Files::isDirectory)
                    .anyMatch(dir -> Files.exists(dir.resolve("pom.xml"))
                            || Files.exists(dir.resolve("build.gradle"))
                            || Files.exists(dir.resolve("build.xml")));
        } catch (IOException e) {
            return false;
        }
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
