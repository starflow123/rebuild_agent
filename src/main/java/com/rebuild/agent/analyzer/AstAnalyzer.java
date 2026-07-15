package com.rebuild.agent.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.rebuild.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * AST 分析器：基于 JavaParser 解析单个 Java 源文件，提取类/方法/字段节点及其依赖信息
 * <p>
 * 第一步（静态分析引擎）的核心：将源码 AST 转换为结构化的图谱节点。
 * 通过 import 语句建立简单类名到全限定名的映射，实现类型解析（无需完整 classpath）。
 */
@Component
public class AstAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AstAnalyzer.class);

    private final JavaParser javaParser;

    public AstAnalyzer() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(config);
        log.info("JavaParser 已配置支持 Java 17 语法");
    }

    /**
     * 解析单个 Java 源文件，提取其中所有类节点
     * @param sourceFile 源文件路径
     * @return 该文件中定义的类节点列表（一个文件可能包含多个类）
     */
    public List<ClassNode> analyzeFile(Path sourceFile) {
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.warn("解析文件失败: {}", sourceFile);
                return Collections.emptyList();
            }
            CompilationUnit cu = parseResult.getResult().get();
            return analyzeCompilationUnit(cu, sourceFile);
        } catch (Exception e) {
            log.error("解析文件异常: {} - {}", sourceFile, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析 CompilationUnit，提取类节点
     */
    private List<ClassNode> analyzeCompilationUnit(CompilationUnit cu, Path sourceFile) {
        // 构建 import 映射：简单类名 -> 全限定名
        Map<String, String> importMap = buildImportMap(cu);
        // 当前包名
        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        // 源文件总行数
        int totalLines = cu.getEnd().map(e -> e.line).orElse(0);

        List<ClassNode> classNodes = new ArrayList<>();
        // 遍历顶层类型声明（类/接口/枚举）
        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration coid = (ClassOrInterfaceDeclaration) typeDecl;
                classNodes.add(buildClassNode(coid, packageName, importMap, sourceFile, totalLines));
            } else if (typeDecl instanceof EnumDeclaration) {
                // 枚举也作为类节点处理
                classNodes.add(buildEnumNode((EnumDeclaration) typeDecl, packageName, importMap, sourceFile, totalLines));
            }
        }
        return classNodes;
    }

    /**
     * 构建 import 映射，用于将简单类名解析为全限定名
     */
    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> importMap = new HashMap<>();
        for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
            String qualifiedName = imp.getNameAsString();
            // 静态 import：取最后一段作为简单名
            if (imp.isStatic()) {
                String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
                importMap.put(simpleName, qualifiedName);
            } else {
                String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
                importMap.put(simpleName, qualifiedName);
                // 通配 import：java.util.*
                if (imp.isAsterisk()) {
                    importMap.put(qualifiedName.substring(0, qualifiedName.length() - 1), qualifiedName);
                }
            }
        }
        return importMap;
    }

    /**
     * 从类声明构建 ClassNode
     */
    private ClassNode buildClassNode(ClassOrInterfaceDeclaration coid, String packageName,
                                     Map<String, String> importMap, Path sourceFile, int fileLines) {
        String qualifiedName = packageName.isEmpty()
                ? coid.getNameAsString()
                : packageName + "." + coid.getNameAsString();

        ClassNode node = new ClassNode(qualifiedName);
        node.setSourceFilePath(sourceFile.toString().replace('\\', '/'));
        node.setClassType(coid.isInterface() ? "INTERFACE"
                : (coid.isAbstract() ? "ABSTRACT" : "CLASS"));
        node.setLineCount(fileLines);

        // 父类
        coid.getExtendedTypes().forEach(t -> {
            String superQName = resolveType(t, packageName, importMap);
            node.setSuperClass(superQName);
        });

        // 实现的接口
        coid.getImplementedTypes().forEach(t -> {
            String ifaceQName = resolveType(t, packageName, importMap);
            node.getInterfaces().add(ifaceQName);
        });

        // 类注解
        coid.getAnnotations().forEach(ann -> {
            String annQName = resolveAnnotation(ann, packageName, importMap);
            node.getAnnotations().add(annQName);
        });

        // 字段
        for (FieldDeclaration field : coid.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                node.getFields().add(buildFieldNode(var, field.getModifiers(), qualifiedName, packageName, importMap));
            }
        }

        // 方法
        for (MethodDeclaration method : coid.getMethods()) {
            node.getMethods().add(buildMethodNode(method, qualifiedName, packageName, importMap));
        }

        // 圈复杂度近似计算
        node.setCyclomaticComplexity(calculateComplexity(coid));

        return node;
    }

    /**
     * 从枚举声明构建 ClassNode
     */
    private ClassNode buildEnumNode(EnumDeclaration enumDecl, String packageName,
                                    Map<String, String> importMap, Path sourceFile, int fileLines) {
        String qualifiedName = packageName.isEmpty()
                ? enumDecl.getNameAsString()
                : packageName + "." + enumDecl.getNameAsString();

        ClassNode node = new ClassNode(qualifiedName);
        node.setSourceFilePath(sourceFile.toString().replace('\\', '/'));
        node.setClassType("ENUM");
        node.setLineCount(fileLines);

        // 枚举实现的接口
        enumDecl.getImplementedTypes().forEach(t -> {
            node.getInterfaces().add(resolveType(t, packageName, importMap));
        });

        // 枚举方法
        for (MethodDeclaration method : enumDecl.getMethods()) {
            node.getMethods().add(buildMethodNode(method, qualifiedName, packageName, importMap));
        }
        return node;
    }

    /**
     * 构建字段节点
     */
    private FieldNode buildFieldNode(VariableDeclarator var, NodeList<Modifier> modifiers,
                                     String ownerClass, String packageName, Map<String, String> importMap) {
        FieldNode field = new FieldNode();
        field.setOwnerClass(ownerClass);
        field.setFieldName(var.getNameAsString());
        field.setFieldType(resolveType(var.getType(), packageName, importMap));
        // 解析访问修饰符
        String accessModifier = "default";
        boolean isStatic = false;
        boolean isFinal = false;
        for (Modifier mod : modifiers) {
            if (mod.getKeyword() == Modifier.Keyword.PUBLIC
                    || mod.getKeyword() == Modifier.Keyword.PRIVATE
                    || mod.getKeyword() == Modifier.Keyword.PROTECTED) {
                accessModifier = mod.getKeyword().asString();
            }
            if (mod.getKeyword() == Modifier.Keyword.STATIC) {
                isStatic = true;
            }
            if (mod.getKeyword() == Modifier.Keyword.FINAL) {
                isFinal = true;
            }
        }
        field.setAccessModifier(accessModifier);
        field.setStatic(isStatic);
        field.setFinal(isFinal);
        var.getBegin().ifPresent(p -> field.setLine(p.line));
        return field;
    }

    /**
     * 构建方法节点，并提取方法体内的依赖信息
     */
    private MethodNode buildMethodNode(MethodDeclaration method, String ownerClass,
                                       String packageName, Map<String, String> importMap) {
        MethodNode methodNode = new MethodNode();
        methodNode.setOwnerClass(ownerClass);
        methodNode.setMethodName(method.getNameAsString());

        // 返回值类型
        methodNode.setReturnType(resolveType(method.getType(), packageName, importMap));

        // 参数类型
        method.getParameters().forEach(p -> {
            methodNode.getParameterTypes().add(resolveType(p.getType(), packageName, importMap));
        });

        // 签名
        methodNode.setSignature(method.getDeclarationAsString(false, false, false));

        // 访问修饰符
        method.getModifiers().stream().findFirst().ifPresent(m ->
                methodNode.setAccessModifier(m.getKeyword().asString()));
        methodNode.setStatic(method.isStatic());

        // 行号范围
        method.getBegin().ifPresent(p -> methodNode.setStartLine(p.line));
        method.getEnd().ifPresent(p -> methodNode.setEndLine(p.line));
        methodNode.setLineCount(methodNode.getEndLine() - methodNode.getStartLine() + 1);

        // 提取方法体内的依赖：方法调用、字段访问、对象实例化
        if (method.getBody().isPresent()) {
            BlockStmt body = method.getBody().get();
            extractMethodDependencies(body, methodNode, ownerClass, packageName, importMap);
        }

        // 方法圈复杂度
        methodNode.setCyclomaticComplexity(calculateMethodComplexity(method));

        return methodNode;
    }

    /**
     * 从方法体提取依赖信息：方法调用、字段访问、对象实例化
     */
    private void extractMethodDependencies(BlockStmt body, MethodNode methodNode,
                                           String ownerClass, String packageName, Map<String, String> importMap) {
        // 方法调用
        body.findAll(MethodCallExpr.class).forEach(call -> {
            String callTarget = call.getScope()
                    .map(s -> s.toString())
                    .orElse("this");
            String calledMethod = callTarget + "." + call.getNameAsString();
            methodNode.getCalledMethods().add(calledMethod);
        });

        // 字段访问（NameExpr 可能是字段引用）
        body.findAll(NameExpr.class).forEach(name -> {
            methodNode.getAccessedFields().add(name.getNameAsString());
        });

        // 对象实例化
        body.findAll(ObjectCreationExpr.class).forEach(creation -> {
            String instantiated = resolveType(creation.getType(), packageName, importMap);
            methodNode.getInstantiatedClasses().add(instantiated);
        });
    }

    /**
     * 解析类型为全限定名
     * 优先从 import 映射查找，找不到则拼接当前包名
     */
    private String resolveType(ClassOrInterfaceType type, String packageName, Map<String, String> importMap) {
        String simpleName = type.getNameAsString();
        // 处理嵌套泛型，只取外层类型名
        return resolveSimpleName(simpleName, packageName, importMap);
    }

    /**
     * 解析 com.github.javaparser.ast.type.Type 为全限定名
     */
    private String resolveType(com.github.javaparser.ast.type.Type type, String packageName, Map<String, String> importMap) {
        if (type instanceof ClassOrInterfaceType) {
            return resolveType((ClassOrInterfaceType) type, packageName, importMap);
        }
        return type.toString();
    }

    /**
     * 解析简单类名为全限定名
     */
    private String resolveSimpleName(String simpleName, String packageName, Map<String, String> importMap) {
        // java.lang 包下的常用类自动识别
        if (isJavaLangClass(simpleName)) {
            return "java.lang." + simpleName;
        }
        // 从 import 映射查找
        if (importMap.containsKey(simpleName)) {
            return importMap.get(simpleName);
        }
        // 同包类
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * 解析注解为全限定名
     */
    private String resolveAnnotation(AnnotationExpr ann, String packageName, Map<String, String> importMap) {
        String name = ann.getNameAsString();
        return resolveSimpleName(name, packageName, importMap);
    }

    /**
     * 判断是否为 java.lang 包下的常用类（无需 import）
     */
    private boolean isJavaLangClass(String simpleName) {
        return Set.of("String", "Integer", "Long", "Double", "Float", "Boolean", "Character",
                "Byte", "Short", "Object", "System", "Math", "Exception", "RuntimeException",
                "Throwable", "Error", "Class", "Number", "Comparable", "Iterable",
                "Void", "Override", "Deprecated", "SuppressWarnings").contains(simpleName);
    }

    /**
     * 计算类的圈复杂度（近似）：所有方法的圈复杂度之和
     */
    private int calculateComplexity(ClassOrInterfaceDeclaration coid) {
        return coid.getMethods().stream()
                .mapToInt(this::calculateMethodComplexity)
                .sum();
    }

    /**
     * 计算单个方法的圈复杂度（近似）
     * 基础为 1，每个分支语句（if/for/while/switch-case/catch/&&/||/?:）加 1
     */
    private int calculateMethodComplexity(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return 1;
        }
        BlockStmt body = method.getBody().get();
        int complexity = 1;
        complexity += body.findAll(IfStmt.class).size();
        complexity += body.findAll(ForStmt.class).size();
        complexity += body.findAll(ForEachStmt.class).size();
        complexity += body.findAll(WhileStmt.class).size();
        complexity += body.findAll(DoStmt.class).size();
        complexity += body.findAll(CatchClause.class).size();
        // switch case
        for (SwitchEntry entry : body.findAll(SwitchEntry.class)) {
            if (!entry.getLabels().isEmpty()) {
                complexity++;
            }
        }
        // 三元运算符
        complexity += body.findAll(ConditionalExpr.class).size();
        // 逻辑运算符 && 和 ||
        String bodyStr = body.toString();
        complexity += countOccurrences(bodyStr, "&&");
        complexity += countOccurrences(bodyStr, "||");
        return complexity;
    }

    /**
     * 统计子串出现次数
     */
    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
