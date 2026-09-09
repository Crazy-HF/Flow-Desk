package com.flowdesk.iam.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 角色权限授权管理入口。
 *
 * <p>计划端点：</p>
 * <ul>
 *     <li>{@code GET /fd/v1/admin/role-permissions}：按角色或权限分页查询授权关系</li>
 *     <li>{@code POST /fd/v1/admin/role-permissions}：为角色授予权限</li>
 *     <li>{@code DELETE /fd/v1/admin/role-permissions/{roleId}/{permissionId}}：撤销角色权限</li>
 * </ul>
 *
 * <p>授权关系本身没有可修改业务字段；变更权限应撤销旧授权后重新授予，故不设置 {@code PUT}。</p>
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@RestController
@RequestMapping("/fd/v1/admin/role-permissions")
public class IamRolePermissionController {

}
