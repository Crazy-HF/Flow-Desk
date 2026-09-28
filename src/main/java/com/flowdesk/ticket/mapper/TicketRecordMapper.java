package com.flowdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.ticket.domain.TicketRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketRecordMapper extends BaseMapper<TicketRecord> {
}