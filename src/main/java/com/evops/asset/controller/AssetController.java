package com.evops.asset.controller;

import com.evops.asset.dto.AssetDetailView;
import com.evops.asset.dto.JobDetailView;
import com.evops.asset.dto.JobInputParams;
import com.evops.asset.dto.SpecsView;
import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.entity.TranscodeJobItem;
import com.evops.asset.service.AssetService;
import com.evops.asset.service.TranscodeService;
import com.evops.asset.support.AssetProperties;
import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 素材流水与多规格转档：上传建档（用途+指纹去重）、翻查、档案详情、产物/旧版本下载、任务状态与明细、重新转档。 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final TranscodeService transcodeService;
    private final AssetProperties properties;
    private final ObjectMapper objectMapper;

    public AssetController(AssetService assetService, TranscodeService transcodeService,
                           AssetProperties properties, ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.transcodeService = transcodeService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 原图上传：同「用途+指纹」不另开档（同源换用途各开各档）；新图按渠道自动出 横版/竖版 多规格。
     * purposeCode 投放用途（默认 FEED）；channel 投放渠道（不传=全部已配置渠道规格）。
     */
    @PostMapping("/upload")
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "operatorName", required = false) String operatorName,
                                            @RequestParam(value = "remark", required = false) String remark,
                                            @RequestParam(value = "purposeCode", required = false) String purposeCode,
                                            @RequestParam(value = "channel", required = false) String channel) {
        return ApiResponse.ok(assetService.upload(file, operatorName, remark, purposeCode, channel));
    }

    /** 翻查：keyword 同时匹配文件名 / 素材编号；可按状态、投放用途过滤。 */
    @GetMapping
    public ApiResponse<List<Asset>> search(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String purposeCode) {
        return ApiResponse.ok(assetService.search(keyword, status, purposeCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<Asset> detail(@PathVariable Long id) {
        return ApiResponse.ok(assetService.requireById(id));
    }

    /** 档案全量：素材档案 + 转档版本（各带多规格产物）+ 转档任务状态。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<AssetDetailView> fullDetail(@PathVariable Long id) {
        Asset asset = assetService.requireById(id);
        AssetDetailView view = new AssetDetailView();
        view.setAsset(asset);
        List<AssetDetailView.VersionView> versionViews = new ArrayList<>();
        assetService.listVersions(id).forEach(v -> {
            AssetDetailView.VersionView vv = new AssetDetailView.VersionView();
            vv.setVersion(v);
            vv.setFiles(assetService.listFiles(v.getId()));
            versionViews.add(vv);
        });
        view.setVersions(versionViews);
        view.setJobs(assetService.listJobs(id));
        return ApiResponse.ok(view);
    }

    /** 当前生效的多规格转换器规格（用途 / 渠道 / 横竖朝向 / 比例 / 长边 / 体积上限），供前端标清用途。 */
    @GetMapping("/specs")
    public ApiResponse<SpecsView> specs() {
        SpecsView view = new SpecsView();
        List<SpecsView.SpecItem> items = new ArrayList<>();
        for (AssetProperties.Spec spec : properties.getSpecs()) {
            SpecsView.SpecItem item = new SpecsView.SpecItem();
            item.setName(spec.getName());
            item.setUsage(spec.getUsage());
            item.setChannel(spec.getChannel());
            item.setOrientation(spec.getOrientation());
            item.setRatio(spec.getRatio());
            item.setLongEdge(spec.getLongEdge());
            items.add(item);
        }
        view.setSpecs(items);
        view.setChannels(properties.channels());
        view.setMaxFileSizeBytes(properties.getMaxFileSize().toBytes());
        view.setMaxFileSizeKb(properties.getMaxFileSize().toKilobytes());
        view.setQualityInitial(properties.getQuality().getInitial());
        view.setQualityFloor(properties.getQuality().getFloor());
        view.setQualityDecrement(properties.getQuality().getDecrement());
        return ApiResponse.ok(view);
    }

    /** 转档任务状态轮询：PENDING / PROCESSING / SUCCESS / PARTIAL_SUCCESS / FAILED，失败带汇总原因。 */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<TranscodeJob> jobStatus(@PathVariable Long jobId) {
        return ApiResponse.ok(transcodeService.requireJob(jobId));
    }

    /** 任务明细回看：输入参数快照 + 产出版本 + 逐规格任务项（各自状态/产物指标/失败原因）+ 经手人。 */
    @GetMapping("/jobs/{jobId}/detail")
    public ApiResponse<JobDetailView> jobDetail(@PathVariable Long jobId) {
        TranscodeJob job = transcodeService.requireJob(jobId);
        JobDetailView view = new JobDetailView();
        view.setJob(job);
        view.setItems(transcodeService.listItems(jobId));
        view.setVersion(assetService.requireVersion(job.getAssetId(), job.getVersionId()));
        view.setInput(parseInput(job));
        return ApiResponse.ok(view);
    }

    /** 失败或想重跑：不抹历史，另起一版。可只重跑某渠道（channel）或指定规格（specs，可多个）。 */
    @PostMapping("/{id}/retry")
    public ApiResponse<TranscodeJob> retry(@PathVariable Long id,
                                           @RequestParam(required = false) String operatorName,
                                           @RequestParam(required = false) String channel,
                                           @RequestParam(required = false) List<String> specs) {
        return ApiResponse.ok(assetService.retry(id, operatorName, channel, specs));
    }

    /** 下载某个多规格产物（按产物 id，含历史版本产物）。 */
    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable Long fileId) {
        AssetFile file = assetService.requireProductFile(fileId);
        byte[] data = assetService.readProduct(file);
        String downloadName = file.getUsage() + "-" + file.getWidth() + "x" + file.getHeight() + ".jpg";
        return respond(data, file.getContentType(), downloadName);
    }

    /**
     * 按原版本稳定下载：素材 id + 版本 id + 规格用途(usage) 精确定位那一版产物。
     * 重转出新版本后，旧素材仍可按原版本下载，不会拿到新版本。
     */
    @GetMapping("/{id}/versions/{versionId}/files/{usage}/download")
    public ResponseEntity<ByteArrayResource> downloadVersionFile(@PathVariable Long id,
                                                                 @PathVariable Long versionId,
                                                                 @PathVariable String usage) {
        AssetFile file = assetService.requireVersionFile(id, versionId, usage);
        AssetVersion version = assetService.requireVersion(id, versionId);
        byte[] data = assetService.readProduct(file);
        String downloadName = "v" + version.getVersionNo() + "-" + file.getUsage()
                + "-" + file.getWidth() + "x" + file.getHeight() + ".jpg";
        return respond(data, file.getContentType(), downloadName);
    }

    /** 下载原图。 */
    @GetMapping("/{id}/original")
    public ResponseEntity<ByteArrayResource> downloadOriginal(@PathVariable Long id) {
        Asset asset = assetService.requireById(id);
        byte[] data = assetService.readOriginal(asset);
        String ext = "jpg";
        String path = asset.getStoragePath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            ext = path.substring(dot + 1);
        }
        String downloadName = asset.getAssetNo() + "-original." + ext;
        MediaType mediaType = asset.getContentType() != null
                ? MediaType.parseMediaType(asset.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return respond(data, mediaType.toString(), downloadName);
    }

    private JobInputParams parseInput(TranscodeJob job) {
        String raw = job.getInputParams();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, JobInputParams.class);
        } catch (Exception e) {
            // 历史脏数据不阻断明细回看：解析不出就不回填结构化输入，原始串仍在 job.inputParams
            return null;
        }
    }

    private ResponseEntity<ByteArrayResource> respond(byte[] data, String contentType, String filename) {
        if (contentType == null || contentType.trim().isEmpty()) {
            throw new BusinessException("产物缺少内容类型，无法下载");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename))
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    private String contentDisposition(String filename) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
        return "attachment; filename=\"" + filename.replaceAll("[\"\\\\]", "_") + "\"; filename*=UTF-8''" + encoded;
    }
}
