package com.flowdesk.iam.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 自定义权限管理入口。
 *
 * <p>计划端点：</p>
 * <ul>
 *     <li>{@code GET /fd/v1/admin/permissions}：分页查询权限</li>
 *     <li>{@code GET /fd/v1/admin/permissions/{permissionId}}：查询权限详情</li>
 *     <li>{@code POST /fd/v1/admin/permissions}：创建权限</li>
 *     <li>{@code PUT /fd/v1/admin/permissions/{permissionId}}：修改权限名称和说明</li>
 *     <li>{@code DELETE /fd/v1/admin/permissions/{permissionId}}：删除未被引用的权限</li>
 * </ul>
 * </p>
 *
 * @author Crazy-HF
 * @since 2026-09-08
 */
@RestController
@RequestMapping("/fd/v1/admin/permissions")
public class IamPermissionController {

}
