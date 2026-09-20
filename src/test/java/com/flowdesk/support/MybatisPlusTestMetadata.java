package com.flowdesk.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单元测试中构建 MyBatis-Plus 条件包装器所需的实体元数据。
 *
 * <p>服务在构建 {@code LambdaQueryWrapper} 时会立即解析字段与列名，因此没有真实 MyBatis 上下文时
 * 也必须先注册实体元数据。</p>
 */
public final class MybatisPlusTestMetadata {

    private MybatisPlusTestMetadata() {
    }

    public static void initialize(Class<?>... entityTypes) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entityType : entityTypes) {
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
