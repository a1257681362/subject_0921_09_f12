package com.evops.asset;

/** 素材多规格转档的状态口径。 */
public final class AssetEnums {
    private AssetEnums() {}

    /** 素材档案状态：在用 / 归档 */
    public static final String ASSET_ACTIVE = "ACTIVE";
    public static final String ASSET_ARCHIVED = "ARCHIVED";

    /** 素材朝向：横图 / 竖图 / 方图；规格项的朝向也用 LANDSCAPE / PORTRAIT */
    public static final String ORIENT_LANDSCAPE = "LANDSCAPE";
    public static final String ORIENT_PORTRAIT = "PORTRAIT";
    public static final String ORIENT_SQUARE = "SQUARE";

    /** 默认投放用途（信息流投放）；同源素材换用途各开各档，指纹不跨用途合并 */
    public static final String PURPOSE_FEED = "FEED";

    /** 转档版本来源：上传首轮 / 手动重新转档 */
    public static final String SOURCE_UPLOAD = "UPLOAD";
    public static final String SOURCE_RETRY = "RETRY";

    /**
     * 多规格转档任务状态。
     * 待处理 / 处理中 / 全部成功 / 部分成功 / 全部失败：
     * 一个任务跑多个规格（横版/竖版 × 渠道），逐规格独立成败，全成才 SUCCESS，有成为部分，全败为失败。
     */
    public static final String JOB_PENDING = "PENDING";
    public static final String JOB_PROCESSING = "PROCESSING";
    public static final String JOB_SUCCESS = "SUCCESS";
    public static final String JOB_PARTIAL_SUCCESS = "PARTIAL_SUCCESS";
    public static final String JOB_FAILED = "FAILED";

    /** 逐规格任务项状态：待处理 / 处理中 / 成功 / 失败（失败原因落该项） */
    public static final String ITEM_PENDING = "PENDING";
    public static final String ITEM_PROCESSING = "PROCESSING";
    public static final String ITEM_SUCCESS = "SUCCESS";
    public static final String ITEM_FAILED = "FAILED";

    /** 规格没有单独配置朝向时，从规格名兜底推断（含 portrait/v 判竖版，其余按横版）。 */
    public static String orientOfSpecName(String name) {
        if (name == null) {
            return ORIENT_LANDSCAPE;
        }
        String n = name.toLowerCase();
        return n.contains("portrait") || n.endsWith("_v") || n.contains("_v_")
                ? ORIENT_PORTRAIT : ORIENT_LANDSCAPE;
    }
}
