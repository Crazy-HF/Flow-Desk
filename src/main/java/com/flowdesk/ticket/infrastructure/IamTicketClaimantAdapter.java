package com.flowdesk.ticket.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import org.springframework.stereotype.Component;

@Component
public class IamTicketClaimantAdapter implements TicketClaimantPort {
    private final IamRoleMapper roleMapper;
    private final IamUserMapper userMapper;
    private final IamUserRoleMapper userRoleMapper;

    public IamTicketClaimantAdapter(
            IamRoleMapper roleMapper,
            IamUserMapper userMapper,
            IamUserRoleMapper userRoleMapper) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    public boolean isEligibleClaimant(long userId) {
        IamRole itRole = roleMapper.selectOne(
                Wrappers.<IamRole>lambdaQuery()
                        .eq(IamRole::getCode, "IT_SUPPORT"));
        if (itRole == null) {
            return false;
        }

        IamUser user = userMapper.selectProfileById(userId);
        return user != null
                && user.getStatus() == IamUserStatus.ENABLED
                && userRoleMapper.selectCount(
                        Wrappers.<IamUserRole>lambdaQuery()
                                .eq(IamUserRole::getUserId, userId)
                                .eq(IamUserRole::getRoleId, itRole.getId())) > 0;
    }

    /** 当前仍启用且拥有 IT_SUPPORT 角色时返回用户摘要，否则返回 null。调用方须在事务内使用。 */
    @Override
    public TicketUserSummaryResult lockEligibleClaimant(long userId) {
        // 角色编码不可修改；只需读取其 ID。锁住角色行会让所有 IT 领取请求全局串行。
        IamRole itRole = roleMapper.selectOne(
                Wrappers.<IamRole>lambdaQuery()
                        .eq(IamRole::getCode, "IT_SUPPORT"));

        if (itRole == null) {
            return null;
        }

        //2.校验用户
        IamUser user = userMapper.selectByIdForUpdate(userId);
        if (user == null || user.getStatus() != IamUserStatus.ENABLED) {
            return null;
        }

        //3.校验用户是否有该角色信息
        boolean hasItRole = userRoleMapper.selectByUserIdForUpdate(userId)
                .stream()
                .anyMatch(grant -> itRole.getId().equals(grant.getRoleId()));

        return hasItRole
                ? new TicketUserSummaryResult(user.getId(), user.getDisplayName())
                : null;
    }
}
