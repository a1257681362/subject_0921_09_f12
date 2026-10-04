package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 转档任务明细：一个规格一行，随任务建档即落（输入参数可回看）。
 * 质量压缩、失败原因逐规格有状态；任务的部分成功就是明细里成败各半的汇总口径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transcode_job_item")
public class TranscodeJobItem extends BaseEntity {
    private Long jobId;
    private Long assetId;
    private Long versionId;
    /** 规格标识，如 feed_landscape */
    private String specName;
    /** 投放渠道，如 FEED / SPLASH */
    private String channel;
    /** LANDSCAPE 横版 / PORTRAIT 竖版 / SQUARE 方版 */
    private String orientation;
    /** 输入参数：目标比例 宽:高 */
    private String ratio;
    /** 输入参数：长边限定像素 */
    private Integer longEdge;
    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;
    /** 第几轮质量压缩（1 = 首轮质量即达标） */
    private Integer qualityRound;
    /** 最终采用的 JPEG 质量 */
    private Float finalQuality;
    /** 质量压缩状态：DIRECT / COMPRESSED / OVER_LIMIT */
    private String qualityState;
    /** 产物字节数 */
    private Long fileSize;
    /** 产物行 id（t_asset_file），成功时回填 */
    private Long fileId;
    /** 本规格失败原因（成功时为空） */
    private String failReason;
}
