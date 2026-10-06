package com.flowdesk.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示种子数据的 Profile 边界：**公共演示口令不进生产**。
 *
 * <p>锁死的规则来自 {@code src/main/resources/db/demo/R__seed_demo_data.sql} 的文件头注释：
 * 它建了 {@code employee} / {@code it} / {@code admin} 三个账号，密码统一为公开的 {@code 123456}，
 * 并在文件头写明「仅限本地/演示环境，禁止用于任何真实环境」。这条约束无法靠代码评审长期维持，
 * 唯一真实依据就是 {@code spring.flyway.locations}：只要它不含 {@code classpath:db/demo}，
 * Flyway 就不会扫描、更不会执行那个种子脚本。因此本类把 Profile 与迁移位置的对应关系钉死在测试里。</p>
 *
 * <p>这里只加载 YAML 属性源，不启动 Spring 上下文、不连接数据库：
 * 被测对象是"配置文件的字面内容"，起上下文反而会把 Profile 合并规则重新引入一遍，
 * 让断言依赖 Spring 的行为而不是配置本身。{@code spring.profiles.group.local=demo} 单独断言，
 * 因为它是"本地怎么拿到演示数据"的唯一合法入口。</p>
 *
 * <p>只覆盖本地文件，不覆盖部署侧的环境变量覆盖（{@code SPRING_FLYWAY_LOCATIONS} 仍可改变生产行为），
 * 那属于部署配置的检查范围。</p>
 */
class DemoSeedProfileTest {

    private static final String BASE = "application.yml";
    private static final String DEMO = "application-demo.yml";
    private static final String PROD = "application-prod.yml";
    private static final String TEST = "application-test.yml";

    private static final String FLYWAY_LOCATIONS = "spring.flyway.locations";
    private static final String COMMON_LOCATION = "classpath:db/migration";
    private static final String DEMO_LOCATION = "classpath:db/demo";

    @Test
    void baseConfigurationMigratesOnlyTheCommonLocationAndKeepsDemoBehindItsOwnProfile() {
        assertThat(flywayLocations(BASE))
                .as("公共迁移位置不能包含演示种子目录")
                .containsExactly(COMMON_LOCATION);

        assertThat(flywayLocations("application-local.yml"))
                .as("local 自身不扫描演示目录，演示数据只能靠 profile group 引入")
                .doesNotContain(DEMO_LOCATION);

        assertThat(property(BASE, "spring.profiles.group.local"))
                .as("本地演示数据的唯一入口是 local → demo 的分组")
                .isEqualTo("demo");
    }

    @Test
    void demoProfileIsTheOnlyPlaceThatAddsTheDemoLocation() {
        assertThat(flywayLocations(DEMO))
                .as("演示 Profile 必须显式追加演示目录")
                .containsExactly(COMMON_LOCATION, DEMO_LOCATION);

        assertThat(effectiveLocations(DEMO)).contains(DEMO_LOCATION);
    }

    @Test
    void prodAndTestProfilesNeverScanTheDemoLocation() {
        assertThat(resourceExists(PROD)).as("profile 文件存在，避免空属性让断言失效").isTrue();
        assertThat(resourceExists(TEST)).as("profile 文件存在，避免空属性让断言失效").isTrue();

        assertThat(flywayLocations(PROD)).doesNotContain(DEMO_LOCATION);
        assertThat(flywayLocations(TEST)).doesNotContain(DEMO_LOCATION);

        assertThat(effectiveLocations(PROD))
                .as("prod 生效位置只应来自基础配置，公共演示口令不会入库")
                .containsExactly(COMMON_LOCATION);
        assertThat(effectiveLocations(TEST))
                .as("test 生效位置只应来自基础配置")
                .containsExactly(COMMON_LOCATION);
    }

    /** Profile 文件覆盖同名属性时整项替换，因此"没写"等于继续用基础配置。 */
    private static List<String> effectiveLocations(String profileFile) {
        List<String> override = flywayLocations(profileFile);
        return override.isEmpty() ? flywayLocations(BASE) : override;
    }

    /** 读取某个 YAML 的属性并归一化为字符串列表；缺失时返回空列表。 */
    private static List<String> flywayLocations(String yamlFile) {
        String value = property(yamlFile, FLYWAY_LOCATIONS);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(location -> !location.isEmpty())
                .toList();
    }

    private static String property(String yamlFile, String name) {
        for (PropertySource<?> source : load(yamlFile)) {
            Object value = source.getProperty(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static List<PropertySource<?>> load(String yamlFile) {
        try {
            return new YamlPropertySourceLoader()
                    .load(yamlFile, new ClassPathResource(yamlFile));
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载配置：" + yamlFile, exception);
        }
    }

    private static boolean resourceExists(String yamlFile) {
        return new ClassPathResource(yamlFile).exists();
    }
}
