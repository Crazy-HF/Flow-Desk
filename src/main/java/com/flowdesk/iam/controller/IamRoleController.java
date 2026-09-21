package com.flowdesk.iam.controller;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamRoleCreateVO;
import com.flowdesk.iam.domain.vo.IamRoleQueryVO;
import com.flowdesk.iam.domain.vo.IamRoleUpdateVO;
import com.flowdesk.iam.service.IamRoleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 角色管理接口，前缀 {@code /fd/v1/admin/roles}。
 *
 * <p>类级 {@code @PreAuthorize} 让本控制器所有端点都要求 {@code RBAC_MANAGE}；只有创建返回 {@code 201}，
 * 其余成功返回 {@code 200}。本层不写业务规则，{@code ApiException} 由 {@code GlobalExceptionHandler} 映射。</p>
 */
@RestController
@RequestMapping("/fd/v1/admin/roles")
@PreAuthorize("hasAuthority('RBAC_MANAGE')")
public class IamRoleController {

    private final IamRoleService iamRoleService;

    /** 构造注入角色服务；控制器保持无状态，不持有任何请求级数据。 */
    public IamRoleController(IamRoleService iamRoleService) {
        this.iamRoleService = iamRoleService;
    }

    /**
     * 分页查询角色列表。
     *
     * @param query 查询串绑定（{@code @ModelAttribute}）：分页参数来自 {@code PageQuery}，
     *              另有可选的 {@code keyword} 匹配 {@code code} 或 {@code name}；
     *              {@code @Valid} 负责页码、每页大小与排序参数的校验
     * @return {@code 200} 与角色分页结果（每项含已授权的 {@code permissionIds}）
     */
    @GetMapping
    public R<PageResult<IamRoleBO>> page(
            @Valid @ModelAttribute IamRoleQueryVO query) {
        return R.success(iamRoleService.page(query));
    }

    /**
     * 查询单个角色详情。
     *
     * @param roleId 路径中的角色 ID；非正整数按“资源不存在”处理
     * @return {@code 200} 与角色详情（含已授权的 {@code permissionIds}）
     */
    @GetMapping("/{roleId}")
    public R<IamRoleBO> getById(@PathVariable long roleId) {
        return R.success(iamRoleService.getById(roleId));
    }

    /**
     * 创建角色。
     *
     * @param request 请求体：{@code code}、{@code name} 与可选 {@code description}；
     *                编码格式、长度由 {@code @Valid} 校验，重复由服务层判定
     * @return {@code 201} 与创建后的角色；这是本控制器唯一的 {@code 201}
     */
    @PostMapping
    public ResponseEntity<R<IamRoleBO>> create(
            @Valid @RequestBody IamRoleCreateVO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(iamRoleService.create(request)));
    }

    /**
     * 修改角色的名称与描述。
     *
     * @param roleId  路径中的角色 ID
     * @param request 请求体：{@code name} 与可选 {@code description}；刻意不含 {@code code}，
     *                因此编码无法通过本接口修改
     * @return {@code 200} 与修改后的角色
     */
    @PutMapping("/{roleId}")
    public R<IamRoleBO> update(
            @PathVariable long roleId,
            @Valid @RequestBody IamRoleUpdateVO request) {
        return R.success(iamRoleService.update(roleId, request));
    }

    /**
     * 删除角色。
     *
     * <p>没有请求体：是否允许删除完全由服务层的保护规则与引用判断决定。</p>
     *
     * @param roleId 路径中的角色 ID
     * @return {@code 200} 与空数据信封（删除类端点统一返回 {@code R<Void>}）
     */
    @DeleteMapping("/{roleId}")
    public R<Void> delete(@PathVariable long roleId) {
        iamRoleService.delete(roleId);
        return R.success();
    }
}
