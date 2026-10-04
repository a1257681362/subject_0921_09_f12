package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 转档任务：多规格转档状态流水。
 * 一次跑多个目标规格（横版/竖版 × 投放渠道），status 为聚合状态：
 * PENDING / PROCESSING / SUCCESS（全部规格成功）/ PARTIAL_SUCCESS（部分成功）/ FAILED（全部失败）。
 * inputParams 为建任务时的输入参数快照，回看任务即知当时按什么用途/渠道/规格/质量档发起。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transcode_job")
public class TranscodeJob extends BaseEntity {
    /** 任务号，全局唯一，TJ+序列 */
    private String jobNo;
    private Long assetId;
    private Long versionId;
    /** PENDING / PROCESSING / SUCCESS / PARTIAL_SUCCESS / FAILED */
    private String status;
    /** 本次跑的规格清单（规格名，逗号分隔，便于翻查） */
    private String specs;
    /** 本次目标规格数 */
    private Integer targetCount;
    /** 成功产出的规格数 */
    private Integer successCount;
    /** 失败的规格数 */
    private Integer failCount;
    /** 输入参数快照 JSON：用途 / 渠道 / 选中规格 / 体积上限 / 质量档 / 来源 / 备注 */
    private String inputParams;
    /** 失败/部分成功时的汇总说明（逐规格原因见任务项 failReason） */
    private String failReason;
    private LocalDateTime finishedTime;
    private String operatorName;
}
