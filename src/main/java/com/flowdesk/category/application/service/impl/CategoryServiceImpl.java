package com.flowdesk.category.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.category.application.command.CategoryStatusChangeCommand;
import com.flowdesk.category.application.command.CreateCategoryCommand;
import com.flowdesk.category.application.command.UpdateCategoryCommand;
import com.flowdesk.category.application.query.CategoryQuery;
import com.flowdesk.category.application.result.CategoryOptionResult;
import com.flowdesk.category.application.result.CategoryResult;
import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.category.domain.TicketCategory;
import com.flowdesk.category.mapper.TicketCategoryMapper;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.utils.StringUtils;
import com.flowdesk.common.web.PageResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class CategoryServiceImpl implements CategoryService {
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";

    // 分类排序字段
    private static final Set<String> CATEGORY_ORDER_FIELDS = Set.of(
            "name",
            "status",
            "sort_order",
            "created_at",
            "updated_at"
    );
    private final Clock clock;
    private final TicketCategoryMapper ticketCategoryMapper;
    public CategoryServiceImpl(Clock clock ,TicketCategoryMapper ticketCategoryMapper) {
        this.clock = clock;
        this.ticketCategoryMapper = ticketCategoryMapper;
    }

    /**
     * 获取按展示顺序排列的启用分类。
     */
    @Override
    public List<CategoryOptionResult> options() {
        LambdaQueryWrapper<TicketCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(TicketCategory::getId, TicketCategory::getName)
                .eq(TicketCategory::getStatus, ENABLED)
                .orderByAsc(TicketCategory::getSortOrder)
                .orderByAsc(TicketCategory::getId);

        // 执行查询
        List<TicketCategory> ticketCategories = ticketCategoryMapper.selectList(wrapper);

        return ticketCategories.stream()
                .map(category ->
                        new CategoryOptionResult(category.getId(), category.getName()))
                .toList();
    }

    /**
     *检查分类是否启用。
     */
    @Override
    public boolean isEnabled(long categoryId) {
        LambdaQueryWrapper<TicketCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getStatus, ENABLED);

        return ticketCategoryMapper.selectCount(wrapper) > 0;
    }


    /**
     * 分页查询分类。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<CategoryResult> page(CategoryQuery query) {
        //1.创建分页对象
        Page<TicketCategory> page = new Page<>(query.getCurrent(), query.getPageSize());

        //2.排序字段
        List<OrderItem> orderItems = query.orderItems(CATEGORY_ORDER_FIELDS);
        if (orderItems.isEmpty()){
            page.addOrder(OrderItem.asc("sort_order"));
        }else {
            page.addOrder(orderItems);
        }

        // 相同排序值时，使用 ID 保证稳定次序。
        page.addOrder(OrderItem.asc("id"));

        //3.筛选查询参数
        LambdaQueryWrapper<TicketCategory> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(query.getKeyword())) {
            String escaped = query.getKeyword().strip()
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_");

            wrapper.apply(
                    "name LIKE {0} ESCAPE '!'",
                    "%" + escaped + "%");
        }

        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(
                    TicketCategory::getStatus,
                    query.getStatus());
        }

        // 4. 执行数据库分页查询。
        Page<TicketCategory> result =
                ticketCategoryMapper.selectPage(page, wrapper);

        // 5. 转换为接口结果。
        return PageResult.from(result, this::toResult);
    }

    /**
     * 创建分类。
     */
    @Override
    @Transactional
    public CategoryResult create(CreateCategoryCommand request) {
        //1.参数校验处理
        String name = normalizeName(request.name());
        validateSortOrder(request.sortOrder());

        //2.获取当前时间并转为UTC
        LocalDateTime now = nowUtc();

        //3.创建分类对象并设置属性
        TicketCategory category = new TicketCategory();
        category.setName(name);
        category.setSortOrder(request.sortOrder());
        category.setStatus(ENABLED);
        category.setVersion(0L);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);

        //4.插入分类对象
        try {
            if (ticketCategoryMapper.insert(category) != 1) {
                throw new IllegalStateException("分类创建失败");
            }
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }

        return toResult(category);
    }

    @Override
    @Transactional
    public CategoryResult update(long categoryId, UpdateCategoryCommand request) {
        //1.参数校验处理
        String name = normalizeName(request.name());
        validateSortOrder(request.sortOrder());

        //2.根据Id锁定当前分类行
        TicketCategory category = ticketCategoryMapper.selectByIdForUpdate(categoryId);

        //3.判断分类是否存在
        checkVersion(category, request.version());

        //4.创建更新版本
        long oldVersion = category.getVersion();
        long newVersion = oldVersion + 1;
        LocalDateTime now = nowUtc();


        //5.创建更新条件并执行更新
        LambdaUpdateWrapper<TicketCategory> wrapper =
                new LambdaUpdateWrapper<>();

        wrapper.eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getVersion, oldVersion)
                .set(TicketCategory::getName, name)
                .set(TicketCategory::getSortOrder, request.sortOrder())
                .set(TicketCategory::getUpdatedAt, now)
                .set(TicketCategory::getVersion, newVersion);

        try {
            if (ticketCategoryMapper.update(null, wrapper) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }

        //6.返回更新后的分类结果
        category.setName(name);
        category.setSortOrder(request.sortOrder());
        category.setUpdatedAt(now);
        category.setVersion(newVersion);

        return toResult(category);
    }

    @Override
    @Transactional
    public CategoryResult enable(long categoryId, CategoryStatusChangeCommand request) {
        return changeStatus(categoryId, request.version(), ENABLED);
    }

    @Override
    @Transactional
    public CategoryResult disable(long categoryId, CategoryStatusChangeCommand request) {
        return changeStatus(categoryId, request.version(), DISABLED);
    }

    /**
     *
     */
    @Override
    @Transactional
    public void delete(long categoryId, long version) {
        if (version < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "版本不能为负数");
        }

        TicketCategory category = requireCategoryForUpdate(categoryId);
        checkVersion(category, version);

        LambdaQueryWrapper<TicketCategory> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getVersion, version);

        try {
            if (ticketCategoryMapper.delete(wrapper) != 1) {
                throw versionConflict();
            }
        } catch (DataIntegrityViolationException exception) {
            if (isForeignKeyReference(exception)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CATEGORY_IN_USE",
                        "分类仍被工单或历史记录引用，不能删除");
            }

            // 其他完整性错误不能误报为“分类被引用”。
            throw exception;
        }
    }

    /** 转换为接口结果。 */
    private CategoryResult toResult(TicketCategory category) {
        return new CategoryResult(
                category.getId(),
                category.getName(),
                category.getStatus(),
                category.getSortOrder(),
                category.getCreatedAt().atOffset(ZoneOffset.UTC),
                category.getUpdatedAt().atOffset(ZoneOffset.UTC),
                category.getVersion());
    }

    private CategoryResult changeStatus(long categoryId, Long expectedVersion, String targetStatus) {
        //
        TicketCategory category = requireCategoryForUpdate(categoryId);
        checkVersion(category, expectedVersion);

        // 状态一致且已经达到目标状态：不写库、不增加版本。
        if (targetStatus.equals(category.getStatus())) {
            return toResult(category);
        }

        // 创建更新版本
        long oldVersion = category.getVersion();
        long newVersion = oldVersion + 1;
        LocalDateTime now = nowUtc();

        // 创建更新条件并执行更新
        LambdaUpdateWrapper<TicketCategory> wrapper =
                new LambdaUpdateWrapper<>();

        wrapper.eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getVersion, oldVersion)
                .set(TicketCategory::getStatus, targetStatus)
                .set(TicketCategory::getUpdatedAt, now)
                .set(TicketCategory::getVersion, newVersion);

        if (ticketCategoryMapper.update(null, wrapper) != 1) {
            throw versionConflict();
        }

        // 更新分类对象
        category.setStatus(targetStatus);
        category.setUpdatedAt(now);
        category.setVersion(newVersion);

        return toResult(category);
    }

    /** 根据 ID 锁定并获取分类。 */
    private TicketCategory requireCategoryForUpdate(long categoryId) {
        if (categoryId <= 0) {
            throw categoryNotFound();
        }

        TicketCategory category =
                ticketCategoryMapper.selectByIdForUpdate(categoryId);

        if (category == null) {
            throw categoryNotFound();
        }

        return category;
    }

    /** 检查分类版本。 */
    private void checkVersion(TicketCategory category, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "版本必须为非负整数");
        }

        if (!Objects.equals(category.getVersion(), expectedVersion)) {
            throw versionConflict();
        }
    }

    /** 规范化分类名称。 */
    private String normalizeName(String name) {
        if (name == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "分类名称不能为空");
        }

        String normalized = name.strip();

        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "分类名称去除首尾空白后长度必须为1～100");
        }

        return normalized;
    }

    /** 验证排序值。 */
    private void validateSortOrder(Integer sortOrder) {
        if (sortOrder == null || sortOrder < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "排序值必须为非负整数");
        }
    }

    /** 获取当前时间并转为UTC。 */
    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    /** 分类不存在。 */
    private ApiException categoryNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "CATEGORY_NOT_FOUND",
                "分类不存在");
    }

    /** 分类版本冲突。 */
    private ApiException versionConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "CATEGORY_CONFLICT",
                "分类已发生变化，请刷新后重试");
    }

    /** 分类名称冲突。 */
    private ApiException nameConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "CATEGORY_NAME_CONFLICT",
                "分类名称已存在");
    }

    /**
     * MySQL 1451：父行仍被外键引用，不能删除。
     */
    private boolean isForeignKeyReference(
            DataIntegrityViolationException exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1451) {
                return true;
            }
            cause = cause.getCause();
        }

        return false;
    }
}
