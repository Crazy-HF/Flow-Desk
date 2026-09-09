package com.flowdesk.iam.controller;

import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamCrateRoleVO;
import com.flowdesk.iam.domain.vo.IamRoleVO;
import com.flowdesk.iam.service.IamRoleService;
import com.flowdesk.shared.web.PageQuery;
import com.flowdesk.shared.web.PageResult;
import com.flowdesk.shared.web.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 自定义角色管理入口。
 *
 * <p>计划端点：</p>
 * <ul>
 *     <li>{@code GET /fd/v1/admin/roles}：分页查询角色</li>
 *     <li>{@code GET /fd/v1/admin/roles/{roleId}}：查询角色详情</li>
 *     <li>{@code POST /fd/v1/admin/roles}：创建角色</li>
 *     <li>{@code PUT /fd/v1/admin/roles/{roleId}}：修改角色名称和说明</li>
 *     <li>{@code DELETE /fd/v1/admin/roles/{roleId}}：删除未被引用的角色</li>
 * </ul>
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@RestController
@RequestMapping("/fd/v1/admin/roles")
public class IamRoleController {

    private final IamRoleService iamRoleService;
    public IamRoleController(IamRoleService iamRoleService) {
        this.iamRoleService = iamRoleService;
    }

    /**
     * 分页查询角色
     */
    @GetMapping("")
    public R<PageResult<IamRoleBO>> getRoleList(IamRoleVO iamRoleVO,
                                                @Valid PageQuery pageQuery) {
        return R.success(iamRoleService.getRoleList(iamRoleVO, pageQuery));
    }

    /**
     * 查询角色详情
     */
    @GetMapping("/{roleId}")
    public R<IamRoleBO> getRole(@PathVariable Long roleId) {
        return R.success(iamRoleService.getIamRoleById(roleId));
    }

    /**
     * 创建角色
     */
    @PostMapping("")
    public R<IamRoleBO> createRole(@Valid @RequestBody IamCrateRoleVO iamRoleVO) {
        return R.success(iamRoleService.createRole(iamRoleVO));
    }

    /**
     * 修改角色名称和说明
     */
    @PutMapping("/{roleId}")
    public R<IamRoleBO> updateRole(@PathVariable Long roleId,
                                   @Valid @RequestBody IamRoleVO iamRoleVO) {
        return R.success(iamRoleService.updateRole(roleId, iamRoleVO));
    }

    /**
     * 删除未被引用的角色
     */
    @DeleteMapping("/{roleId}")
    public R<Void> deleteRole(@PathVariable Long roleId) {
        return R.success(iamRoleService.deleteRole(roleId));
    }
}
