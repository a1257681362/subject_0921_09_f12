package com.evops.asset;

import com.evops.asset.dto.JobInputParams;
import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.entity.TranscodeJobItem;
import com.evops.asset.service.AssetService;
import com.evops.asset.service.TranscodeService;
import com.evops.asset.support.AssetProperties;
import com.evops.asset.support.AssetProperties.Spec;
import com.evops.common.BusinessException;
import com.evops.ledger.service.SchemaVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F2/F12 素材流水与多规格转档端到端测试（真实 H2 + 真实异步线程 + 真实 ImageIO 转档落盘）。 */
@SpringBootTest
class AssetTranscodeFlowTest {

    @Autowired private AssetService assetService;
    @Autowired private TranscodeService transcodeService;
    @Autowired private AssetProperties properties;
    @Autowired private SchemaVersionService schemaVersionService;
    @Autowired private ObjectMapper objectMapper;

    /** 等到转档任务进入终态（异步线程池真实执行）。 */
    private TranscodeJob awaitTerminal(Long jobId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            TranscodeJob job = transcodeService.requireJob(jobId);
            if (!AssetEnums.JOB_PENDING.equals(job.getStatus())
                    && !AssetEnums.JOB_PROCESSING.equals(job.getStatus())) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("转档任务迟迟没有结束: jobId=" + jobId);
    }

    private Map<String, TranscodeJobItem> itemMap(Long jobId) {
        return transcodeService.listItems(jobId).stream()
                .collect(Collectors.toMap(TranscodeJobItem::getUsage, i -> i));
    }

    private byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 逐像素随机噪声：JPEG 最难压的高熵图，用来逼出二轮质量压缩。 */
    private BufferedImage noise(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        return img;
    }

