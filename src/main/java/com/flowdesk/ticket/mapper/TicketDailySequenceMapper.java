package com.flowdesk.ticket.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 每日序号分配，必须在工单创建事务中调用。 */
@Mapper
public interface TicketDailySequenceMapper {

    /** 原子创建或递增当天序号；行锁持续到外层事务结束。 */
    @Insert("""
            INSERT INTO ticket_daily_sequence (business_date, current_value)
            VALUES (#{businessDate}, 1)
            ON DUPLICATE KEY UPDATE current_value = current_value + 1
            """)
    int increment(@Param("businessDate") LocalDate businessDate);

    /** 获取当天的序号，并行锁。 */
    @Select("""
            SELECT current_value
            FROM ticket_daily_sequence
            WHERE business_date = #{businessDate}
            FOR UPDATE
            """)
    Long selectCurrentForUpdate(@Param("businessDate") LocalDate businessDate);
}
