package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.application.service.IamUserService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class IamUserServiceImpl implements IamUserService {
    private final IamUserMapper iamUserMapper;
    private final Clock clock;

    public IamUserServiceImpl(IamUserMapper iamUserMapper, Clock clock) {
        this.iamUserMapper = iamUserMapper;
        this.clock = clock;
    }

    @Override
    public boolean updatePassword(long userId, String encodedPassword) {
        IamUser current = iamUserMapper.selectById(userId);
        if (current == null)
            return false;

        // 用读到的版本做条件更新：并发的其它修改会让这次更新影响 0 行，交由调用方判定冲突
        LambdaUpdateWrapper<IamUser> update = Wrappers.<IamUser>lambdaUpdate()
                .eq(IamUser::getId, userId)
                .eq(IamUser::getVersion, current.getVersion())
                .set(IamUser::getPassword, encodedPassword)
                .set(IamUser::getVersion, current.getVersion() + 1)
                .set(IamUser::getUpdatedAt, LocalDateTime.now(clock));
        // 实体传 null：SET 子句完全由 wrapper 提供
        return iamUserMapper.update(null, update) == 1;
    }
}
