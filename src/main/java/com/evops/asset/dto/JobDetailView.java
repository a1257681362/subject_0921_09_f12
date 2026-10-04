package com.evops.asset.dto;

import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.entity.TranscodeJobItem;
import lombok.Data;

import java.util.List;

/**
 * 转档任务明细回看：任务本体（状态/操作人/重试链）+ 逐规格明细（输入参数与各自状态）
 * + 产出版本与产物清单 + 提交时冻结的输入参数快照。
 */
@Data
public class JobDetailView {
    private TranscodeJob job;
    private List<TranscodeJobItem> items;
    private AssetVersion version;
    private List<AssetFile> files;
    /** 输入参数快照（提交时冻结的 JSON 原文解析结果：用途、各规格比例/长边、体积上限、质量档） */
    private Object inputParams;
}
