package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 素材档案：一张原图一个用途一份档案。
 * fingerprintSha256 是文件指纹，同一张图重复上传按指纹认出来；
 * 但 指纹+投放用途 才是一份档案——同源图投不同用途各开各的档，不误合并；
 * 同指纹同用途重复上传只累加 uploadCount，不另开档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset")
public class Asset extends BaseEntity {
    /** 素材编号，全局唯一，AS+序列 */
    private String assetNo;
    /** 原图文件指纹（SHA-256，hex），与 purpose 联合唯一 */
    private String fingerprintSha256;
    /** 投放用途（渠道 code，如 FEED / SPLASH），决定本档跑哪些转档规格 */
    private String purpose;
    /** 最近一次上传使用的文件名 */
    private String fileName;
    private String contentType;
    private Integer width;
    private Integer height;
    /** LANDSCAPE / PORTRAIT / SQUARE */
    private String orientation;
    /** 原图字节数 */
    private Long fileSize;
    /** 原图落盘路径 */
    private String storagePath;
    /** 累计上传次数：重复上传同指纹 +1 */
    private Integer uploadCount;
    private LocalDateTime lastUploadTime;
    /** 最新转档版本 id */
    private Long latestVersionId;
    /** ACTIVE / ARCHIVED */
    private String status;
    private String operatorName;
    private String remark;
}
