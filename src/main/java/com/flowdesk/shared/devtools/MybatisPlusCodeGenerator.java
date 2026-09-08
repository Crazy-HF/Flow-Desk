package com.flowdesk.shared.devtools;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Development-only MyBatis-Plus scaffold generator.
 *
 * <p>Run this class from IntelliJ and provide table names as program arguments, for example:
 * {@code iam_user iam_role iam_permission}. It generates entity, Mapper, Mapper XML, Service,
 * ServiceImpl and Controller skeletons but never overwrites existing files.</p>
 *
 * <p>Connection settings are read first from JVM system properties, then environment variables,
 * then the local untracked {@code .env} file. No database credentials are hard-coded or logged.</p>
 */
public final class MybatisPlusCodeGenerator {

    private static final String BASE_PACKAGE = "com.flowdesk";
    private static final String AUTHOR = "Crazy-HF";
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Properties DOT_ENV = loadDotEnv();

    private MybatisPlusCodeGenerator() {
    }

    public static void main(String[] args) {
        List<String> tables;

        // 1. 如果命令行有参数，则使用参数
        if (args.length > 0) {
            tables = parseTables(args);
        } else {
            // 2. 否则交互式提示输入
            System.out.print("请输入要生成的表名（多个用空格或逗号分隔）：");
            try (Scanner scanner = new Scanner(System.in)) {
                String line = scanner.nextLine().trim();
                if (line.isBlank()) {
                    throw new IllegalArgumentException("表名不能为空，请重新运行并输入表名。");
                }
                // 将一行输入拆分成多个表名（兼容空格和逗号）
                tables = parseTables(line.split("\\s+|,\\s*"));
            }
        }

        if (tables.isEmpty()) {
            throw new IllegalArgumentException("请传入至少一个表名，例如：iam_user iam_role iam_permission");
        }

        Map<String, List<String>> tablesByModule = tables.stream()
                .collect(Collectors.groupingBy(MybatisPlusCodeGenerator::moduleOf,
                        LinkedHashMap::new, Collectors.toList()));

        String url = required("FLOWDESK_DB_URL");
        String username = required("FLOWDESK_DB_USERNAME");
        String password = required("FLOWDESK_DB_PASSWORD");

        tablesByModule.forEach((module, moduleTables) -> generate(url, username, password, module, moduleTables));
    }

    private static void generate(String url, String username, String password, String module, Collection<String> tables) {
        Path javaOutput = PROJECT_ROOT.resolve("src/main/java");
        Path xmlOutput = PROJECT_ROOT.resolve("src/main/resources/mapper").resolve(module);

        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> builder
                        .author(AUTHOR)
                        .disableOpenDir()
                        .outputDir(javaOutput.toString()))
                .packageConfig(builder -> builder
                        .parent(BASE_PACKAGE)
                        .moduleName(module)
                        .entity("domain")
                        .mapper("mapper")
                        .service("service")
                        .serviceImpl("service.impl")
                        .controller("controller")
                        .pathInfo(Map.of(OutputFile.xml, xmlOutput.toString())))
                .strategyConfig(builder -> builder
                        .addInclude(tables.toArray(String[]::new))
                        .entityBuilder()
                        .enableLombok()
                        .enableTableFieldAnnotation()
                        .idType(IdType.AUTO)
                        .mapperBuilder()
                        .enableMapperAnnotation()
                        .enableBaseResultMap()
                        .enableBaseColumnList()
                        .serviceBuilder()
                        .formatServiceFileName("%sService")
                        .formatServiceImplFileName("%sServiceImpl")
                        .controllerBuilder()
                        .enableRestStyle())
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();
    }

    private static List<String> parseTables(String[] args) {
        return Arrays.stream(args)
                .flatMap(argument -> Arrays.stream(argument.split(",")))
                .map(String::trim)
                .filter(table -> !table.isBlank())
                .distinct()
                .toList();
    }

    private static String moduleOf(String table) {
        if (table.startsWith("iam_")) {
            return "iam";
        }
        if (table.equals("ticket_category")) {
            return "category";
        }
        if (table.startsWith("ticket_")) {
            return "ticket";
        }
        throw new IllegalArgumentException("未配置表所属模块：" + table);
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        if (value == null || value.isBlank()) {
            value = DOT_ENV.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少数据库配置：" + key + "。请在环境变量、JVM 参数或 .env 中配置。");
        }
        return value;
    }

    private static Properties loadDotEnv() {
        Properties properties = new Properties();
        Path dotEnv = PROJECT_ROOT.resolve(".env");
        if (!Files.isRegularFile(dotEnv)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(dotEnv, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地 .env 文件", exception);
        }
    }
}