    private MockMultipartFile pngFile(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    @Test
    void 同一用途同一张图重复上传按指纹认出_不另开档_不重跑转档() throws Exception {
        byte[] bytes = png(noise(1600, 1200, 1));
        UploadResult first = assetService.upload(pngFile("banner-a.png", bytes), "tester", null, "FEED", "feed");
        assertFalse(first.isDeduped());
        awaitTerminal(first.getJob().getId());
        Long assetId = first.getAsset().getId();

        // 同一份内容、同一用途，换个文件名再传：指纹一致
        UploadResult again = assetService.upload(pngFile("banner-b-改名.png", bytes), "tester", null, "FEED", "feed");
        assertTrue(again.isDeduped());
        assertEquals(assetId, again.getAsset().getId());

        Asset reloaded = assetService.requireById(assetId);
        assertEquals(2, reloaded.getUploadCount());
        List<Asset> rows = assetService.search(reloaded.getAssetNo(), null, null);
        assertEquals(1, rows.size());
        // 重复上传没有另起转档版本
        assertEquals(1, assetService.listVersions(assetId).size());
    }

    @Test
    void 同源素材投不同用途各开各档_指纹不误合并() throws Exception {
        byte[] bytes = png(noise(1600, 1200, 11));
        UploadResult feed = assetService.upload(pngFile("same.png", bytes), "tester", null, "FEED", "feed");
        awaitTerminal(feed.getJob().getId());
        // 同一张图、另一个用途：必须是新档案，不能并进 FEED 那张
        UploadResult splash = assetService.upload(pngFile("same.png", bytes), "tester", null, "SPLASH", "feed");
        assertFalse(splash.isDeduped());
        assertNotEquals(feed.getAsset().getId(), splash.getAsset().getId());
        assertEquals("SPLASH", splash.getAsset().getPurposeCode());
        // 两份档案都在，按用途过滤各得一份
        assertEquals(1, assetService.search(null, null, "FEED").stream()
                .filter(a -> a.getId().equals(feed.getAsset().getId())).count());
        assertEquals(1, assetService.search(null, null, "SPLASH").size());
    }

    @Test
    void 按投放渠道出横竖两版_长边压进限定像素() throws Exception {
        byte[] bytes = png(noise(2000, 1000, 2));
        // 只投信息流渠道：横版 1280x720、竖版 720x1280
        UploadResult result = assetService.upload(pngFile("wide.png", bytes), "tester", null, null, "feed");
        TranscodeJob job = awaitTerminal(result.getJob().getId());

        assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());
        assertEquals(2, job.getTargetCount());
        assertEquals(2, job.getSuccessCount());
        assertEquals(0, job.getFailCount());
        Map<String, TranscodeJobItem> items = itemMap(job.getId());
        AssetFile landscape = assetService.listFiles(job.getVersionId()).stream()
                .filter(f -> "feed_landscape".equals(f.getUsage())).findFirst().orElseThrow(AssertionError::new);
        AssetFile portrait = assetService.listFiles(job.getVersionId()).stream()
                .filter(f -> "feed_portrait".equals(f.getUsage())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("feed", landscape.getChannel());
        assertEquals("LANDSCAPE", landscape.getOrientation());
        assertEquals(1280, landscape.getWidth());
        assertEquals(720, landscape.getHeight());
        assertEquals(720, portrait.getWidth());
        assertEquals(1280, portrait.getHeight());
        assertTrue(portrait.getLongEdge() <= 1280);
        // 任务项也都成功且回指产物
        assertEquals(AssetEnums.ITEM_SUCCESS, items.get("feed_landscape").getStatus());
        assertEquals(landscape.getId(), items.get("feed_landscape").getFileId());
        assertTrue(assetService.readProduct(landscape).length > 0);

        AssetVersion version = assetService.listVersions(result.getAsset().getId()).get(0);
        assertEquals(AssetEnums.JOB_SUCCESS, version.getTranscodeStatus());
        assertEquals("LANDSCAPE", result.getAsset().getOrientation());
    }

    @Test
    void 另一投放渠道出该渠道规格_不传渠道则全部渠道齐出() throws Exception {
        byte[] bytes = png(noise(1920, 1080, 12));
        UploadResult moments = assetService.upload(pngFile("m.png", bytes), "tester", null, null, "moments");
        TranscodeJob mJob = awaitTerminal(moments.getJob().getId());
        assertEquals(AssetEnums.JOB_SUCCESS, mJob.getStatus());
        // 朋友圈渠道长边 1080
        AssetFile ml = assetService.listFiles(mJob.getVersionId()).stream()
                .filter(f -> "moments_landscape".equals(f.getUsage())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1080, ml.getWidth());
        assertEquals(608, ml.getHeight());
        assertEquals("moments", ml.getChannel());
        // 只出朋友圈两个规格，不串渠道
        assertNull(assetService.listFiles(mJob.getVersionId()).stream()
                .filter(f -> "feed_landscape".equals(f.getUsage())).findFirst().orElse(null));

        // 不传渠道：两个渠道 4 个规格齐出（换一张图，避免与上面同用途同指纹被去重）
        byte[] bytes2 = png(noise(1920, 1080, 13));
        UploadResult all = assetService.upload(pngFile("m2.png", bytes2), "tester", null, null, null);
        TranscodeJob aJob = awaitTerminal(all.getJob().getId());
        assertEquals(4, aJob.getTargetCount());
        assertEquals(4, aJob.getSuccessCount());
        assertEquals(4, assetService.listFiles(aJob.getVersionId()).size());
    }

    @Test
    void 常规规格出固定标准尺寸_小图也补齐为规格尺寸() throws Exception {
        byte[] bytes = png(noise(640, 360, 3));
        UploadResult result = assetService.upload(pngFile("small.png", bytes), "tester", null, null, "feed");
        TranscodeJob job = awaitTerminal(result.getJob().getId());
        assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());
        Map<String, AssetFile> files = assetService.listFiles(job.getVersionId()).stream()
                .collect(Collectors.toMap(AssetFile::getUsage, f -> f));
        AssetFile landscape = files.get("feed_landscape");
        AssetFile portrait = files.get("feed_portrait");
        assertEquals(1280, landscape.getWidth());
        assertEquals(720, landscape.getHeight());
        assertEquals(720, portrait.getWidth());
        assertEquals(1280, portrait.getHeight());
    }

    @Test
    void 体积超标自动再压一轮质量() throws Exception {
        float initial = properties.getQuality().getInitial();
        float floor = properties.getQuality().getFloor();
        org.springframework.util.unit.DataSize originLimit = properties.getMaxFileSize();
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofBytes(1));
        try {
            byte[] bytes = png(noise(1280, 720, 42));
            UploadResult result = assetService.upload(pngFile("noisy.png", bytes), "tester", null, null, "feed");
            TranscodeJob job = awaitTerminal(result.getJob().getId());
            // 压到下限仍超标不算规格失败：按体积最小一轮交差，任务整体成功
            assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());

