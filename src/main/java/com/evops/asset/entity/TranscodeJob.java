package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 转档任务：多规格转档状态流水，输入参数随任务快照，失败原因随任务落库，支持重跑。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transcode_job")
public class TranscodeJob extends BaseEntity {
    /** 任务号，全局唯一，TJ+序列 */
    private String jobNo;
    private Long assetId;
    private Long versionId;
    /** PENDING / PROCESSING / SUCCESS / PARTIAL / FAILED */
    private String status;
    /** 本次跑的规格清单（规格名，逗号分隔，便于翻查） */
    private String specs;
    /** 输入参数快照（JSON：用途、各规格比例/长边、体积上限、质量档），任务明细可回看 */
    private String specsSnapshot;
    /** 本次要出的规格总数 */
    private Integer totalCount;
    /** 成功产出的规格数 */
    private Integer successCount;
    /** 重试来源任务 id（失败重试链可翻） */
    private Long retryOfJobId;
    /** 失败/部分成功原因：美工看得见，不干等 */
    private String failReason;
    private LocalDateTime finishedTime;
    private String operatorName;
}
