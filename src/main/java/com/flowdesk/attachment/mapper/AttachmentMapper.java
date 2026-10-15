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

    /**
     * 读取附件元数据并锁定该行，供「删除未绑定附件」使用。
     *
     * <p>必须与工单侧引用附件的 {@code selectUsableForBinding} 争同一把行锁（两处都是
     * {@code attachment} 行的 {@code FOR UPDATE}，锁顺序一致、不构成环）：否则「判定未绑定」
     * 与「写入绑定」之间会留下一个窗口——删除事务删掉元数据后，绑定事务可能已经拿到该行，
     * 随后 {@code ticket_attachment} 的外键约束失败，把一个可预期的冲突变成 500。</p>
     *
     * <p>加锁后两端只会出现两种结果：绑定先提交则删除看到已引用并拒绝（409），
     * 删除先提交则绑定查不到该行、数量不符而整体失败（400）。</p>
     */
    @Select("""
            SELECT id, storage_key AS storageKey, original_name AS originalName,
                   content_type AS contentType, size_bytes AS sizeBytes,
                   sha256, uploaded_by AS uploadedBy, created_at AS createdAt
            FROM attachment
            WHERE id = #{id}
            FOR UPDATE
            """)
    Attachment selectByIdForUpdate(@Param("id") long id);

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