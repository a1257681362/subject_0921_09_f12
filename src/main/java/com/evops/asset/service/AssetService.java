package com.evops.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.asset.AssetEnums;
import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.mapper.AssetFileMapper;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.mapper.AssetVersionMapper;
import com.evops.asset.support.AssetProperties;
import com.evops.asset.support.AssetStorage;
import com.evops.asset.support.ImageFingerprints;
import com.evops.asset.support.ImageTranscoder;
import com.evops.asset.support.ImageTranscoder.SourceImage;
import com.evops.common.BusinessException;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 素材流水入口：
 * 原图上传 → 解码验尺寸 → 按 文件指纹+投放用途 建档（同图同用途重传只累加次数；同图不同用途各开各的档）
 * → 挂首轮转档版本与任务（含逐规格明细）→ 提交后后台转档。
 */
@Service
public class AssetService {

    private static final List<String> ALLOWED_CONTENT = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif");

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetFileMapper fileMapper;
    private final AssetStorage storage;
    private final ImageTranscoder transcoder;
    private final TranscodeService transcodeService;
    private final TranscodeAsyncRunner asyncRunner;
    private final AssetProperties properties;

    public AssetService(AssetMapper assetMapper, AssetVersionMapper versionMapper,
                        AssetFileMapper fileMapper, AssetStorage storage,
                        ImageTranscoder transcoder, TranscodeService transcodeService,
                        TranscodeAsyncRunner asyncRunner, AssetProperties properties) {
        this.assetMapper = assetMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.storage = storage;
        this.transcoder = transcoder;
        this.transcodeService = transcodeService;
        this.asyncRunner = asyncRunner;
        this.properties = properties;
    }

