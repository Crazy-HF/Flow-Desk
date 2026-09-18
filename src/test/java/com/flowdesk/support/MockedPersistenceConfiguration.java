package com.flowdesk.support;

import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 公共 Spring 测试上下文的持久化替身。
 *
 * <p>这些测试会排除 MyBatis-Plus 自动配置，因此 IAM 服务依赖的 Mapper 必须以替身提供，
 * 否则上下文无法启动。替身只替代数据库访问，不替代业务规则。</p>
 *
 * <p>Redis 会话仓储无法用同样的方式替换（组件扫描出的实现仍要求 Redis 连接），
 * 需要在各测试中用 {@code @MockitoBean} 声明。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockedPersistenceConfiguration {

    @Bean
    IamUserMapper iamUserMapper() {
        return Mockito.mock(IamUserMapper.class);
    }

    @Bean
    IamRoleMapper iamRoleMapper() {
        return Mockito.mock(IamRoleMapper.class);
    }

    @Bean
    IamPermissionMapper iamPermissionMapper() {
        return Mockito.mock(IamPermissionMapper.class);
    }

    @Bean
    IamUserRoleMapper iamUserRoleMapper() {
        return Mockito.mock(IamUserRoleMapper.class);
    }

    @Bean
    IamRolePermissionMapper iamRolePermissionMapper() {
        return Mockito.mock(IamRolePermissionMapper.class);
    }
}
