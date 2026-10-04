package com.evops.asset;

/** 素材多规格转档的状态口径。 */
public final class AssetEnums {
    private AssetEnums() {}

    /** 素材档案状态：在用 / 归档 */
    public static final String ASSET_ACTIVE = "ACTIVE";
    public static final String ASSET_ARCHIVED = "ARCHIVED";

    /** 素材朝向：横图 / 竖图 / 方图 */
    public static final String ORIENT_LANDSCAPE = "LANDSCAPE";
    public static final String ORIENT_PORTRAIT = "PORTRAIT";
    public static final String ORIENT_SQUARE = "SQUARE";

    /** 转档版本来源：上传首轮 / 手动重新转档 */
    public static final String SOURCE_UPLOAD = "UPLOAD";
    public static final String SOURCE_RETRY = "RETRY";

    /** 多规格转档状态：待处理 / 处理中 / 成功 / 部分成功 / 失败（失败与部分成功的原因都必须落库可见） */
    public static final String JOB_PENDING = "PENDING";
    public static final String JOB_PROCESSING = "PROCESSING";
    public static final String JOB_SUCCESS = "SUCCESS";
    public static final String JOB_PARTIAL = "PARTIAL";
    public static final String JOB_FAILED = "FAILED";

    /** 质量压缩状态：首轮达标 / 降质压缩后达标 / 压到质量下限仍超体积上限（按体积最小一轮交差） */
    public static final String QS_DIRECT = "DIRECT";
    public static final String QS_COMPRESSED = "COMPRESSED";
    public static final String QS_OVER_LIMIT = "OVER_LIMIT";
}
