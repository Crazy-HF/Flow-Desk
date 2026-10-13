package com.flowdesk.attachment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowdesk.attachment.domain.Attachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通用附件表映射；单表读写由 MyBatis-Plus 提供（项目无 {@code @MapperScan}，靠 {@code @Mapper} 注解注册）。
 */
@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /** 全部已提交附件的存储标识（对账任务以数据库为权威扫描最终目录；v1 演示规模一次全取）。 */
    @Select("SELECT storage_key FROM attachment")
    List<String> selectAllStorageKeys();

    /** 保留期之前创建的附件元数据（孤儿对账候选；是否仍被引用由业务授权端口判定）。 */
    @Select("""
            SELECT id, storage_key AS storageKey, original_name AS originalName,
                   content_type AS contentType, size_bytes AS sizeBytes,
                   sha256, uploaded_by AS uploadedBy, created_at AS createdAt
            FROM attachment
            WHERE created_at < #{cutoff}
            """)
    List<Attachment> selectCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}