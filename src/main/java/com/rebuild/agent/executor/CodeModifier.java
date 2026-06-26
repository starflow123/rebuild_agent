package com.rebuild.agent.executor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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

    private final JavaParser javaParser = new JavaParser();

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
        CompilationUnit sourceCu = parseFile(sourceFile);
        if (sourceCu == null) {
            throw new RuntimeException("无法解析源文件: " + sourceFile);
        }

        // 2. 创建新类的 CompilationUnit
        CompilationUnit newCu = new CompilationUnit();
        newCu.setPackageDeclaration(packageName);

        // 复制必要的 import（简化处理：复制全部 import）
        sourceCu.getImports().forEach(newCu::addImport);

        // 创建新类声明
        ClassOrInterfaceDeclaration newClassDecl = newCu.addClass(newClassName);
        // 添加 @Component 注解（Spring 习惯）
        newClassDecl.addAnnotation("org.springframework.stereotype.Component");
        newClassDecl.setComment(new com.github.javaparser.ast.comments.LineComment(
                "由重构 Agent 从 " + sourceClassName + " 自动提取生成"));

        // 3. 从原类中移除并迁移方法
        Optional<ClassOrInterfaceDeclaration> sourceClassOpt = sourceCu.getClassByName(sourceClassName);
        if (sourceClassOpt.isEmpty()) {
            throw new RuntimeException("源文件中未找到类: " + sourceClassName);
        }
        ClassOrInterfaceDeclaration sourceClass = sourceClassOpt.get();

        for (String methodName : methodsToMove) {
            Optional<MethodDeclaration> methodOpt = sourceClass.getMethodsByName(methodName).stream().findFirst();
            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();
                // 复制方法到新类
                MethodDeclaration copied = method.clone();
                newClassDecl.addMember(copied);
                // 从原类移除
                sourceClass.remove(method);
                log.debug("迁移方法: {}", methodName);
            } else {
                log.warn("未找到待迁移方法: {}", methodName);
            }
        }

        // 4. 从原类中移除并迁移字段
        for (String fieldName : fieldsToMove) {
            for (FieldDeclaration field : new ArrayList<>(sourceClass.getFields())) {
                for (VariableDeclarator var : field.getVariables()) {
                    if (var.getNameAsString().equals(fieldName)) {
                        // 复制字段到新类
                        FieldDeclaration copiedField = field.clone();
                        copiedField.getVariables().clear();
                        copiedField.addVariable(var.clone());
                        newClassDecl.addMember(copiedField);
                        // 从原类移除该变量
                        field.remove(var);
                        log.debug("迁移字段: {}", fieldName);
                    }
                }
            }
        }

        // 5. 写回原文件
        writeCuToFile(sourceCu, sourceFile);

        // 6. 写入新文件
        Path newFile = resolveSourceFile(sourceFile, packageName, newClassName);
        writeCuToFile(newCu, newFile);

        log.info("新类提取完成: {}", newFile);
        return newFile;
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
                    // 复制修饰符
                    field.getModifiers().forEach(m -> newField.addModifier(m.getKeyword()));
                    newField.addVariable(var.clone());
                    newField.setVariables(field.getVariables());
                    targetClass.addMember(newField.clone());
                    field.remove(var);
                    found = true;
                    log.debug("字段 {} 已迁移", fieldName);
                }
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
        switch (step.getStepType()) {
            case EXTRACT_CLASS -> {
                // 简化处理：实际参数从 step 的 description 解析或由 Agent 提供
                log.info("执行 EXTRACT_CLASS: {}", step.getDescription());
                // 具体参数由 Agent 工具直接调用 extractClass 方法传入
            }
            case MOVE_METHOD -> log.info("执行 MOVE_METHOD: {}", step.getDescription());
            case MOVE_FIELD -> log.info("执行 MOVE_FIELD: {}", step.getDescription());
            case UPDATE_REFERENCE -> log.info("执行 UPDATE_REFERENCE: {}", step.getDescription());
            default -> log.info("执行步骤: {}", step.getDescription());
        }
        return modifiedFiles;
    }

    // ---- 工具方法 ----

    /**
     * 解析源文件为 CompilationUnit
     */
    private CompilationUnit parseFile(Path file) {
        try {
            ParseResult<CompilationUnit> result = javaParser.parse(file);
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
