package com.flowdesk.ticket.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.ticket.application.port.TicketOperatorRolePort;
import org.springframework.stereotype.Component;

/**
 * 用 IAM 侧的真实数据核验「当前仍持有 {@code IT_SUPPORT}」。
 *
 * <p>与 {@link IamTicketClaimantAdapter} 同一形状、同一方向：端口由工单模块声明，
 * 适配器放在工单模块的 {@code infrastructure}，只读 IAM 的表，不反向依赖 IAM 的服务。
 * 刻意只读 {@code id / display_name / status} 与角色关系，不碰密码列。</p>
 */
@Component
public class IamTicketOperatorRoleAdapter implements TicketOperatorRolePort {

    private final IamRoleMapper roleMapper;
    private final IamUserMapper userMapper;
    private final IamUserRoleMapper userRoleMapper;

    public IamTicketOperatorRoleAdapter(
            IamRoleMapper roleMapper,
            IamUserMapper userMapper,
            IamUserRoleMapper userRoleMapper) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    public boolean hasActiveItSupportRole(long userId) {
        // 角色编码不可修改，这里只需读它的 ID；不锁角色行——概览是只读查询，不该与领取串行
        IamRole itRole = roleMapper.selectOne(
                Wrappers.<IamRole>lambdaQuery()
                        .eq(IamRole::getCode, "IT_SUPPORT"));
        if (itRole == null) {
            return false;
        }

        IamUser user = userMapper.selectProfileById(userId);
        if (user == null || user.getStatus() != IamUserStatus.ENABLED) {
            return false;
        }

        return userRoleMapper.selectCount(
                Wrappers.<IamUserRole>lambdaQuery()
                        .eq(IamUserRole::getUserId, userId)
                        .eq(IamUserRole::getRoleId, itRole.getId())) > 0;
    }
}
