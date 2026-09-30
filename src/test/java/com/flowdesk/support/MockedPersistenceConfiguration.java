package com.flowdesk.support;

import com.flowdesk.category.mapper.TicketCategoryMapper;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 公共 Spring 测试上下文的持久化替身。
 *
 * <p>这些测试会排除 MyBatis-Plus 自动配置，因此 Mapper 必须以替身提供，否则上下文无法启动。
 * 替身只替代数据库访问，不替代业务规则。</p>
 *
 * <p><strong>覆盖范围是"全部 {@code @Mapper} 接口"，不是"当前用得上的那几个"。</strong>
 * 这类 Web 测试用 {@code @SpringBootTest(classes = FlowDeskApplication.class)} 起完整上下文，
 * 组件扫描会把所有 {@code @Service} 都实例化（IAM 五个服务、分类服务、工单创建与查询服务）。
 * Mapper 靠 MyBatis 自动配置按 {@code @Mapper} 注解注册，而这里把它排除了，所以**任何缺少替身的
 * Mapper 都会让整个上下文启动失败**——报错点是注入它的服务，不是那个 Mapper 本身，
 * 表现为"新增一个模块后，一大堆与它无关的旧测试一起挂掉"（2026-09-29 实测：分类与工单四个
 * Mapper 缺失，394 项里 185 个上下文错误）。新增 Mapper 时请同步在这里补一个替身。</p>
 *
 * <p>Redis 会话仓储无法用同样的方式替换（组件扫描出的实现仍要求 Redis 连接），
 * 需要在各测试中用 {@code @MockitoBean} 声明。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockedPersistenceConfiguration {

    /** 工单创建服务使用 TransactionTemplate；排除数据源的 Web 上下文需提供事务管理器替身。 */
    @Bean
    PlatformTransactionManager platformTransactionManager() {
        return Mockito.mock(PlatformTransactionManager.class);
    }

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

    /** 分类服务（TASK-063）依赖。 */
    @Bean
    TicketCategoryMapper ticketCategoryMapper() {
        return Mockito.mock(TicketCategoryMapper.class);
    }

    /** 工单创建与查询服务依赖；列表、详情、时间线都走它。 */
    @Bean
    TicketMapper ticketMapper() {
        return Mockito.mock(TicketMapper.class);
    }

    /** 工单不可变记录（时间线与创建时写入首条记录）依赖。 */
    @Bean
    TicketRecordMapper ticketRecordMapper() {
        return Mockito.mock(TicketRecordMapper.class);
    }

    /** 领取工单写入历史 IT 参与关系时依赖。 */
    @Bean
    TicketParticipantMapper ticketParticipantMapper() {
        return Mockito.mock(TicketParticipantMapper.class);
    }

    /** 每日工单编号序号依赖。 */
    @Bean
    TicketDailySequenceMapper ticketDailySequenceMapper() {
        return Mockito.mock(TicketDailySequenceMapper.class);
    }
}