    /**
     * 上传原图：文件指纹认同源，但 指纹+投放用途 才是一份档案——
     * 同图同用途重传只累加次数、不重跑转档；同图不同用途各开各的档，互不合并。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadResult upload(MultipartFile file, String purpose, String operatorName, String remark) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("没有收到图片文件，请选择原图后再上传");
        }
        String operator = Operators.resolve(operatorName);
        String resolvedPurpose = resolvePurpose(purpose);
        byte[] bytes = readBytes(file);
        String fingerprint = ImageFingerprints.sha256(bytes);

        Asset existing = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getFingerprintSha256, fingerprint)
                .eq(Asset::getPurpose, resolvedPurpose));
        if (existing != null) {
            // 同一张图同一用途重复上传：按指纹认出来，不另开档、不重跑转档
            assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                    .eq(Asset::getId, existing.getId())
                    .setSql("upload_count = upload_count + 1")
                    .set(Asset::getFileName, file.getOriginalFilename())
                    .set(Asset::getContentType, file.getContentType())
                    .set(Asset::getLastUploadTime, LocalDateTime.now()));
            existing.setUploadCount(existing.getUploadCount() + 1);
            return UploadResult.deduped(existing, properties.channelName(resolvedPurpose));
        }

        // 新图（或同源图的新用途）：先同步解码验尺寸，读不出来当场告诉美工原因，不建空档案
        SourceImage source = transcoder.decode(bytes);
        String ext = resolveExt(file);
        // 原图按指纹落一份：同源不同用途的档案共享同一份原图文件，但档案各自独立
        String storagePath = storage.storeOriginal(fingerprint, ext, bytes);

        Asset asset = new Asset();
        asset.setAssetNo(String.format("AS%010d", assetMapper.nextAssetSeq()));
        asset.setFingerprintSha256(fingerprint);
        asset.setPurpose(resolvedPurpose);
        asset.setFileName(file.getOriginalFilename());
        asset.setContentType(file.getContentType());
        asset.setWidth(source.width);
        asset.setHeight(source.height);
        asset.setOrientation(orientOf(source.width, source.height));
        asset.setFileSize((long) bytes.length);
        asset.setStoragePath(storagePath);
        asset.setUploadCount(1);
        asset.setLastUploadTime(LocalDateTime.now());
        asset.setStatus(AssetEnums.ASSET_ACTIVE);
        asset.setOperatorName(operator);
        asset.setRemark(remark);
        try {
            assetMapper.insert(asset);
        } catch (DuplicateKeyException e) {
            // 并发重复上传兜底：唯一约束拦下后按已存在处理，不另开档
            Asset raced = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getFingerprintSha256, fingerprint)
                    .eq(Asset::getPurpose, resolvedPurpose));
            if (raced != null) {
                return UploadResult.deduped(raced, properties.channelName(resolvedPurpose));
            }
            throw e;
        }

        TranscodeJob job = transcodeService.startJob(asset, 1, AssetEnums.SOURCE_UPLOAD, operator, null);
        runAfterCommit(job.getId());
        return UploadResult.created(asset, job, properties.channelName(resolvedPurpose));
    }

    /** 重新转档：不抹历史，另起一个转档版本（RETRY）和新任务，并挂到失败重试链上。 */
    @Transactional(rollbackFor = Exception.class)
    public TranscodeJob retry(Long assetId, String operatorName) {
        Asset asset = requireById(assetId);
        String operator = Operators.resolve(operatorName);
        Long versions = versionMapper.selectCount(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId));
        int nextVersionNo = (versions == null ? 0 : versions.intValue()) + 1;
        TranscodeJob lastJob = transcodeService.latestJob(assetId);
        TranscodeJob job = transcodeService.startJob(asset, nextVersionNo, AssetEnums.SOURCE_RETRY,
                operator, lastJob == null ? null : lastJob.getId());
        runAfterCommit(job.getId());
        return job;
    }

    public Asset requireById(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new BusinessException("素材档案不存在: id=" + id);
        }
        return asset;
    }

    public Asset requireByNo(String assetNo) {
        Asset asset = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getAssetNo, assetNo));
        if (asset == null) {
            throw new BusinessException("素材档案不存在: " + assetNo);
        }
        return asset;
    }

    /** 翻查：文件名 / 编号模糊、按状态与投放用途过滤；空条件全量，新档案在前。 */
    public List<Asset> search(String keyword, String status, String purpose) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Asset::getFileName, kw).or().like(Asset::getAssetNo, kw));
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Asset::getStatus, status.trim());
        }
        if (purpose != null && !purpose.trim().isEmpty()) {
            wrapper.eq(Asset::getPurpose, purpose.trim());
        }
        wrapper.orderByDesc(Asset::getId);
        return assetMapper.selectList(wrapper);
    }

    public List<AssetVersion> listVersions(Long assetId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId)
                .orderByAsc(AssetVersion::getVersionNo));
    }

    public List<AssetFile> listFiles(Long versionId) {
        return fileMapper.selectList(new LambdaQueryWrapper<AssetFile>()
                .eq(AssetFile::getVersionId, versionId)
                .orderByAsc(AssetFile::getUsage));
    }

    public List<TranscodeJob> listJobs(Long assetId) {
        return transcodeService.listJobs(assetId);
    }

    /** 取产物字节供下载，顺带校验归属。 */
    public AssetFile requireProductFile(Long fileId) {
        AssetFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException("规格产物不存在: id=" + fileId);
        }
        return file;
    }

    public byte[] readProduct(AssetFile file) {
        return storage.read(file.getStoragePath());
    }

    public byte[] readOriginal(Asset asset) {
        return storage.read(asset.getStoragePath());
    }

    /** 投放用途口径：缺省取配置的第一个渠道；填了就必须是配置里的渠道，否则给可读原因。 */
    private String resolvePurpose(String purpose) {
        if (purpose == null || purpose.trim().isEmpty()) {
            String fallback = properties.defaultPurpose();
            if (fallback == null) {
                throw new BusinessException("系统还没有配置投放渠道（asset.channels），请联系管理员配置后再上传");
            }
            return fallback;
        }
        String code = purpose.trim();
        if (!properties.hasChannel(code)) {
            throw new BusinessException("投放用途不支持: " + code
                    + "，当前支持的用途: " + properties.channelOptionsText());
        }
        return code;
    }

    /** 等当前事务提交后再让后台线程跑，避免线程先于 INSERT 提交读到“任务不存在”。 */
    private void runAfterCommit(Long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncRunner.run(jobId);
                }
            });
        } else {
            asyncRunner.run(jobId);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT.contains(contentType.toLowerCase())) {
            throw new BusinessException("只收图片文件（JPG / PNG / GIF），收到的类型是: " + contentType);
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }
    }

    private String resolveExt(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase();
                if (ext.matches("[a-z0-9]{1,5}")) {
                    return ext;
                }
            }
        }
        if ("image/png".equalsIgnoreCase(file.getContentType())) {
            return "png";
        }
        if ("image/gif".equalsIgnoreCase(file.getContentType())) {
            return "gif";
        }
        return "jpg";
    }

    private String orientOf(int width, int height) {
        if (width > height) {
            return AssetEnums.ORIENT_LANDSCAPE;
        }
        if (width < height) {
            return AssetEnums.ORIENT_PORTRAIT;
        }
        return AssetEnums.ORIENT_SQUARE;
    }
}
