package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 多规格转档产物：一行一个规格版本，按投放渠道与横竖朝向保存，usage 标清用途。
 * 长边被压进 longEdge；qualityRound 记录体积超标后压到第几轮质量，finalQuality 记最终质量档，
 * qualityState 记质量压缩状态（首轮达标 / 降质压缩后达标 / 压到下限仍超标）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset_file")
public class AssetFile extends BaseEntity {
    private Long assetId;
    private Long versionId;
    /** 规格标识，如 feed_landscape / splash_portrait */
    private String usage;
    /** 投放渠道，如 FEED / SPLASH */
    private String channel;
    /** LANDSCAPE 横版 / PORTRAIT 竖版 / SQUARE 方版 */
    private String orientation;
    private Integer width;
    private Integer height;
    /** 长边限定像素 */
    private Integer longEdge;
    private Long fileSize;
    /** 第几轮质量压缩（1 = 首轮质量即达标） */
    private Integer qualityRound;
    /** 最终采用的 JPEG 质量（0~1，两位小数落库） */
    private Float finalQuality;
    /** 质量压缩状态：DIRECT / COMPRESSED / OVER_LIMIT */
    private String qualityState;
    private String contentType;
    private String storagePath;
}
