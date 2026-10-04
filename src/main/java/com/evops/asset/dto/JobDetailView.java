package com.evops.asset.dto;

import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.entity.TranscodeJobItem;
import lombok.Data;

import java.util.List;

/**
 * 转档任务明细回看：任务本体 + 解析后的输入参数 + 产出版本 + 逐规格任务项 + 经手人。
 */
@Data
public class JobDetailView {
    private TranscodeJob job;
    /** 输入参数快照（解析自 job.inputParams；解析失败不影响其余信息，原样字符串兜底由 controller 处理） */
    private JobInputParams input;
    /** 本次任务产出版本 */
    private AssetVersion version;
    /** 逐规格任务项（含各自状态、产物指标与失败原因） */
    private List<TranscodeJobItem> items;
}
