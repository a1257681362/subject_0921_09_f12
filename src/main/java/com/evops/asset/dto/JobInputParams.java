package com.evops.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 转档任务输入参数快照：建任务那一刻把发起口径固化下来，之后配置怎么改都不影响回看。
 * 序列化为 JSON 存进 t_transcode_job.input_params。
 */
@Data
public class JobInputParams {
    /** 发起来源：UPLOAD 上传首轮 / RETRY 重新转档 */
    private String source;
    /** 投放用途码 */
    private String purposeCode;
    /** 投放渠道（可多个渠道规格一起跑；为空表示全部已配置规格） */
    private List<String> channels;
    /** 选中的目标规格名（横版/竖版 × 渠道） */
    private List<String> specs;
    /** 原文件名 / 尺寸 / 字节，记录当时依据的输入 */
    private String sourceFileName;
    private Integer sourceWidth;
    private Integer sourceHeight;
    private Long sourceFileSize;
    /** 当时的体积上限与质量档，解释产物为何压到那个质量 */
    private Long maxFileSizeBytes;
    private Float qualityInitial;
    private Float qualityFloor;
    private Float qualityDecrement;
    private String remark;
}
