package com.flowdesk.iam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.domain.bo.IamUserBO;
import com.flowdesk.iam.domain.vo.IamUserCreateVO;
import com.flowdesk.iam.domain.vo.IamUserResetPasswordVO;
import com.flowdesk.iam.domain.vo.IamUserVO;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.service.IamUserService;
import com.flowdesk.shared.exception.ApiException;
import com.flowdesk.shared.utils.StringUtils;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;


@Slf4j
@Service
public class IamUserServiceImpl extends ServiceImpl<IamUserMapper, IamUser> implements IamUserService {


    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public IamUserServiceImpl(PasswordEncoder passwordEncoder, Clock clock) {
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * 获取IAM用户列表
     */
    @Override
    public PageResult<IamUserBO> listIamUsers(IamUserVO iamUserVO, PageQuery pageQuery) {
        // 1. 参数防空
        iamUserVO = Objects.requireNonNullElse(iamUserVO, new IamUserVO());

        // 2. 构建查询条件
        LambdaQueryWrapper<IamUser> queryWrapper = new LambdaQueryWrapper<IamUser>()
                .select(IamUser::getId, IamUser::getUsername, IamUser::getDisplayName,
                        IamUser::getStatus, IamUser::getCreatedAt, IamUser::getUpdatedAt,
                        IamUser::getVersion)
                .like(StringUtils.hasText(iamUserVO.getUsername()), IamUser::getUsername, iamUserVO.getUsername())
                .eq(iamUserVO.getStatus() != null, IamUser::getStatus, iamUserVO.getStatus())
                // 可继续添加更多条件
                .eq(StringUtils.hasText(iamUserVO.getDisplayName()), IamUser::getDisplayName, iamUserVO.getDisplayName());

        // 3. 构建分页对象（包含动态排序）
        Page<IamUser> page = new Page<>(pageQuery.getCurrent(), pageQuery.getSize());
        List<OrderItem> orderItems = pageQuery.getOrderItems();
        if (!orderItems.isEmpty()) {
            page.addOrder(orderItems);
        } else {
            // 默认排序
            page.addOrder(OrderItem.desc("created_at"), OrderItem.desc("id"));
        }

        // 4. 执行分页查询（Service 继承的 page 方法）
        Page<IamUser> userPage = page(page, queryWrapper);

        // 5. 转换为稳定的接口分页结构，避免 Controller 暴露 MyBatis-Plus 类型
        return PageResult.from(userPage, this::toUserBO);
    }

    /**
     * 根据用户ID获取IAM用户
     */
    @Override
    public IamUserBO getIamUserById(Long userId) {
        // 1. 参数防空

        if (userId == null)
            return null;

        // 2. 获取用户信息
        IamUser user = baseMapper.selectById(userId);

        // 3. 转换为BO
        return user != null ? toUserBO(user) : null;
    }

    /**
     * 增加IAM用户
     */
    @Override
    public IamUserBO createIamUser(IamUserCreateVO iamUserVO) {

        Objects.requireNonNull(iamUserVO, "创建用户参数不能为 null");

        IamUser user = new IamUser();
        LocalDateTime now = LocalDateTime.now(clock);
        user.setUsername(iamUserVO.getUsername().trim());
        user.setDisplayName(iamUserVO.getDisplayName().trim());
        user.setPasswordHash(passwordEncoder.encode(iamUserVO.getPassword()));
        user.setStatus(IamUserStatus.ENABLED);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setVersion(0L);

        try {
            if (baseMapper.insert(user) != 1)
                throw new IllegalStateException("创建用户失败");
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_CONFLICT", "登录名已存在");
        }

        return toUserBO(user);
    }

    /**
     * 更新IAM用户
     */
    @Override
    public IamUserBO updateIamUser(Long userId, IamUserVO iamUserVO) {
        // 1. 参数防空
        if (userId == null || iamUserVO == null)
            return null;

        // 2. 更新用户信息
        IamUser user = baseMapper.selectById(userId);
        if (user != null) {
            user.setDisplayName(iamUserVO.getDisplayName().trim());
            user.setUpdatedAt(LocalDateTime.now(clock));
            if (baseMapper.updateById(user) == 1) {
                return toUserBO(user);
            }
        }

        try {
            if (baseMapper.insert(user) != 1)
                throw new IllegalStateException("更新用户失败");
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_CONFLICT", "登录名已存在");
        }

        return toUserBO(user);
    }


    /**
     * 启用 IAM 用户。重复启用按幂等成功处理。
     */
    @Override
    public void enableIamUser(Long userId, Long expectedVersion) {
        changeStatus(userId, expectedVersion,
                IamUserStatus.DISABLED,
                IamUserStatus.ENABLED
        );
    }

    /**
     * 禁用 IAM 用户。重复禁用按幂等成功处理。
     */
    @Override
    public void disableIamUser(Long userId, Long expectedVersion) {
        changeStatus(userId, expectedVersion,
                IamUserStatus.ENABLED,
                IamUserStatus.DISABLED);
    }



    /** 管理员重置 IAM 用户密码。 */
    @Override
    public void resetIamUserPassword(Long userId, IamUserResetPasswordVO request) {
        if (userId == null || userId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_USER_ID",
                    "用户 ID 必须为正整数");
        }
        Objects.requireNonNull(request, "重置密码参数不能为 null");

        IamUser user = baseMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "USER_NOT_FOUND",
                    "用户不存在");
        }

        if (!Objects.equals(user.getVersion(), request.getVersion())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "USER_CONFLICT",
                    "用户信息或版本已发生变化");
        }

        String passwordHash = passwordEncoder.encode(request.getNewPassword());
        int updatedRows = baseMapper.update(
                null,
                new LambdaUpdateWrapper<IamUser>()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getVersion, request.getVersion())
                        .set(IamUser::getPasswordHash, passwordHash)
                        .set(IamUser::getUpdatedAt, LocalDateTime.now(clock))
                        .set(IamUser::getVersion, request.getVersion() + 1)
        );

        if (updatedRows != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "USER_CONFLICT",
                    "用户信息或版本已发生变化");
        }

        // TODO TASK-010：接入 Redis 会话服务后，撤销该用户的全部会话。
        log.info("管理员重置用户密码成功，userId={}", userId);
    }

    /**
     * 将 IamUser 转换为 IamUserBO
     */
    private IamUserBO toUserBO(IamUser user) {
        IamUserBO result = new IamUserBO();
        result.setId(user.getId());
        result.setUsername(user.getUsername());
        result.setDisplayName(user.getDisplayName());
        result.setStatus(user.getStatus());
        result.setCreatedAt(user.getCreatedAt());
        result.setUpdatedAt(user.getUpdatedAt());
        result.setVersion(user.getVersion());
        return result;
    }


    /**
     * 修改用户状态
     */
    private void changeStatus(Long userId, Long expectedVersion,
                              IamUserStatus sourceStatus, IamUserStatus targetStatus) {
        if (userId == null || userId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_USER_ID",
                    "用户 ID 必须为正整数");
        }

        IamUser user = baseMapper.selectById(userId);

        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "USER_NOT_FOUND",
                    "用户不存在");
        }

        // 已达到目标状态：重复请求按幂等成功处理
        if (user.getStatus() == targetStatus) {
            log.debug("用户已经处于目标状态，userId={}, status={}",
                    userId,
                    targetStatus);
            return;
        }

        if (!Objects.equals(user.getVersion(), expectedVersion)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "USER_CONFLICT",
                    "用户状态或版本已发生变化");
        }

        int updatedRows = baseMapper.update(
                null,
                new LambdaUpdateWrapper<IamUser>()
                        .eq(IamUser::getId, userId)
                        .eq(IamUser::getStatus, sourceStatus)
                        .eq(IamUser::getVersion, expectedVersion)
                        .set(IamUser::getStatus, targetStatus)
                        .set(IamUser::getUpdatedAt, LocalDateTime.now(clock))
                        .set(IamUser::getVersion, expectedVersion + 1)
        );

        if (updatedRows == 1) {
            log.info("用户状态修改成功，userId={}, status={}", userId, targetStatus);
            return;
        }

        // 可能有另一个相同请求抢先完成，因此重新确认最终状态
        IamUser latestUser = baseMapper.selectById(userId);

        if (latestUser != null && latestUser.getStatus() == targetStatus) {
            return;
        }

        throw new ApiException(HttpStatus.CONFLICT, "USER_CONFLICT", "用户状态或版本已发生变化");
    }



    //业务前置校验

}
