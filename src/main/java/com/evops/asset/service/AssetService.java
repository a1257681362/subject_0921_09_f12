package com.evops.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.asset.AssetEnums;
import com.evops.asset.dto.JobInputParams;
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
import com.evops.asset.support.AssetProperties.Spec;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 素材流水入口：
 * 原图上传 → 解码验尺寸 → 按「用途 + 文件指纹」建档（同用途同源只累加次数；换用途各开各档，不误合并）
 * → 挂首轮转档版本与任务（按渠道/规格选目标）→ 提交后后台逐规格转档。
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
     * 上传原图：同「用途 + 指纹」已存在则不另开档（只记一次重复上传）；
     * 同源素材换个用途（purposeCode 不同）各开各档。新图按渠道选规格自动发起转档（channel 空=全部已配置规格）。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadResult upload(MultipartFile file, String operatorName, String remark,
                               String purposeCode, String channel) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("没有收到图片文件，请选择原图后再上传");
        }
        String operator = Operators.resolve(operatorName);
        String purpose = normalizePurpose(purposeCode);
        byte[] bytes = readBytes(file);
        String fingerprint = ImageFingerprints.sha256(bytes);

        Asset existing = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getPurposeCode, purpose)
                .eq(Asset::getFingerprintSha256, fingerprint));
        if (existing != null) {
            // 同用途同一张图重复上传：按指纹认出来，不另开档、不重跑转档
            assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                    .eq(Asset::getId, existing.getId())
                    .setSql("upload_count = upload_count + 1")
                    .set(Asset::getFileName, file.getOriginalFilename())
                    .set(Asset::getContentType, file.getContentType())
                    .set(Asset::getLastUploadTime, LocalDateTime.now()));
            existing.setUploadCount(existing.getUploadCount() + 1);
            return UploadResult.deduped(existing);
        }

        List<Spec> targetSpecs = resolveSpecsForChannel(channel);
        // 新图：先同步解码验尺寸，读不出来当场告诉美工原因，不建空档案
        SourceImage source = transcoder.decode(bytes);
        String ext = resolveExt(file);
        String storagePath = storage.storeOriginal(fingerprint, ext, bytes);

        Asset asset = new Asset();
        asset.setAssetNo(String.format("AS%010d", assetMapper.nextAssetSeq()));
        asset.setPurposeCode(purpose);
        asset.setFingerprintSha256(fingerprint);
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
            // 并发重复上传兜底：(用途,指纹) 唯一约束拦下后按已存在处理，不另开档
            Asset raced = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getPurposeCode, purpose)
                    .eq(Asset::getFingerprintSha256, fingerprint));
            if (raced != null) {
                return UploadResult.deduped(raced);
            }
            throw e;
        }

        JobInputParams input = buildInput(AssetEnums.SOURCE_UPLOAD, purpose, channel, targetSpecs, asset, remark);
        TranscodeJob job = transcodeService.startJob(asset, 1, AssetEnums.SOURCE_UPLOAD, operator, targetSpecs, input);
        runAfterCommit(job.getId());
        return UploadResult.created(asset, job);
    }

    /**
     * 重新转档：不抹历史，另起一个转档版本（RETRY）和新任务。
     * 可只重跑某个渠道或指定规格；不传则重跑全部已配置规格。
     */
    @Transactional(rollbackFor = Exception.class)
    public TranscodeJob retry(Long assetId, String operatorName, String channel, List<String> specNames) {
        Asset asset = requireById(assetId);
        String operator = Operators.resolve(operatorName);
        Long versions = versionMapper.selectCount(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId));
        int nextVersionNo = (versions == null ? 0 : versions.intValue()) + 1;

        List<Spec> targetSpecs = resolveSpecs(channel, specNames);
        JobInputParams input = buildInput(AssetEnums.SOURCE_RETRY, asset.getPurposeCode(), channel, targetSpecs, asset, null);
        TranscodeJob job = transcodeService.startJob(asset, nextVersionNo, AssetEnums.SOURCE_RETRY,
                operator, targetSpecs, input);
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

    /** 翻查：文件名 / 编号模糊、按状态/用途过滤；空条件全量，新档案在前。 */
    public List<Asset> search(String keyword, String status, String purposeCode) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Asset::getFileName, kw).or().like(Asset::getAssetNo, kw));
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Asset::getStatus, status.trim());
        }
        if (purposeCode != null && !purposeCode.trim().isEmpty()) {
            wrapper.eq(Asset::getPurposeCode, purposeCode.trim().toUpperCase());
        }
        wrapper.orderByDesc(Asset::getId);
        return assetMapper.selectList(wrapper);
    }

    public List<AssetVersion> listVersions(Long assetId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId)
                .orderByAsc(AssetVersion::getVersionNo));
    }

    /** 取指定版本（回看/下载旧版本用），顺带校验归属该素材。 */
    public AssetVersion requireVersion(Long assetId, Long versionId) {
        AssetVersion version = versionMapper.selectById(versionId);
        if (version == null || !version.getAssetId().equals(assetId)) {
            throw new BusinessException("转档版本不存在或不属于该素材: versionId=" + versionId);
        }
        return version;
    }

    public List<AssetFile> listFiles(Long versionId) {
        return fileMapper.selectList(new LambdaQueryWrapper<AssetFile>()
                .eq(AssetFile::getVersionId, versionId)
                .orderByAsc(AssetFile::getUsage));
    }

    /** 取版本内某个用途的产物（旧版本也按版本+用途精确定位，不会拿到别的版本）。 */
    public AssetFile requireVersionFile(Long assetId, Long versionId, String usage) {
        requireVersion(assetId, versionId);
        AssetFile file = fileMapper.selectOne(new LambdaQueryWrapper<AssetFile>()
                .eq(AssetFile::getVersionId, versionId)
                .eq(AssetFile::getUsage, usage));
        if (file == null) {
            throw new BusinessException("该版本下没有规格「" + usage + "」的产物（可能该规格当时失败或未选）");
        }
        return file;
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

    private String normalizePurpose(String purposeCode) {
        if (purposeCode == null || purposeCode.trim().isEmpty()) {
            return AssetEnums.PURPOSE_FEED;
        }
        String p = purposeCode.trim().toUpperCase();
        if (!p.matches("[A-Z0-9_]{1,32}")) {
            throw new BusinessException("投放用途码不合法（限字母/数字/下划线，最长 32）: " + purposeCode);
        }
        return p;
    }

    /** 按渠道选规格：渠道空=全部；渠道不存在/该渠道无规格直接报错，不静默出空任务。 */
    private List<Spec> resolveSpecsForChannel(String channel) {
        List<Spec> specs = properties.specsForChannel(channel);
        if (specs.isEmpty()) {
            throw new BusinessException((channel == null || channel.trim().isEmpty())
                    ? "还没有配置任何转档规格"
                    : "投放渠道「" + channel + "」没有配置转档规格，可选渠道：" + properties.channels());
        }
        return specs;
    }

    /** 渠道 + 指定规格名取目标：规格名给了就按名精确选（仍校验属于该渠道、配置存在），否则按渠道取。 */
    private List<Spec> resolveSpecs(String channel, List<String> specNames) {
        if (specNames == null || specNames.isEmpty()) {
            return resolveSpecsForChannel(channel);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String s : specNames) {
            if (s != null && !s.trim().isEmpty()) {
                wanted.add(s.trim());
            }
        }
        if (wanted.isEmpty()) {
            return resolveSpecsForChannel(channel);
        }
        List<Spec> picked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : wanted) {
            Spec spec = properties.specByName(name);
            if (spec == null) {
                missing.add(name);
            } else if (channel == null || channel.trim().isEmpty()
                    || channel.trim().equalsIgnoreCase(spec.getChannel())) {
                picked.add(spec);
            } else {
                missing.add(name + "(不属于渠道 " + channel + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("这些规格当前不可用：" + missing + "；可选规格见 /api/assets/specs");
        }
        if (picked.isEmpty()) {
            throw new BusinessException("没有选中任何可转档的规格");
        }
        return picked;
    }

    private JobInputParams buildInput(String source, String purpose, String channel, List<Spec> targetSpecs,
                                      Asset asset, String remark) {
        JobInputParams input = new JobInputParams();
        input.setSource(source);
        input.setPurposeCode(purpose);
        List<String> channels = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> channelSet = new LinkedHashSet<>();
        for (Spec spec : targetSpecs) {
            names.add(spec.getName());
            channelSet.add(spec.getChannel());
        }
        channels.addAll(channelSet);
        input.setChannels(channels);
        input.setSpecs(names);
        input.setSourceFileName(asset.getFileName());
        input.setSourceWidth(asset.getWidth());
        input.setSourceHeight(asset.getHeight());
        input.setSourceFileSize(asset.getFileSize());
        input.setMaxFileSizeBytes(properties.getMaxFileSize().toBytes());
        input.setQualityInitial(properties.getQuality().getInitial());
        input.setQualityFloor(properties.getQuality().getFloor());
        input.setQualityDecrement(properties.getQuality().getDecrement());
        input.setRemark(remark);
        return input;
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
