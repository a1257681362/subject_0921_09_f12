package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 转档任务项：一行一个目标规格（横版/竖版 × 投放渠道）。
 * 逐规格独立状态与失败原因，是任务「部分成功」的落点：成的那几个有产物可下，败的那几个带可读原因可重跑。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transcode_job_item")
public class TranscodeJobItem extends BaseEntity {
    private Long jobId;
    private Long assetId;
    private Long versionId;
    /** 规格标识，对应 t_asset_file.usage */
    private String usage;
    /** 投放渠道 */
    private String channel;
    /** LANDSCAPE / PORTRAIT */
    private String orientation;
    /** 任务内展示顺序 */
    private Integer seq;
    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private Integer qualityRound;
    private Float finalQuality;
    /** 成功产出的产物 id（t_asset_file.id） */
    private Long fileId;
    /** 该规格失败的可读原因 */
    private String failReason;
    private LocalDateTime finishedTime;
}
