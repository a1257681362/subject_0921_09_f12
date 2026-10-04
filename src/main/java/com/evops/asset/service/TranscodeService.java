package com.evops.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.asset.AssetEnums;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.entity.TranscodeJobItem;
import com.evops.asset.mapper.AssetFileMapper;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.mapper.AssetVersionMapper;
import com.evops.asset.mapper.TranscodeJobItemMapper;
import com.evops.asset.mapper.TranscodeJobMapper;
import com.evops.asset.support.AssetProperties;
import com.evops.asset.support.AssetStorage;
import com.evops.asset.support.ImageTranscoder;
import com.evops.asset.support.ImageTranscoder.Product;
import com.evops.asset.support.ImageTranscoder.SourceImage;
import com.evops.asset.support.AssetProperties.Spec;
import com.evops.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转档任务编排：建任务（PENDING，逐规格明细随行）→ 后台处理（PROCESSING）→
 * 全成 SUCCESS / 成败各半 PARTIAL / 全败 FAILED。
 * 每个规格独立成败：质量压缩状态、失败原因逐行落库，任务明细可回看输入参数、产出版本与操作人。
 */
@Service
public class TranscodeService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeService.class);
    private static final int REASON_MAX = 900;

    private final TranscodeJobMapper jobMapper;
    private final TranscodeJobItemMapper itemMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetFileMapper fileMapper;
    private final AssetMapper assetMapper;
    private final AssetStorage storage;
    private final ImageTranscoder transcoder;
    private final AssetProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TranscodeService(TranscodeJobMapper jobMapper, TranscodeJobItemMapper itemMapper,
                            AssetVersionMapper versionMapper, AssetFileMapper fileMapper,
                            AssetMapper assetMapper, AssetStorage storage,
                            ImageTranscoder transcoder, AssetProperties properties) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.transcoder = transcoder;
        this.properties = properties;
    }

    /**
     * 建一版转档版本、对应任务与逐规格明细（输入参数随任务快照）。随外层事务提交，状态 PENDING。
     * 规格按素材投放用途所属渠道取；重试任务挂 retryOfJobId 形成失败重试链。
     */
    @Transactional(rollbackFor = Exception.class)
    public TranscodeJob startJob(Asset asset, int versionNo, String source, String operator, Long retryOfJobId) {
        List<Spec> specs = properties.specsForChannel(asset.getPurpose());
        if (specs.isEmpty()) {
            throw new BusinessException("投放用途 " + asset.getPurpose() + "（"
                    + properties.channelName(asset.getPurpose()) + "）没有配置转档规格，请检查 asset.specs 配置");
        }

        AssetVersion version = new AssetVersion();
        version.setAssetId(asset.getId());
        version.setVersionNo(versionNo);
        version.setSource(source);
        version.setFileName(asset.getFileName());
        version.setFileSize(asset.getFileSize());
        version.setWidth(asset.getWidth());
        version.setHeight(asset.getHeight());
        version.setStoragePath(asset.getStoragePath());
        version.setTranscodeStatus(AssetEnums.JOB_PENDING);
        version.setOperatorName(operator);
        versionMapper.insert(version);

        TranscodeJob job = new TranscodeJob();
        job.setJobNo(String.format("TJ%010d", jobMapper.nextJobSeq()));
        job.setAssetId(asset.getId());
        job.setVersionId(version.getId());
        job.setStatus(AssetEnums.JOB_PENDING);
        StringBuilder specNames = new StringBuilder();
        for (Spec spec : specs) {
            if (specNames.length() > 0) {
                specNames.append(",");
            }
            specNames.append(spec.getName());
        }
        job.setSpecs(specNames.toString());
        job.setSpecsSnapshot(buildSnapshot(asset.getPurpose(), specs));
        job.setTotalCount(specs.size());
        job.setSuccessCount(0);
        job.setRetryOfJobId(retryOfJobId);
        job.setOperatorName(operator);
        jobMapper.insert(job);

        // 逐规格明细：输入参数（比例/长边/渠道/朝向）随行落库，执行时按明细参数跑，回看与执行同一份口径
        for (Spec spec : specs) {
            TranscodeJobItem item = new TranscodeJobItem();
            item.setJobId(job.getId());
            item.setAssetId(asset.getId());
            item.setVersionId(version.getId());
            item.setSpecName(spec.getName());
            item.setChannel(asset.getPurpose());
            item.setOrientation(safeOrientation(spec));
            item.setRatio(spec.getRatio());
            item.setLongEdge(spec.getLongEdge());
            item.setStatus(AssetEnums.JOB_PENDING);
            itemMapper.insert(item);
        }
        return job;
    }

    /**
     * 后台执行转档。仅 PENDING 任务能抢进 PROCESSING（条件更新兜底并发/重复执行）。
     * 每个规格独立成败：单规格失败不拖垮其他规格，按 全成/部分成功/全败 收口；任何失败都落原因，不抛出线程。
     */
    public void processJob(Long jobId) {
        TranscodeJob job = jobMapper.selectById(jobId);
        if (job == null || !AssetEnums.JOB_PENDING.equals(job.getStatus())) {
            return;
        }
        int claimed = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .eq(TranscodeJob::getStatus, AssetEnums.JOB_PENDING)
                .set(TranscodeJob::getStatus, AssetEnums.JOB_PROCESSING));
        if (claimed == 0) {
            return;
        }
        AssetVersion version = versionMapper.selectById(job.getVersionId());
        markVersion(version.getId(), AssetEnums.JOB_PROCESSING, null, version.getWidth(), version.getHeight());
        List<TranscodeJobItem> items = listItems(jobId);
        Asset asset = assetMapper.selectById(job.getAssetId());

        // 原图读不出来是全任务级失败：逐明细同落原因，任务 FAILED
        SourceImage source;
        try {
            byte[] original = storage.read(version.getStoragePath());
            source = transcoder.decode(original);
        } catch (Exception e) {
            String reason = friendlyReason(e);
            log.warn("转档失败 job={} version={}: {}", job.getJobNo(), version.getVersionNo(), reason);
            for (TranscodeJobItem item : items) {
                markItem(item.getId(), AssetEnums.JOB_FAILED, null, null, null, null, null, reason);
            }
            markVersion(version.getId(), AssetEnums.JOB_FAILED, reason, version.getWidth(), version.getHeight());
            finishJob(jobId, AssetEnums.JOB_FAILED, 0, reason);
            return;
        }

        int success = 0;
        StringBuilder failSummary = new StringBuilder();
        for (TranscodeJobItem item : items) {
            markItem(item.getId(), AssetEnums.JOB_PROCESSING, null, null, null, null, null, null);
            try {
                // 按明细落库的输入参数执行，执行与回看同一份口径
                Product product = transcoder.transcode(source, specOf(item));
                String path = storage.storeProduct(asset.getId(), version.getVersionNo(),
                        item.getChannel(), item.getSpecName(), product.bytes);
                AssetFile file = new AssetFile();
                file.setAssetId(asset.getId());
                file.setVersionId(version.getId());
                file.setUsage(item.getSpecName());
                file.setChannel(item.getChannel());
                file.setOrientation(item.getOrientation());
                file.setWidth(product.width);
                file.setHeight(product.height);
                file.setLongEdge(Math.max(product.width, product.height));
                file.setFileSize(product.fileSize);
                file.setQualityRound(product.qualityRound);
                file.setFinalQuality(product.finalQuality);
                file.setQualityState(qualityStateOf(product));
                file.setContentType(product.contentType);
                file.setStoragePath(path);
                fileMapper.insert(file);
                markItem(item.getId(), AssetEnums.JOB_SUCCESS, product.qualityRound, product.finalQuality,
                        file.getQualityState(), product.fileSize, file.getId(), null);
                success++;
            } catch (Exception e) {
                String reason = friendlyReason(e);
                log.warn("转档规格失败 job={} spec={}: {}", job.getJobNo(), item.getSpecName(), reason);
                markItem(item.getId(), AssetEnums.JOB_FAILED, null, null, null, null, null, reason);
                if (failSummary.length() > 0) {
                    failSummary.append("；");
                }
                failSummary.append(item.getSpecName()).append("：").append(reason);
            }
        }

        int total = items.size();
        String finalStatus = success == total ? AssetEnums.JOB_SUCCESS
                : (success == 0 ? AssetEnums.JOB_FAILED : AssetEnums.JOB_PARTIAL);
        String reason = failSummary.length() > 0 ? truncate(failSummary.toString()) : null;
        markVersion(version.getId(), finalStatus, reason, source.width, source.height);
        finishJob(jobId, finalStatus, success, reason);
        if (success > 0) {
            // 最新版本指针指到有产出的版本（成功或部分成功）；旧版本产物始终可下载
            assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                    .eq(Asset::getId, asset.getId())
                    .set(Asset::getLatestVersionId, version.getId()));
        }
    }

    public TranscodeJob requireJob(Long id) {
        TranscodeJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new IllegalArgumentException("转档任务不存在: id=" + id);
        }
        return job;
    }

    public TranscodeJob requireJobByNo(String jobNo) {
        TranscodeJob job = jobMapper.selectOne(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getJobNo, jobNo));
        if (job == null) {
            throw new IllegalArgumentException("转档任务不存在: " + jobNo);
        }
        return job;
    }

    public List<TranscodeJob> listJobs(Long assetId) {
        return jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getAssetId, assetId)
                .orderByDesc(TranscodeJob::getId));
    }

    /** 任务逐规格明细（输入参数 + 各自状态），按规格落库顺序。 */
    public List<TranscodeJobItem> listItems(Long jobId) {
        return itemMapper.selectList(new LambdaQueryWrapper<TranscodeJobItem>()
                .eq(TranscodeJobItem::getJobId, jobId)
                .orderByAsc(TranscodeJobItem::getId));
    }

    /** 该素材最近一次任务（失败重试链挂到它上面）。 */
    public TranscodeJob latestJob(Long assetId) {
        List<TranscodeJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getAssetId, assetId)
                .orderByDesc(TranscodeJob::getId)
                .last("LIMIT 1"));
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    /** 输入参数快照：用途、各规格比例/长边、体积上限、质量档，随任务冻结可回看。 */
    private String buildSnapshot(String purpose, List<Spec> specs) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("purpose", purpose);
        snapshot.put("purposeName", properties.channelName(purpose));
        List<Map<String, Object>> specList = new ArrayList<>();
        for (Spec spec : specs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", spec.getName());
            s.put("usage", spec.getUsage());
            s.put("channel", purpose);
            s.put("orientation", safeOrientation(spec));
            s.put("ratio", spec.getRatio());
            s.put("longEdge", spec.getLongEdge());
            specList.add(s);
        }
        snapshot.put("specs", specList);
        snapshot.put("maxFileSizeBytes", properties.getMaxFileSize().toBytes());
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("initial", properties.getQuality().getInitial());
        quality.put("floor", properties.getQuality().getFloor());
        quality.put("decrement", properties.getQuality().getDecrement());
        snapshot.put("quality", quality);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("转档输入参数快照序列化失败", e);
        }
    }

    /** 明细行的输入参数还原成规格定义，执行与回看同一份口径。 */
    private Spec specOf(TranscodeJobItem item) {
        Spec spec = new Spec();
        spec.setName(item.getSpecName());
        spec.setChannel(item.getChannel());
        spec.setRatio(item.getRatio());
        spec.setLongEdge(item.getLongEdge());
        return spec;
    }

    /** 比例配置非法时朝向先落 UNKNOWN，该明细执行时会带可读原因失败，不拖垮整单。 */
    private String safeOrientation(Spec spec) {
        try {
            return spec.orientation();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** 质量压缩状态：首轮达标 / 降质压缩后达标 / 压到下限仍超标（按最小一轮交差）。 */
    private String qualityStateOf(Product product) {
        if (!product.withinLimit) {
            return AssetEnums.QS_OVER_LIMIT;
        }
        return product.qualityRound <= 1 ? AssetEnums.QS_DIRECT : AssetEnums.QS_COMPRESSED;
    }

    private void markVersion(Long versionId, String status, String reason, Integer width, Integer height) {
        versionMapper.update(null, new LambdaUpdateWrapper<AssetVersion>()
                .eq(AssetVersion::getId, versionId)
                .set(AssetVersion::getTranscodeStatus, status)
                .set(AssetVersion::getFailReason, reason)
                .set(width != null, AssetVersion::getWidth, width)
                .set(height != null, AssetVersion::getHeight, height));
    }

    private void markItem(Long itemId, String status, Integer qualityRound, Float finalQuality,
                          String qualityState, Long fileSize, Long fileId, String reason) {
        itemMapper.update(null, new LambdaUpdateWrapper<TranscodeJobItem>()
                .eq(TranscodeJobItem::getId, itemId)
                .set(TranscodeJobItem::getStatus, status)
                .set(qualityRound != null, TranscodeJobItem::getQualityRound, qualityRound)
                .set(finalQuality != null, TranscodeJobItem::getFinalQuality, finalQuality)
                .set(qualityState != null, TranscodeJobItem::getQualityState, qualityState)
                .set(fileSize != null, TranscodeJobItem::getFileSize, fileSize)
                .set(fileId != null, TranscodeJobItem::getFileId, fileId)
                .set(TranscodeJobItem::getFailReason, reason));
    }

    private void finishJob(Long jobId, String status, int successCount, String reason) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .set(TranscodeJob::getStatus, status)
                .set(TranscodeJob::getSuccessCount, successCount)
                .set(TranscodeJob::getFailReason, reason)
                .set(TranscodeJob::getFinishedTime, LocalDateTime.now()));
    }

    /** 异常消息面向美工展示；兜底异常不给堆栈，只给可读提示。 */
    private String friendlyReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "转档处理异常：" + e.getClass().getSimpleName();
        }
        return truncate(message);
    }

    private String truncate(String text) {
        return text.length() > REASON_MAX ? text.substring(0, REASON_MAX) : text;
    }
}
