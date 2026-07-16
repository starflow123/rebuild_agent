package com.rebuild.agent.executor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.rebuild.agent.model.RefactorStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 代码修改器：基于 JavaParser 改写源码文件，执行具体的重构操作
 * <p>
 * 第三步（增量执行 + 回滚保障）的代码操作环节。
 * 支持提取新类、迁移方法、迁移字段、更新调用引用等原子操作。
 * 所有修改都通过 JavaParser 的 AST 操作完成，保证语法正确性。
 */
@Component
public class CodeModifier {

    private static final Logger log = LoggerFactory.getLogger(CodeModifier.class);

    private final JavaParser javaParser;

    public CodeModifier() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(config);
        log.info("CodeModifier JavaParser 已配置支持 Java 17 语法");
    }

    /**
     * 提取新类：从原类中抽取指定方法和字段，创建新类
     * @param sourceFile 原类源文件路径
     * @param sourceClassName 原类简单名
     * @param newClassName 新类简单名
     * @param packageName 新类所在包名
     * @param methodsToMove 待迁移的方法名列表
     * @param fieldsToMove 待迁移的字段名列表
     * @return 新创建的源文件路径
     */
    public Path extractClass(Path sourceFile, String sourceClassName, String newClassName,
                             String packageName, List<String> methodsToMove, List<String> fieldsToMove) {
        log.info("提取新类: {} -> {} (包: {})", sourceClassName, newClassName, packageName);

        // 1. 解析原文件
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        JavaParser parser = new JavaParser(config);
        CompilationUnit sourceCu;
        try {
            ParseResult<CompilationUnit> result = parser.parse(sourceFile);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                throw new RuntimeException("无法解析源文件: " + sourceFile + " - " + result.getProblems());
            }
            sourceCu = result.getResult().get();
        } catch (IOException e) {
            throw new RuntimeException("无法读取源文件: " + sourceFile, e);
        }

        // 2. 创建新类的 CompilationUnit
        CompilationUnit newCu = new CompilationUnit();
        newCu.setPackageDeclaration(packageName);

        // 复制所有 import
        sourceCu.getImports().forEach(newCu::addImport);

        // 创建新类声明
        ClassOrInterfaceDeclaration newClassDecl = newCu.addClass(newClassName);
        newClassDecl.setComment(new com.github.javaparser.ast.comments.LineComment(
                "由重构 Agent 从 " + sourceClassName + " 自动提取生成"));

        // 3. 获取原类
        Optional<ClassOrInterfaceDeclaration> sourceClassOpt = sourceCu.getClassByName(sourceClassName);
        if (sourceClassOpt.isEmpty()) {
            throw new RuntimeException("源文件中未找到类: " + sourceClassName);
        }
        ClassOrInterfaceDeclaration sourceClass = sourceClassOpt.get();

        // 4. 检测原类的Spring注解类型，应用到新类
        String springAnnotation = detectSpringAnnotation(sourceClass);
        newClassDecl.addAnnotation(springAnnotation);
        log.info("新类使用Spring注解: {}", springAnnotation);

        // 4. 收集待迁移的方法
        List<MethodDeclaration> methodsToCopy = new ArrayList<>();
        for (String methodName : methodsToMove) {
            Optional<MethodDeclaration> methodOpt = sourceClass.getMethodsByName(methodName).stream().findFirst();
            if (methodOpt.isPresent()) {
                methodsToCopy.add(methodOpt.get());
            } else {
                log.warn("未找到待迁移方法: {}", methodName);
            }
        }

        // 5. 自动检测方法依赖的字段，补充到迁移列表
        Set<String> dependentFields = new HashSet<>(fieldsToMove);
        Set<String> dependentPrivateMethods = new HashSet<>();
        Set<String> dependentConstants = new HashSet<>();
        for (MethodDeclaration method : methodsToCopy) {
            Set<String> usedFields = findUsedFieldsInMethod(method, sourceClass);
            dependentFields.addAll(usedFields);
            log.debug("方法 {} 依赖字段: {}", method.getNameAsString(), usedFields);

            Set<String> usedPrivateMethods = findUsedPrivateMethods(method, sourceClass);
            dependentPrivateMethods.addAll(usedPrivateMethods);
            log.debug("方法 {} 依赖私有方法: {}", method.getNameAsString(), usedPrivateMethods);

            Set<String> usedConstants = findUsedConstants(method, sourceClass);
            dependentConstants.addAll(usedConstants);
            log.debug("方法 {} 依赖常量: {}", method.getNameAsString(), usedConstants);
        }
        if (dependentFields.size() > fieldsToMove.size()) {
            log.info("自动检测到额外依赖字段: {}", dependentFields);
        }
        if (!dependentPrivateMethods.isEmpty()) {
            log.info("自动检测到依赖私有方法: {}", dependentPrivateMethods);
        }
        if (!dependentConstants.isEmpty()) {
            log.info("自动检测到依赖常量: {}", dependentConstants);
        }

        // 6. 迁移字段
        for (String fieldName : dependentFields) {
            for (FieldDeclaration field : new ArrayList<>(sourceClass.getFields())) {
                List<VariableDeclarator> variablesCopy = new ArrayList<>(field.getVariables());
                for (VariableDeclarator var : variablesCopy) {
                    if (var.getNameAsString().equals(fieldName)) {
                        FieldDeclaration copiedField = new FieldDeclaration();
                        field.getModifiers().forEach(m -> copiedField.addModifier(m.getKeyword()));
                        field.getAnnotations().forEach(a -> copiedField.addAnnotation(a.clone()));
                        copiedField.addVariable(var.clone());
                        newClassDecl.addMember(copiedField);
                        field.remove(var);
                        log.debug("迁移字段: {}", fieldName);
                    }
                }
                if (field.getVariables().isEmpty()) {
                    sourceClass.remove(field);
                }
            }
        }

        // 7. 迁移私有方法依赖
        for (String privateMethodName : dependentPrivateMethods) {
            Optional<MethodDeclaration> methodOpt = sourceClass.getMethodsByName(privateMethodName).stream().findFirst();
            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();
                MethodDeclaration copied = method.clone();
                newClassDecl.addMember(copied);
                sourceClass.remove(method);
                log.debug("迁移私有方法: {}", privateMethodName);
            }
        }

        // 8. 迁移常量依赖（静态final字段）
        for (String constantName : dependentConstants) {
            for (FieldDeclaration field : new ArrayList<>(sourceClass.getFields())) {
                List<VariableDeclarator> variablesCopy = new ArrayList<>(field.getVariables());
                for (VariableDeclarator var : variablesCopy) {
                    if (var.getNameAsString().equals(constantName)) {
                        FieldDeclaration copiedField = new FieldDeclaration();
                        field.getModifiers().forEach(m -> copiedField.addModifier(m.getKeyword()));
                        copiedField.addVariable(var.clone());
                        newClassDecl.addMember(copiedField);
                        field.remove(var);
                        log.debug("迁移常量: {}", constantName);
                    }
                }
                if (field.getVariables().isEmpty()) {
                    sourceClass.remove(field);
                }
            }
        }

        // 9. 迁移方法
        for (MethodDeclaration method : methodsToCopy) {
            MethodDeclaration copied = method.clone();
            newClassDecl.addMember(copied);
            sourceClass.remove(method);
            log.debug("迁移方法: {}", method.getNameAsString());
        }

        // 10. 写回原文件
        writeCuToFile(sourceCu, sourceFile);

        // 11. 写入新文件
        Path newFile = resolveSourceFile(sourceFile, packageName, newClassName);
        writeCuToFile(newCu, newFile);

        log.info("新类提取完成: {}", newFile);
        return newFile;
    }

    private Set<String> findUsedFieldsInMethod(MethodDeclaration method, ClassOrInterfaceDeclaration sourceClass) {
        Set<String> usedFields = new HashSet<>();
        Set<String> classFieldNames = sourceClass.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .collect(java.util.stream.Collectors.toSet());

        method.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();
            if (classFieldNames.contains(name)) {
                usedFields.add(name);
            }
        });

        return usedFields;
    }

    private String detectSpringAnnotation(ClassOrInterfaceDeclaration sourceClass) {
        Set<String> springAnnotations = Set.of(
                "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository",
                "org.springframework.stereotype.Controller",
                "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Configuration"
        );

        for (AnnotationExpr annotation : sourceClass.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            if (springAnnotations.contains(annotationName)) {
                return annotationName;
            }
            // 处理简化的注解名（不带包名）
            for (String fullAnnotation : springAnnotations) {
                if (fullAnnotation.endsWith("." + annotationName)) {
                    return fullAnnotation;
                }
            }
        }

        return "org.springframework.stereotype.Component";
    }

    private Set<String> findUsedPrivateMethods(MethodDeclaration method, ClassOrInterfaceDeclaration sourceClass) {
        Set<String> usedMethods = new HashSet<>();
        Set<String> privateMethodNames = sourceClass.getMethods().stream()
                .filter(m -> m.getModifiers().contains(Modifier.Keyword.PRIVATE))
                .map(m -> m.getNameAsString())
                .collect(java.util.stream.Collectors.toSet());

        method.findAll(MethodCallExpr.class).forEach(call -> {
            String methodName = call.getNameAsString();
            if (privateMethodNames.contains(methodName)) {
                usedMethods.add(methodName);
            }
        });

        return usedMethods;
    }

    private Set<String> findUsedConstants(MethodDeclaration method, ClassOrInterfaceDeclaration sourceClass) {
        Set<String> usedConstants = new HashSet<>();
        Set<String> constantNames = sourceClass.getFields().stream()
                .filter(f -> f.getModifiers().contains(Modifier.Keyword.STATIC) && 
                           f.getModifiers().contains(Modifier.Keyword.FINAL))
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .collect(java.util.stream.Collectors.toSet());

        method.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();
            if (constantNames.contains(name)) {
                usedConstants.add(name);
            }
        });

        return usedConstants;
    }

    /**
     * 迁移单个方法到目标类
     * @param sourceFile 源类文件
     * @param sourceClassName 源类名
     * @param methodName 方法名
     * @param targetFile 目标类文件
     * @param targetClassName 目标类名
     */
    public void moveMethod(Path sourceFile, String sourceClassName, String methodName,
                           Path targetFile, String targetClassName) {
        log.info("迁移方法: {}.{} -> {}", sourceClassName, methodName, targetClassName);

        CompilationUnit sourceCu = parseFile(sourceFile);
        CompilationUnit targetCu = parseFile(targetFile);
        if (sourceCu == null || targetCu == null) {
            throw new RuntimeException("无法解析源文件或目标文件");
        }

        Optional<ClassOrInterfaceDeclaration> sourceClassOpt = sourceCu.getClassByName(sourceClassName);
        Optional<ClassOrInterfaceDeclaration> targetClassOpt = targetCu.getClassByName(targetClassName);
        if (sourceClassOpt.isEmpty() || targetClassOpt.isEmpty()) {
            throw new RuntimeException("未找到源类或目标类");
        }

        // 查找并迁移方法
        Optional<MethodDeclaration> methodOpt = sourceClassOpt.get().getMethodsByName(methodName).stream().findFirst();
        if (methodOpt.isPresent()) {
            MethodDeclaration method = methodOpt.get();
            targetClassOpt.get().addMember(method.clone());
            sourceClassOpt.get().remove(method);
            log.debug("方法 {} 已迁移", methodName);
        } else {
            log.warn("未找到方法: {}", methodName);
        }

        // 复制源文件中方法用到的 import 到目标文件
        copyUsedImports(sourceCu, targetCu, methodOpt);

        writeCuToFile(sourceCu, sourceFile);
        writeCuToFile(targetCu, targetFile);
    }

    /**
     * 迁移字段到目标类
     */
    public void moveField(Path sourceFile, String sourceClassName, String fieldName,
                          Path targetFile, String targetClassName) {
        log.info("迁移字段: {}.{} -> {}", sourceClassName, fieldName, targetClassName);

        CompilationUnit sourceCu = parseFile(sourceFile);
        CompilationUnit targetCu = parseFile(targetFile);
        if (sourceCu == null || targetCu == null) {
            throw new RuntimeException("无法解析源文件或目标文件");
        }

        ClassOrInterfaceDeclaration sourceClass = sourceCu.getClassByName(sourceClassName)
                .orElseThrow(() -> new RuntimeException("未找到源类: " + sourceClassName));
        ClassOrInterfaceDeclaration targetClass = targetCu.getClassByName(targetClassName)
                .orElseThrow(() -> new RuntimeException("未找到目标类: " + targetClassName));

        boolean found = false;
        for (FieldDeclaration field : new ArrayList<>(sourceClass.getFields())) {
            for (VariableDeclarator var : new ArrayList<>(field.getVariables())) {
                if (var.getNameAsString().equals(fieldName)) {
                    FieldDeclaration newField = new FieldDeclaration();
                    field.getModifiers().forEach(m -> newField.addModifier(m.getKeyword()));
                    newField.addVariable(var.clone());
                    targetClass.addMember(newField);
                    field.remove(var);
                    found = true;
                    log.debug("字段 {} 已迁移", fieldName);
                }
            }
            if (field.getVariables().isEmpty()) {
                sourceClass.remove(field);
            }
        }
        if (!found) {
            log.warn("未找到字段: {}", fieldName);
        }

        writeCuToFile(sourceCu, sourceFile);
        writeCuToFile(targetCu, targetFile);
    }

    /**
     * 更新调用引用：将 oldTarget.method() 改为 newTarget.method()
     * @param fileToUpdate 待更新的源文件
     * @param oldTargetName 旧调用目标（字段名或类名）
     * @param newTargetName 新调用目标
     */
    public void updateMethodCallReferences(Path fileToUpdate, String oldTargetName, String newTargetName) {
        log.info("更新方法调用引用: {} -> {} (文件: {})", oldTargetName, newTargetName, fileToUpdate);

        CompilationUnit cu = parseFile(fileToUpdate);
        if (cu == null) {
            log.warn("无法解析文件: {}", fileToUpdate);
            return;
        }

        int updateCount = 0;
        // 遍历所有方法调用，更新 scope
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent()) {
                String scope = call.getScope().get().toString();
                if (scope.equals(oldTargetName)) {
                    call.setScope(new NameExpr(newTargetName));
                    updateCount++;
                }
            }
        }

        // 如果更新了字段声明名，遍历 NameExpr
        for (NameExpr nameExpr : cu.findAll(NameExpr.class)) {
            if (nameExpr.getNameAsString().equals(oldTargetName)) {
                nameExpr.setName(newTargetName);
                updateCount++;
            }
        }

        if (updateCount > 0) {
            writeCuToFile(cu, fileToUpdate);
            log.info("更新了 {} 处引用", updateCount);
        }
    }

    /**
     * 在指定类中添加字段（用于注入新拆分出的类依赖）
     */
    public void addField(Path file, String className, String fieldType, String fieldName, String annotation) {
        CompilationUnit cu = parseFile(file);
        if (cu == null) {
            return;
        }
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName(className).orElse(null);
        if (classDecl == null) {
            log.warn("未找到类: {}", className);
            return;
        }

        // 检查字段是否已存在
        boolean fieldExists = classDecl.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .anyMatch(v -> v.getNameAsString().equals(fieldName));
        if (fieldExists) {
            log.warn("字段已存在，跳过添加: {} {} (类: {})", fieldType, fieldName, className);
            return;
        }

        FieldDeclaration field = classDecl.addField(fieldType, fieldName, Modifier.Keyword.PRIVATE);
        if (annotation != null) {
            field.addAnnotation(annotation);
        }
        writeCuToFile(cu, file);
        log.info("已添加字段: {} {} (类: {})", fieldType, fieldName, className);
    }

    /**
     * 根据 RefactorStep 执行对应的代码修改
     */
    public List<Path> executeStep(RefactorStep step, Path projectRoot) {
        List<Path> modifiedFiles = new ArrayList<>();
        String targetFile = step.getTargetFile();
        String targetClass = step.getTargetClass();
        String methodName = step.getMethodName();

        if (targetFile == null || targetClass == null) {
            log.warn("步骤缺少必要参数: targetFile={}, targetClass={}", targetFile, targetClass);
            return modifiedFiles;
        }

        Path sourcePath = projectRoot.resolve(targetFile);

        switch (step.getStepType()) {
            case EXTRACT_CLASS -> {
                log.info("执行 EXTRACT_CLASS: {}", step.getDescription());
                String description = step.getDescription();
                String newClassName = extractNewClassName(description);
                String sourceClassName = extractSourceClassName(description);
                
                if (sourceClassName == null) {
                    sourceClassName = targetClass;
                }
                
                if (newClassName != null) {
                    String packageName = extractPackageName(sourcePath);
                    modifiedFiles.add(extractClass(sourcePath, sourceClassName, newClassName, packageName,
                            extractMethodNames(description), extractFieldNames(description)));
                    modifiedFiles.add(sourcePath);
                } else {
                    log.warn("无法从描述中提取新类名: {}", description);
                }
            }
            case MOVE_METHOD -> {
                log.info("执行 MOVE_METHOD: {}", step.getDescription());
                String targetClassName = extractTargetClassName(step.getDescription());
                if (targetClassName != null && methodName != null) {
                    Path targetPath = resolveSourceFile(sourcePath, extractPackageName(sourcePath), targetClassName);
                    moveMethod(sourcePath, targetClass, methodName, targetPath, targetClassName);
                    modifiedFiles.add(sourcePath);
                    modifiedFiles.add(targetPath);
                }
            }
            case MOVE_FIELD -> {
                log.info("执行 MOVE_FIELD: {}", step.getDescription());
                String fieldName = extractFieldName(step.getDescription());
                String targetClassName = extractTargetClassName(step.getDescription());
                if (fieldName != null && targetClassName != null) {
                    Path targetPath = resolveSourceFile(sourcePath, extractPackageName(sourcePath), targetClassName);
                    moveField(sourcePath, targetClass, fieldName, targetPath, targetClassName);
                    modifiedFiles.add(sourcePath);
                    modifiedFiles.add(targetPath);
                }
            }
            case UPDATE_REFERENCE -> {
                log.info("执行 UPDATE_REFERENCE: {}", step.getDescription());
                String[] names = extractOldNewNames(step.getDescription());
                if (names != null && names.length == 2) {
                    updateMethodCallReferences(sourcePath, names[0], names[1]);
                    modifiedFiles.add(sourcePath);
                }
            }
            default -> log.info("执行步骤: {}", step.getDescription());
        }
        return modifiedFiles;
    }

    private String extractNewClassName(String description) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("提取\\s+(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        pattern = java.util.regex.Pattern.compile("新类\\s*[:：]\\s*(\\w+)");
        matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        pattern = java.util.regex.Pattern.compile("提取(\\w+)新类");
        matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        pattern = java.util.regex.Pattern.compile("创建(\\w+)");
        matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractPackageName(Path sourceFile) {
        CompilationUnit cu = parseFile(sourceFile);
        if (cu != null && cu.getPackageDeclaration().isPresent()) {
            return cu.getPackageDeclaration().get().getNameAsString();
        }
        return "";
    }

    private List<String> extractMethodNames(String description) {
        List<String> names = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\w+)\\s*\\(");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!name.equals("new") && !name.equals("this") && !name.equals("super")) {
                names.add(name);
            }
        }
        return names;
    }

    private List<String> extractFieldNames(String description) {
        List<String> names = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("迁移\\s+(\\w+)\\s+字段");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private String extractTargetClassName(String description) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("到\\s+(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractSourceClassName(String description) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("从\\s+(\\w+)\\s+提取");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        pattern = java.util.regex.Pattern.compile("(\\w+)\\s+提取");
        matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractFieldName(String description) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("迁移\\s+(\\w+)\\s+字段");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String[] extractOldNewNames(String description) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("将\\s+(\\w+)\\s+改为\\s+(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    // ---- 工具方法 ----

    /**
     * 解析源文件为 CompilationUnit
     */
    private CompilationUnit parseFile(Path file) {
        try {
            ParserConfiguration config = new ParserConfiguration();
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            JavaParser parser = new JavaParser(config);
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult().get();
            }
            log.warn("解析文件失败: {} - {}", file, result.getProblems());
        } catch (IOException e) {
            log.error("读取文件失败: {}", file, e);
        }
        return null;
    }

    /**
     * 将 CompilationUnit 写回文件
     */
    private void writeCuToFile(CompilationUnit cu, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, cu.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("写入文件失败: {}", file, e);
            throw new RuntimeException("写入文件失败: " + file, e);
        }
    }

    /**
     * 根据包名和类名解析源文件路径
     */
    private Path resolveSourceFile(Path existingFile, String packageName, String className) {
        Path srcJavaRoot = findSourceRoot(existingFile);
        Path packageDir = srcJavaRoot;
        if (packageName != null && !packageName.isEmpty()) {
            packageDir = srcJavaRoot.resolve(packageName.replace('.', '/'));
        }
        return packageDir.resolve(className + ".java");
    }

    /**
     * 从现有文件路径推断 src/main/java 根目录
     */
    private Path findSourceRoot(Path sourceFile) {
        Path normalized = sourceFile.toAbsolutePath().normalize();
        String pathStr = normalized.toString().replace('\\', '/');
        int idx = pathStr.indexOf("src/main/java");
        if (idx >= 0) {
            return Paths.get(pathStr.substring(0, idx + "src/main/java".length()));
        }
        // 回退：取文件所在目录
        return normalized.getParent();
    }

    /**
     * 复制方法用到的 import 到目标文件（简化版：复制全部 import）
     */
    private void copyUsedImports(CompilationUnit sourceCu, CompilationUnit targetCu, Optional<MethodDeclaration> methodOpt) {
        if (methodOpt.isEmpty()) {
            return;
        }
        // 简化处理：将源文件所有 import 复制到目标文件（去重）
        sourceCu.getImports().forEach(imp -> {
            boolean exists = targetCu.getImports().stream()
                    .anyMatch(i -> i.getNameAsString().equals(imp.getNameAsString()));
            if (!exists) {
                targetCu.addImport(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk());
            }
        });
    }
}
