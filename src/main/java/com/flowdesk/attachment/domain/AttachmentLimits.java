package com.flowdesk.attachment.domain;

/**
 * 附件契约限额（{@code docs/api-design.md} 7.1/7.6 已确认）。
 *
 * <p>限额是契约的一部分，不作为环境配置项；测试 profile 与生产使用同一组值。</p>
 */
public final class AttachmentLimits {

    /** 单个文件最大 10 MiB。 */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** 单次请求最多 5 个文件。 */
    public static final int MAX_FILES_PER_REQUEST = 5;

    /** 单次请求文件总大小最大 25 MiB。 */
    public static final long MAX_REQUEST_TOTAL_BYTES = 25L * 1024 * 1024;

    /** 原始文件名去首尾空白后的最大字符数。 */
    public static final int MAX_ORIGINAL_NAME_LENGTH = 255;

    private AttachmentLimits() {
    }
}