package com.flowdesk.ticket.infrastructure;

import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import org.springframework.stereotype.Component;

@Component
public class CategoryAvailabilityAdapter implements CategoryAvailabilityPort {

    private final CategoryService categoryService;
    public CategoryAvailabilityAdapter(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** */
    @Override
    public boolean isEnabled(long categoryId) {

        return categoryService.isEnabled(categoryId);
    }
}
