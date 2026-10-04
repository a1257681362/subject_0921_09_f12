package com.evops.asset.controller;

import com.evops.asset.dto.AssetDetailView;
import com.evops.asset.dto.JobDetailView;
import com.evops.asset.dto.SpecsView;
import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.service.AssetService;
import com.evops.asset.service.TranscodeService;
import com.evops.asset.support.AssetProperties;
import com.evops.common.ApiResponse;
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

/** 素材流水与多规格转档：上传建档（指纹+用途去重）、翻查、档案详情、任务明细回看、产物下载、状态轮询、重新转档。 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final TranscodeService transcodeService;
    private final AssetProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssetController(AssetService assetService, TranscodeService transcodeService,
                           AssetProperties properties) {
        this.assetService = assetService;
        this.transcodeService = transcodeService;
        this.properties = properties;
    }

    /** 原图上传：同指纹同用途不另开档；新图按用途所属渠道自动出横竖两版常规规格。 */
    @PostMapping("/upload")
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "purpose", required = false) String purpose,
                                            @RequestParam(value = "operatorName", required = false) String operatorName,
                                            @RequestParam(value = "remark", required = false) String remark) {
        return ApiResponse.ok(assetService.upload(file, purpose, operatorName, remark));
    }

    /** 翻查：keyword 同时匹配文件名 / 素材编号；purpose 按投放用途过滤。 */
    @GetMapping
    public ApiResponse<List<Asset>> search(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String purpose) {
        return ApiResponse.ok(assetService.search(keyword, status, purpose));
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

    /** 当前生效的投放渠道与常规规格（用途 / 横竖 / 比例 / 长边 / 体积上限），供前端标清用途。 */
    @GetMapping("/specs")
    public ApiResponse<SpecsView> specs() {
        SpecsView view = new SpecsView();
        List<SpecsView.ChannelItem> channels = new ArrayList<>();
        for (AssetProperties.Channel channel : properties.getChannels()) {
            SpecsView.ChannelItem item = new SpecsView.ChannelItem();
            item.setCode(channel.getCode());
            item.setName(channel.getName());
            channels.add(item);
        }
        view.setChannels(channels);
        view.setDefaultPurpose(properties.defaultPurpose());
        List<SpecsView.SpecItem> items = new ArrayList<>();
        for (AssetProperties.Spec spec : properties.getSpecs()) {
            SpecsView.SpecItem item = new SpecsView.SpecItem();
            item.setName(spec.getName());
            item.setUsage(spec.getUsage());
            String channel = spec.getChannel() == null || spec.getChannel().trim().isEmpty()
                    ? properties.defaultPurpose() : spec.getChannel();
            item.setChannel(channel);
            try {
                item.setOrientation(spec.orientation());
            } catch (Exception e) {
                item.setOrientation("UNKNOWN");
            }
            item.setRatio(spec.getRatio());
            item.setLongEdge(spec.getLongEdge());
            items.add(item);
        }
        view.setSpecs(items);
        view.setMaxFileSizeBytes(properties.getMaxFileSize().toBytes());
        view.setMaxFileSizeKb(properties.getMaxFileSize().toKilobytes());
        view.setQualityInitial(properties.getQuality().getInitial());
        view.setQualityFloor(properties.getQuality().getFloor());
        view.setQualityDecrement(properties.getQuality().getDecrement());
        return ApiResponse.ok(view);
    }

    /** 转档任务状态轮询：PENDING / PROCESSING / SUCCESS / PARTIAL / FAILED，失败与部分成功带原因。 */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<TranscodeJob> jobStatus(@PathVariable Long jobId) {
        return ApiResponse.ok(transcodeService.requireJob(jobId));
    }

    /** 任务明细回看：逐规格状态与输入参数、产出版本与产物、操作人、提交时冻结的参数快照。 */
    @GetMapping("/jobs/{jobId}/detail")
    public ApiResponse<JobDetailView> jobDetail(@PathVariable Long jobId) {
        TranscodeJob job = transcodeService.requireJob(jobId);
        JobDetailView view = new JobDetailView();
        view.setJob(job);
        view.setItems(transcodeService.listItems(jobId));
        AssetVersion version = assetService.listVersions(job.getAssetId()).stream()
                .filter(v -> v.getId().equals(job.getVersionId())).findFirst().orElse(null);
        view.setVersion(version);
        view.setFiles(version == null ? new ArrayList<>() : assetService.listFiles(version.getId()));
        view.setInputParams(parseSnapshot(job.getSpecsSnapshot()));
        return ApiResponse.ok(view);
    }

    /** 失败或想重跑：不抹历史，另起一版重新转档（挂失败重试链）。 */
    @PostMapping("/{id}/retry")
    public ApiResponse<TranscodeJob> retry(@PathVariable Long id,
                                           @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(assetService.retry(id, operatorName));
    }

    /** 下载某个多规格产物（任何历史版本的产物都可按原版本下载）。 */
    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable Long fileId) {
        AssetFile file = assetService.requireProductFile(fileId);
        byte[] data = assetService.readProduct(file);
        String downloadName = file.getUsage() + "-" + file.getWidth() + "x" + file.getHeight() + ".jpg";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(downloadName))
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
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
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(downloadName))
                .contentType(mediaType)
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    /** 快照 JSON 解析成对象供前端直接渲染；老任务没有快照或快照损坏时原样给原文。 */
    private Object parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot, Object.class);
        } catch (Exception e) {
            return snapshot;
        }
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
