package com.flowdesk.notification.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/** 站内信列表查询：本人范围，支持「只看未读」；排序固定为 id 倒序，不接受客户端排序。 */
@Getter
@Setter
public class NotificationQuery extends PageQuery {

    /** 只看未读；缺省返回全部。 */
    private Boolean unreadOnly;

    /** 列表 API 的 page/size 映射到现有分页字段，复用父类校验。 */
    public Integer getPage() {
        return getPageNo();
    }

    public void setPage(Integer page) {
        setPageNo(page);
    }

    public void setSize(int size) {
        setPageSize(size);
    }

    /** 站内信只支持固定的 id 倒序，拒绝客户端自选排序字段与方向。 */
    @AssertTrue(message = "站内信排序固定为 id 倒序")
    public boolean isFixedSortOnly() {
        return !StringUtils.hasText(getOrderBy()) && isAscendingDirection();
    }
}
