package com.flowdesk.ticket.application.port;

public interface CategoryAvailabilityPort {

    /**检查类别是否启用*/
    boolean isEnabled(long categoryId);
}
