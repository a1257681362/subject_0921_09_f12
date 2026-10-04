package com.evops.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.asset.AssetEnums;
import com.evops.asset.dto.JobInputParams;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 转档任务编排：建任务（PENDING，逐规格落任务项 + 输入参数快照）→ 后台处理（PROCESSING）
 * → 每个规格（横版/竖版 × 投放渠道）独立成败 → 全成 SUCCESS / 部分成 PARTIAL_SUCCESS / 全败 FAILED。
 * 成的有产物可下，败的带可读原因可重跑；状态与原因都落库，美工轮询任务号即可看到，不会干等。
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
    private final ObjectMapper objectMapper;

    public TranscodeService(TranscodeJobMapper jobMapper, TranscodeJobItemMapper itemMapper,
                            AssetVersionMapper versionMapper, AssetFileMapper fileMapper,
                            AssetMapper assetMapper, AssetStorage storage, ImageTranscoder transcoder,
                            AssetProperties properties, ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.transcoder = transcoder;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 建一版转档版本、对应任务和逐规格任务项。随外层事务提交，状态 PENDING。 */
    @Transactional(rollbackFor = Exception.class)
    public TranscodeJob startJob(Asset asset, int versionNo, String source, String operator,
                                 List<Spec> targetSpecs, JobInputParams input) {
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
        for (Spec spec : targetSpecs) {
            if (specNames.length() > 0) {
                specNames.append(",");
            }
            specNames.append(spec.getName());
        }
        job.setSpecs(specNames.toString());
        job.setTargetCount(targetSpecs.size());
        job.setSuccessCount(0);
        job.setFailCount(0);
        job.setInputParams(writeInput(input));
        job.setOperatorName(operator);
        jobMapper.insert(job);

        // job 有了 id，再落逐规格任务项
        int order = 0;
        for (Spec spec : targetSpecs) {
            TranscodeJobItem item = new TranscodeJobItem();
            item.setJobId(job.getId());
            item.setAssetId(asset.getId());
            item.setVersionId(version.getId());
            item.setUsage(spec.getName());
            item.setChannel(spec.getChannel());
            item.setOrientation(resolveOrient(spec));
            item.setSeq(order++);
            item.setStatus(AssetEnums.ITEM_PENDING);
            itemMapper.insert(item);
        }
        return job;
    }

    /**
     * 后台执行转档。仅 PENDING 任务能抢进 PROCESSING（条件更新兜底并发/重复执行）。
     * 逐规格独立 try：单个规格失败不拖累其余，最后按成败计数聚合状态。不抛出线程。
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

        List<TranscodeJobItem> items = itemMapper.selectList(new LambdaQueryWrapper<TranscodeJobItem>()
                .eq(TranscodeJobItem::getJobId, jobId)
                .orderByAsc(TranscodeJobItem::getSeq));
        int target = items.size();
        int success = 0;
        List<String> failures = new ArrayList<>();

        // 原图只解码一次；原图本身读不出/解不了属于整单前置失败：所有规格统一落这个原因
        SourceImage source;
        Asset asset = assetMapper.selectById(job.getAssetId());
        try {
            byte[] original = storage.read(version.getStoragePath());
            source = transcoder.decode(original);
        } catch (Exception e) {
            String reason = friendlyReason(e);
            log.warn("转档前置失败 job={}: {}", job.getJobNo(), reason);
            LocalDateTime now = LocalDateTime.now();
            for (TranscodeJobItem item : items) {
                finishItem(item.getId(), AssetEnums.ITEM_FAILED, null, reason, now);
                failures.add(item.getUsage() + "：" + reason);
            }
            String summary = "原图读取或解码失败，全部规格未产出：" + reason;
            markVersion(version.getId(), AssetEnums.JOB_FAILED, summary, version.getWidth(), version.getHeight());
            finishJob(jobId, AssetEnums.JOB_FAILED, 0, target, summary, now);
            return;
        }

        LocalDateTime batchTime = LocalDateTime.now();
        for (TranscodeJobItem item : items) {
            markItem(item.getId(), AssetEnums.ITEM_PROCESSING, null, null);
            try {
                Spec spec = properties.specByName(item.getUsage());
                if (spec == null) {
                    throw new IllegalStateException("规格「" + item.getUsage() + "」已下线或不再配置，无法转档");
                }
                Product product = transcoder.transcode(source, spec);
                String path = storage.storeProduct(asset.getId(), version.getVersionNo(), spec.getName(), product.bytes);

                AssetFile file = new AssetFile();
                file.setAssetId(asset.getId());
                file.setVersionId(version.getId());
                file.setUsage(spec.getName());
                file.setChannel(spec.getChannel());
                file.setOrientation(resolveOrient(spec));
                file.setWidth(product.width);
                file.setHeight(product.height);
                file.setLongEdge(Math.max(product.width, product.height));
                file.setFileSize(product.fileSize);
                file.setQualityRound(product.qualityRound);
                file.setFinalQuality(product.finalQuality);
                file.setContentType(product.contentType);
                file.setStoragePath(path);
                fileMapper.insert(file);

                finishItem(item.getId(), AssetEnums.ITEM_SUCCESS, file.getId(), null, batchTime);
                itemMapper.update(null, new LambdaUpdateWrapper<TranscodeJobItem>()
                        .eq(TranscodeJobItem::getId, item.getId())
                        .set(TranscodeJobItem::getWidth, product.width)
                        .set(TranscodeJobItem::getHeight, product.height)
                        .set(TranscodeJobItem::getFileSize, product.fileSize)
                        .set(TranscodeJobItem::getQualityRound, product.qualityRound)
                        .set(TranscodeJobItem::getFinalQuality, product.finalQuality));
                success++;
            } catch (Exception e) {
                String reason = friendlyReason(e);
                log.warn("规格转档失败 job={} spec={}: {}", job.getJobNo(), item.getUsage(), reason);
                finishItem(item.getId(), AssetEnums.ITEM_FAILED, null, reason, batchTime);
                failures.add(item.getUsage() + "：" + reason);
            }
        }

        int fail = target - success;
        String aggregate;
        String summary = null;
        if (fail == 0) {
            aggregate = AssetEnums.JOB_SUCCESS;
        } else if (success == 0) {
            aggregate = AssetEnums.JOB_FAILED;
            summary = "全部 " + target + " 个规格转档失败：" + joinReasons(failures);
        } else {
            aggregate = AssetEnums.JOB_PARTIAL_SUCCESS;
            summary = "部分规格成功：成功 " + success + "/" + target + "；失败 " + fail + " 个：" + joinReasons(failures);
        }

        markVersion(version.getId(), aggregate, summary, source.width, source.height);
        finishJob(jobId, aggregate, success, fail, summary, batchTime);

        // 最新版本指针：整单成功指过去；首版即部分成功（此前无可用版本）也指过去；整单失败不回退旧的好版本
        boolean pointNow = AssetEnums.JOB_SUCCESS.equals(aggregate)
                || (AssetEnums.JOB_PARTIAL_SUCCESS.equals(aggregate) && asset.getLatestVersionId() == null);
        if (pointNow) {
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

    public List<TranscodeJobItem> listItems(Long jobId) {
        return itemMapper.selectList(new LambdaQueryWrapper<TranscodeJobItem>()
                .eq(TranscodeJobItem::getJobId, jobId)
                .orderByAsc(TranscodeJobItem::getSeq));
    }

    public List<TranscodeJob> listJobs(Long assetId) {
        return jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getAssetId, assetId)
                .orderByDesc(TranscodeJob::getId));
    }

    private String resolveOrient(Spec spec) {
        if (spec.getOrientation() != null && !spec.getOrientation().trim().isEmpty()) {
            return spec.getOrientation().trim().toUpperCase();
        }
        return AssetEnums.orientOfSpecName(spec.getName());
    }

    private void markItem(Long itemId, String status, Long fileId, String reason) {
        itemMapper.update(null, new LambdaUpdateWrapper<TranscodeJobItem>()
                .eq(TranscodeJobItem::getId, itemId)
                .set(TranscodeJobItem::getStatus, status)
                .set(fileId != null, TranscodeJobItem::getFileId, fileId)
                .set(TranscodeJobItem::getFailReason, reason));
    }

    private void finishItem(Long itemId, String status, Long fileId, String reason, LocalDateTime time) {
        itemMapper.update(null, new LambdaUpdateWrapper<TranscodeJobItem>()
                .eq(TranscodeJobItem::getId, itemId)
                .set(TranscodeJobItem::getStatus, status)
                .set(fileId != null, TranscodeJobItem::getFileId, fileId)
                .set(TranscodeJobItem::getFailReason, reason)
                .set(TranscodeJobItem::getFinishedTime, time));
    }

    private void markVersion(Long versionId, String status, String reason, Integer width, Integer height) {
        versionMapper.update(null, new LambdaUpdateWrapper<AssetVersion>()
                .eq(AssetVersion::getId, versionId)
                .set(AssetVersion::getTranscodeStatus, status)
                .set(AssetVersion::getFailReason, reason)
                .set(width != null, AssetVersion::getWidth, width)
                .set(height != null, AssetVersion::getHeight, height));
    }

    private void finishJob(Long jobId, String status, int successCount, int failCount, String reason, LocalDateTime time) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .set(TranscodeJob::getStatus, status)
                .set(TranscodeJob::getSuccessCount, successCount)
                .set(TranscodeJob::getFailCount, failCount)
                .set(TranscodeJob::getFailReason, reason)
                .set(TranscodeJob::getFinishedTime, time));
    }

    private String joinReasons(List<String> failures) {
        String joined = String.join("；", failures);
        return joined.length() > REASON_MAX ? joined.substring(0, REASON_MAX) : joined;
    }

    private String writeInput(JobInputParams input) {
        if (input == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            log.warn("转档输入参数快照序列化失败，任务仍照常执行", e);
            return null;
        }
    }

    /** 异常消息面向美工展示；兜底异常不给堆栈，只给可读提示。 */
    private String friendlyReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "转档处理异常：" + e.getClass().getSimpleName();
        }
        return message.length() > REASON_MAX ? message.substring(0, REASON_MAX) : message;
    }
}