            AssetFile landscape = assetService.listFiles(job.getVersionId()).stream()
                    .filter(f -> "feed_landscape".equals(f.getUsage())).findFirst().orElseThrow(AssertionError::new);
            assertTrue(landscape.getQualityRound() >= 2, "上限 1 字节必须多轮压缩，实际轮次=" + landscape.getQualityRound());
            assertTrue(landscape.getFinalQuality() < initial, "最终质量应低于首轮 " + initial);
            assertEquals(floor, landscape.getFinalQuality(), 0.001, "应一直压到质量下限");
            assertTrue(landscape.getFileSize() > 1, "下限仍压不进时按最小一轮交差");
        } finally {
            properties.setMaxFileSize(originLimit);
        }
    }

    @Test
    void 上传损坏图片当场告知原因() {
        byte[] junk = "这压根不是图片".getBytes();
        MockMultipartFile bad = new MockMultipartFile("file", "bad.jpg", "image/jpeg", junk);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.upload(bad, "tester", null, null, "feed"));
        assertTrue(ex.getMessage().contains("格式不支持") || ex.getMessage().contains("损坏"));
    }

    @Test
    void 部分规格成功_逐规格落状态与可读原因_其余规格不受累() throws Exception {
        // 临时塞进一个比例配置非法的坏规格：它必败，其余 4 个好规格照常成功
        Spec bad = new Spec();
        bad.setName("bad_ratio_spec");
        bad.setUsage("坏规格");
        bad.setChannel("feed");
        bad.setOrientation("LANDSCAPE");
        bad.setRatio("16"); // 非法比例，transcode 时抛可读异常
        bad.setLongEdge(100);
        List<Spec> origin = new ArrayList<>(properties.getSpecs());
        properties.getSpecs().add(bad);
        try {
            byte[] bytes = png(noise(1600, 900, 55));
            UploadResult result = assetService.upload(pngFile("partial.png", bytes), "tester", null, null, null);
            TranscodeJob job = awaitTerminal(result.getJob().getId());

            assertEquals(AssetEnums.JOB_PARTIAL_SUCCESS, job.getStatus());
            assertEquals(5, job.getTargetCount());
            assertEquals(4, job.getSuccessCount());
            assertEquals(1, job.getFailCount());
            assertNotNull(job.getFailReason());
            assertTrue(job.getFailReason().contains("bad_ratio_spec"));

            // 版本状态同样是部分成功
            AssetVersion version = assetService.listVersions(result.getAsset().getId()).get(0);
            assertEquals(AssetEnums.JOB_PARTIAL_SUCCESS, version.getTranscodeStatus());

            Map<String, TranscodeJobItem> items = itemMap(job.getId());
            TranscodeJobItem failedItem = items.get("bad_ratio_spec");
            assertEquals(AssetEnums.ITEM_FAILED, failedItem.getStatus());
            assertNotNull(failedItem.getFailReason());
            assertTrue(failedItem.getFailReason().contains("比例配置非法"));
            assertNull(failedItem.getFileId());
            // 好规格仍成功、有产物
            assertEquals(AssetEnums.ITEM_SUCCESS, items.get("feed_landscape").getStatus());
            assertEquals(AssetEnums.ITEM_SUCCESS, items.get("moments_portrait").getStatus());
            // 成功的 4 个产物照常落盘
            assertEquals(4, assetService.listFiles(job.getVersionId()).size());
        } finally {
            properties.getSpecs().clear();
            properties.getSpecs().addAll(origin);
        }
    }

    @Test
    void 后台转档全部失败原因落库可见_重新转档另起版本不抹失败历史() throws Exception {
        byte[] bytes = png(noise(1600, 900, 7));
        UploadResult result = assetService.upload(pngFile("ok.png", bytes), "tester", null, null, "feed");
        awaitTerminal(result.getJob().getId());
        Asset asset = result.getAsset();

        // 把落盘原图换成坏数据，模拟后台处理时原图读不出（v1 已成功不受影响）
        Path original = Paths.get(properties.getStorageRoot()).resolve(asset.getStoragePath());
        byte[] backup = Files.readAllBytes(original);
        Files.write(original, "broken-bytes".getBytes());

        TranscodeJob failedJob = assetService.retry(asset.getId(), "tester", "feed", null);
        TranscodeJob failed = awaitTerminal(failedJob.getId());
        assertEquals(AssetEnums.JOB_FAILED, failed.getStatus());
        assertEquals(2, failed.getTargetCount());
        assertEquals(2, failed.getFailCount());
        assertNotNull(failed.getFailReason());
        assertTrue(failed.getFailReason().contains("格式不支持") || failed.getFailReason().contains("损坏")
                || failed.getFailReason().contains("解码"));
        // 逐规格任务项都带原因
        for (TranscodeJobItem item : transcodeService.listItems(failed.getId())) {
            assertEquals(AssetEnums.ITEM_FAILED, item.getStatus());
            assertNotNull(item.getFailReason());
        }
        AssetVersion failedVersion = assetService.listVersions(asset.getId()).stream()
                .filter(v -> v.getId().equals(failed.getVersionId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(AssetEnums.JOB_FAILED, failedVersion.getTranscodeStatus());
        assertNotNull(failedVersion.getFailReason());

        // 原图恢复后只重跑信息流横版：新版本成功，失败历史版本仍在
        Files.write(original, backup);
        TranscodeJob okJob = assetService.retry(asset.getId(), "tester", null,
                java.util.Collections.singletonList("feed_landscape"));
        TranscodeJob ok = awaitTerminal(okJob.getId());
        assertEquals(AssetEnums.JOB_SUCCESS, ok.getStatus());
        assertEquals(1, ok.getSuccessCount());

        List<AssetVersion> versions = assetService.listVersions(asset.getId());
        assertEquals(3, versions.size());
        assertEquals(AssetEnums.JOB_SUCCESS, versions.get(0).getTranscodeStatus());
        assertEquals(AssetEnums.JOB_FAILED, versions.get(1).getTranscodeStatus());
        assertEquals(AssetEnums.JOB_SUCCESS, versions.get(2).getTranscodeStatus());
        // 最新版本指针指到成功版本
        assertEquals(ok.getVersionId(), assetService.requireById(asset.getId()).getLatestVersionId());
    }

    @Test
    void 任务明细可回看输入参数_产出版本_经手人() throws Exception {
        byte[] bytes = png(noise(1600, 900, 88));
        UploadResult result = assetService.upload(pngFile("review.png", bytes), "美工小张", "首轮投放", null, "feed");
        TranscodeJob job = awaitTerminal(result.getJob().getId());

        // 经手人落在任务和版本上
        assertEquals("美工小张", job.getOperatorName());
        AssetVersion version = assetService.listVersions(result.getAsset().getId()).get(0);
        assertEquals("美工小张", version.getOperatorName());
        assertEquals(job.getVersionId(), version.getId());

        // 输入参数快照可回看：来源/用途/渠道/选中规格/原图信息/质量档
        JobInputParams input = objectMapper.readValue(job.getInputParams(), JobInputParams.class);
        assertEquals("UPLOAD", input.getSource());
        assertEquals("FEED", input.getPurposeCode());
        assertEquals(java.util.Collections.singletonList("feed"), input.getChannels());
        assertTrue(input.getSpecs().contains("feed_landscape"));
        assertTrue(input.getSpecs().contains("feed_portrait"));
        assertEquals("review.png", input.getSourceFileName());
        assertEquals(1600, input.getSourceWidth());
        assertEquals(900, input.getSourceHeight());
        assertEquals(properties.getMaxFileSize().toBytes(), input.getMaxFileSizeBytes());
        assertEquals(properties.getQuality().getInitial(), input.getQualityInitial(), 0.0001);
        // 逐规格任务项可翻
        assertEquals(2, transcodeService.listItems(job.getId()).size());
    }

    @Test
    void 旧素材产物仍可按原版本下载_新版本不顶掉旧文件() throws Exception {
        byte[] bytes = png(noise(1600, 900, 99));
        UploadResult first = assetService.upload(pngFile("keep.png", bytes), "tester", null, null, "feed");
        TranscodeJob v1Job = awaitTerminal(first.getJob().getId());
        Long assetId = first.getAsset().getId();
        Long v1Id = v1Job.getVersionId();

        // 另起一版只跑朋友圈渠道
        TranscodeJob v2Job = assetService.retry(assetId, "tester", "moments", null);
        awaitTerminal(v2Job.getId());
        Long v2Id = v2Job.getVersionId();
        assertNotEquals(v1Id, v2Id);

        // v1 的信息流横版按原版本仍能精确取到并读出，不受 v2 影响
        AssetFile v1File = assetService.requireVersionFile(assetId, v1Id, "feed_landscape");
        byte[] v1Bytes = assetService.readProduct(v1File);
        assertTrue(v1Bytes.length > 0);
        assertEquals(v1Id, v1File.getVersionId());

        // v2 只有朋友圈规格，取不到信息流规格
        assertThrows(BusinessException.class,
                () -> assetService.requireVersionFile(assetId, v2Id, "feed_landscape"));
        AssetFile v2File = assetService.requireVersionFile(assetId, v2Id, "moments_landscape");
        assertTrue(assetService.readProduct(v2File).length > 0);

        // 版本不属于该素材要拦
        assertThrows(BusinessException.class,
                () -> assetService.requireVersionFile(assetId + 99999, v1Id, "feed_landscape"));
    }

    @Test
    void 不存在的渠道转档当场给可读原因() {
        byte[] bytes = png(noise(800, 800, 5));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.upload(pngFile("c.png", bytes), "tester", null, null, "no-such-channel"));
        assertTrue(ex.getMessage().contains("渠道"));
    }

    @Test
    void 底账版本登记包含f2与f12素材转档版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f2-asset-transcode-1"));
        assertTrue(versions.contains("f12-asset-transcode-2"));
    }
}
