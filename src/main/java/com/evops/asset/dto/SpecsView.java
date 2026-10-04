package com.evops.asset.dto;

import lombok.Data;

import java.util.List;

/** 当前生效的转档规格口径（给前端标清用途、投放渠道、横竖朝向与体积上限）。 */
@Data
public class SpecsView {
    private List<SpecItem> specs;
    /** 已配置的投放渠道清单 */
    private List<String> channels;
    private long maxFileSizeBytes;
    private long maxFileSizeKb;
    private float qualityInitial;
    private float qualityFloor;
    private float qualityDecrement;

    @Data
    public static class SpecItem {
        private String name;
        private String usage;
        private String channel;
        private String orientation;
        private String ratio;
        private int longEdge;
    }
}
