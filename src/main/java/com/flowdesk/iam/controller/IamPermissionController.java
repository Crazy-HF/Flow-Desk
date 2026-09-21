package com.flowdesk.iam.controller;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.common.web.R;
import com.flowdesk.iam.domain.bo.IamPermissionBO;
import com.flowdesk.iam.domain.vo.IamPermissionCreateVO;
import com.flowdesk.iam.domain.vo.IamPermissionQueryVO;
import com.flowdesk.iam.domain.vo.IamPermissionUpdateVO;
import com.flowdesk.iam.service.IamPermissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 权限管理接口。
 *
 * <p>该控制器统一要求调用者具备 {@code RBAC_MANAGE} 权限，负责接收和校验
 * HTTP 请求，并将具体业务处理委托给 {@link IamPermissionService}。</p>
 */
@RestController
@RequestMapping("/fd/v1/admin/permissions")
@PreAuthorize("hasAuthority('RBAC_MANAGE')")
public class IamPermissionController {

    private final IamPermissionService iamPermissionService;

    /**
     * 创建权限管理控制器。
     *
     * @param iamPermissionService 权限应用服务
     */
    public IamPermissionController(IamPermissionService iamPermissionService) {
        this.iamPermissionService = iamPermissionService;
    }

    /**
     * 分页查询权限。
     *
     * @param query 分页、关键字和排序条件
     * @return 权限分页结果
     */
    @GetMapping
    public R<PageResult<IamPermissionBO>> page(
            @Valid @ModelAttribute IamPermissionQueryVO query){
        return R.success(iamPermissionService.page(query));
    }

    /**
     * 查询指定权限详情。
     *
     * @param permissionId 权限 ID
     * @return 权限详情
     */
    @GetMapping("/{permissionId}")
    public R<IamPermissionBO> getById(
            @PathVariable long permissionId){
        return R.success(iamPermissionService.getById(permissionId));
    }

    /**
     * 创建权限。
     *
     * @param request 权限创建参数
     * @return 已创建的权限，HTTP 状态码为 201
     */
    @PostMapping
    public ResponseEntity<R<IamPermissionBO>> create(
            @Valid @RequestBody IamPermissionCreateVO request){
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(iamPermissionService.create(request)));
    }

    /**
     * 更新权限的可变信息，权限编码不会被修改。
     *
     * @param permissionId 权限 ID
     * @param request 权限更新参数
     * @return 更新后的权限详情
     */
    @PutMapping("/{permissionId}")
    public R<IamPermissionBO> update(
            @PathVariable long permissionId,
            @Valid @RequestBody IamPermissionUpdateVO request){
        return R.success(iamPermissionService.update(permissionId, request));
    }

    /**
     * 删除未被角色引用的权限。
     *
     * @param permissionId 权限 ID
     * @return 空成功响应
     */
    @DeleteMapping("/{permissionId}")
    public R<Void> delete(
            @PathVariable long permissionId){
        iamPermissionService.delete(permissionId);
        return R.success();
    }
}
