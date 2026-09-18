package com.flowdesk.common.web;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.flowdesk.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    private static final Set<String> ALLOWED = Set.of("created_at", "username");

    @Test
    void orderItemsAcceptsOnlyWhitelistedFields() {
        PageQuery query = new PageQuery();
        query.setOrderBy("created_at, username");
        query.setOrderDirection("desc");

        List<OrderItem> orderItems = query.orderItems(ALLOWED);

        assertThat(orderItems).extracting(OrderItem::getColumn).containsExactly("created_at", "username");
        assertThat(orderItems).allSatisfy(item -> assertThat(item.isAsc()).isFalse());
    }

    @Test
    void orderItemsRejectsFieldOutsideTheWhitelist() {
        PageQuery query = new PageQuery();
        query.setOrderBy("password_hash");

        assertThatThrownBy(() -> query.orderItems(ALLOWED))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(exception.getMessage()).doesNotContain("password_hash");
                });
    }

    @Test
    void orderItemsRejectsUnknownDirection() {
        PageQuery query = new PageQuery();
        query.setOrderBy("created_at");
        query.setOrderDirection("sideways");

        assertThatThrownBy(() -> query.orderItems(ALLOWED))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void orderItemsIsEmptyWhenNoOrderRequested() {
        assertThat(new PageQuery().orderItems(ALLOWED)).isEmpty();

        PageQuery blank = new PageQuery();
        blank.setOrderBy("  ");
        assertThat(blank.orderItems(ALLOWED)).isEmpty();
    }

    @Test
    void defaultsRemainOnePageOfTwentyAscending() {
        PageQuery query = new PageQuery();

        assertThat(query.getCurrent()).isEqualTo(1);
        assertThat(query.getSize()).isEqualTo(20);
        assertThat(query.getOrderDirection()).isEqualTo("asc");
    }
}
