package com.flowdesk.ticket.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface TicketAiDailyQuotaMapper {

    /** 确保用户在北京时间指定日期有额度行；已存在时不重置已用次数。 */
    @Insert("""
            INSERT INTO ticket_ai_daily_quota
                (user_id, quota_date, used_count, created_at, updated_at)
            VALUES
                (#{userId}, #{quotaDate}, 0, #{now}, #{now})
            ON DUPLICATE KEY UPDATE user_id = user_id
            """)
    int ensureRow(
            @Param("userId") long userId,
            @Param("quotaDate") LocalDate quotaDate,
            @Param("now") LocalDateTime now);

    /** 原子占用一次额度；返回 1 表示成功，0 表示当日额度已满。 */
    @Update("""
            UPDATE ticket_ai_daily_quota
            SET used_count = used_count + 1,
                updated_at = #{now}
            WHERE user_id = #{userId}
              AND quota_date = #{quotaDate}
              AND used_count < #{dailyLimit}
            """)
    int tryUseOne(
            @Param("userId") long userId,
            @Param("quotaDate") LocalDate quotaDate,
            @Param("dailyLimit") int dailyLimit,
            @Param("now") LocalDateTime now);
}
