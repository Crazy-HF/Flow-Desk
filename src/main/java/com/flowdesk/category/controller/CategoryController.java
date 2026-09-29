package com.flowdesk.category.controller;

import com.flowdesk.category.application.result.CategoryOptionResult;
import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.common.web.R;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分类选项接口。
 */
@RestController
@RequestMapping("/fd/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 获取分类选项。
     */
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('TICKET_CREATE', 'TICKET_PROCESS')")
    public R<List<CategoryOptionResult>> options() {
        return R.success(categoryService.options());
    }
}