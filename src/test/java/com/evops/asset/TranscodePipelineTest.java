package com.evops.asset;

import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.entity.TranscodeJobItem;
import com.evops.asset.service.AssetService;
import com.evops.asset.service.TranscodeService;
import com.evops.asset.support.AssetProperties;
import com.evops.common.BusinessException;
import com.evops.ledger.service.SchemaVersionService;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F12 转档任务生产化管理端到端测试（真实 H2 + 真实异步线程 + 真实 ImageIO 转档落盘）。 */
@SpringBootTest
class TranscodePipelineTest {

    @Autowired private AssetService assetService;
    @Autowired private TranscodeService transcodeService;
    @Autowired private AssetProperties properties;
    @Autowired private SchemaVersionService schemaVersionService;

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

    private byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 逐像素随机噪声：JPEG 最难压的高熵图，用来逼出多轮质量压缩。 */
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
    void 文件指纹认同源_同用途才合并_不同用途各开各的档() throws Exception {
        byte[] bytes = png(noise(1600, 1200, 101));

        UploadResult feed = assetService.upload(pngFile("kv.png", bytes), "FEED", "tester", null);
        assertFalse(feed.isDeduped());
        awaitTerminal(feed.getJob().getId());

        // 同一张图投另一个用途：指纹一致但用途不同，必须另开一档，不能误合并
        UploadResult splash = assetService.upload(pngFile("kv-改名.png", bytes), "SPLASH", "tester", null);
        assertFalse(splash.isDeduped());
        awaitTerminal(splash.getJob().getId());
        assertNotEquals(feed.getAsset().getId(), splash.getAsset().getId());
        assertNotEquals(feed.getAsset().getAssetNo(), splash.getAsset().getAssetNo());

        // 两份档案指纹一致（同源可认）、用途各异；原图按指纹共享同一份文件
        Asset feedAsset = assetService.requireById(feed.getAsset().getId());
        Asset splashAsset = assetService.requireById(splash.getAsset().getId());
        assertEquals(feedAsset.getFingerprintSha256(), splashAsset.getFingerprintSha256());
        assertEquals("FEED", feedAsset.getPurpose());
        assertEquals("SPLASH", splashAsset.getPurpose());
        assertEquals(feedAsset.getStoragePath(), splashAsset.getStoragePath());

        // 同图同用途再传：按指纹+用途认出来，只累加次数，不另开档、不重跑转档
        UploadResult again = assetService.upload(pngFile("kv-再传.png", bytes), "FEED", "tester", null);
        assertTrue(again.isDeduped());
        assertEquals(feedAsset.getId(), again.getAsset().getId());
        assertEquals(2, assetService.requireById(feedAsset.getId()).getUploadCount());
        assertEquals(1, assetService.listVersions(feedAsset.getId()).size());
        assertEquals(1, assetService.listVersions(splashAsset.getId()).size());

        // 按用途翻查各自只见到自己的档
        List<Asset> feedRows = assetService.search("kv", null, "FEED");
        assertTrue(feedRows.stream().anyMatch(a -> a.getId().equals(feedAsset.getId())));
        assertTrue(feedRows.stream().noneMatch(a -> a.getId().equals(splashAsset.getId())));
    }

    @Test
    void 产物按投放渠道与横竖两版保存() throws Exception {
        byte[] bytes = png(noise(2000, 1000, 102));
        UploadResult result = assetService.upload(pngFile("splash-kv.png", bytes), "SPLASH", "tester", null);
        TranscodeJob job = awaitTerminal(result.getJob().getId());

        assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());
        assertEquals(2, job.getTotalCount());
        assertEquals(2, job.getSuccessCount());

        Map<String, AssetFile> files = assetService.listFiles(job.getVersionId()).stream()
                .collect(Collectors.toMap(AssetFile::getUsage, f -> f));
        AssetFile landscape = files.get("splash_landscape");
        AssetFile portrait = files.get("splash_portrait");
        assertNotNull(landscape);
        assertNotNull(portrait);
        // 渠道与横竖朝向随产物落库
        assertEquals("SPLASH", landscape.getChannel());
        assertEquals("SPLASH", portrait.getChannel());
        assertEquals("LANDSCAPE", landscape.getOrientation());
        assertEquals("PORTRAIT", portrait.getOrientation());
        // 开屏竖版长边压进 1920，出 1080x1920 标准件
        assertEquals(1080, portrait.getWidth());
        assertEquals(1920, portrait.getHeight());
        assertEquals(1280, landscape.getWidth());
        assertEquals(720, landscape.getHeight());
        // 产物按 素材/版本/渠道/规格 落盘
        assertTrue(landscape.getStoragePath().contains("/SPLASH/"), landscape.getStoragePath());
        assertTrue(assetService.readProduct(portrait).length > 0);
    }

    @Test
    void 单个规格失败不拖垮整单_任务落部分成功_明细各自有状态() throws Exception {
        // 临时往信息流渠道塞一个比例配置非法的规格：该规格必败，其余两版正常出
        AssetProperties.Spec bad = new AssetProperties.Spec();
        bad.setName("feed_bad_ratio");
        bad.setUsage("信息流坏规格");
        bad.setChannel("FEED");
        bad.setRatio("16:9:5");
        bad.setLongEdge(1280);
        properties.getSpecs().add(bad);
        try {
            byte[] bytes = png(noise(1600, 900, 103));
            UploadResult result = assetService.upload(pngFile("partial.png", bytes), "FEED", "tester", null);
            TranscodeJob job = awaitTerminal(result.getJob().getId());

            // 部分成功：3 个规格成了 2 个，任务不算全败
            assertEquals(AssetEnums.JOB_PARTIAL, job.getStatus());
            assertEquals(3, job.getTotalCount());
            assertEquals(2, job.getSuccessCount());
            assertNotNull(job.getFailReason());
            assertTrue(job.getFailReason().contains("feed_bad_ratio"), job.getFailReason());

            // 逐规格明细：两成一败，失败行带可读原因
            List<TranscodeJobItem> items = transcodeService.listItems(job.getId());
            assertEquals(3, items.size());
            Map<String, TranscodeJobItem> bySpec = items.stream()
                    .collect(Collectors.toMap(TranscodeJobItem::getSpecName, i -> i));
            assertEquals(AssetEnums.JOB_SUCCESS, bySpec.get("feed_landscape").getStatus());
            assertEquals(AssetEnums.JOB_SUCCESS, bySpec.get("feed_portrait").getStatus());
            TranscodeJobItem badItem = bySpec.get("feed_bad_ratio");
            assertEquals(AssetEnums.JOB_FAILED, badItem.getStatus());
            assertTrue(badItem.getFailReason().contains("规格比例配置非法"), badItem.getFailReason());
            assertNull(badItem.getFileId());
            // 成功的明细行回填了产物与质量信息
            assertNotNull(bySpec.get("feed_landscape").getFileId());
            assertNotNull(bySpec.get("feed_landscape").getQualityState());

            // 版本同样落部分成功，成功规格的产物已入档；最新版本指针指到这版（有产出）
            AssetVersion version = assetService.listVersions(result.getAsset().getId()).get(0);
            assertEquals(AssetEnums.JOB_PARTIAL, version.getTranscodeStatus());
            assertNotNull(version.getFailReason());
            assertEquals(2, assetService.listFiles(version.getId()).size());
            assertEquals(version.getId(), assetService.requireById(result.getAsset().getId()).getLatestVersionId());
        } finally {
            properties.getSpecs().remove(bad);
        }
    }

    @Test
    void 质量压缩状态_压到下限仍超标也交差并标出来() throws Exception {
        org.springframework.util.unit.DataSize originLimit = properties.getMaxFileSize();
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofBytes(1));
        try {
            byte[] bytes = png(noise(1280, 720, 104));
            UploadResult result = assetService.upload(pngFile("over-limit.png", bytes), "FEED", "tester", null);
            TranscodeJob job = awaitTerminal(result.getJob().getId());
            // 压到下限仍超标不算失败：按体积最小一轮交差，任务整体成功
            assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());

            List<TranscodeJobItem> items = transcodeService.listItems(job.getId());
            assertEquals(2, items.size());
            for (TranscodeJobItem item : items) {
                assertEquals(AssetEnums.QS_OVER_LIMIT, item.getQualityState());
                assertTrue(item.getQualityRound() >= 2, "上限 1 字节必须多轮压缩");
                assertEquals(properties.getQuality().getFloor(), item.getFinalQuality(), 0.001);
            }
            // 产物行同样带着质量压缩状态
            for (AssetFile file : assetService.listFiles(job.getVersionId())) {
                assertEquals(AssetEnums.QS_OVER_LIMIT, file.getQualityState());
            }
        } finally {
            properties.setMaxFileSize(originLimit);
        }
    }

    @Test
    void 任务明细可回看输入参数_产出版本与操作人() throws Exception {
        byte[] bytes = png(noise(1600, 900, 105));
        UploadResult result = assetService.upload(pngFile("review.png", bytes), "FEED", "美工小赵", "618 主会场");
        TranscodeJob job = awaitTerminal(result.getJob().getId());
        assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());

        // 操作人随任务落库
        TranscodeJob reloaded = transcodeService.requireJob(job.getId());
        assertEquals("美工小赵", reloaded.getOperatorName());

        // 输入参数快照：用途、各规格比例/长边、体积上限、质量档，提交时冻结
        String snapshot = reloaded.getSpecsSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("\"purpose\":\"FEED\""), snapshot);
        assertTrue(snapshot.contains("feed_landscape"), snapshot);
        assertTrue(snapshot.contains("\"ratio\":\"16:9\""), snapshot);
        assertTrue(snapshot.contains("\"longEdge\":1280"), snapshot);
        assertTrue(snapshot.contains("maxFileSizeBytes"), snapshot);
        assertTrue(snapshot.contains("\"initial\":0.85"), snapshot);

        // 逐规格明细就是可回看的输入参数 + 各自状态
        List<TranscodeJobItem> items = transcodeService.listItems(job.getId());
        assertEquals(2, items.size());
        Map<String, TranscodeJobItem> bySpec = items.stream()
                .collect(Collectors.toMap(TranscodeJobItem::getSpecName, i -> i));
        assertEquals("16:9", bySpec.get("feed_landscape").getRatio());
        assertEquals(1280, bySpec.get("feed_landscape").getLongEdge());
        assertEquals("LANDSCAPE", bySpec.get("feed_landscape").getOrientation());
        assertEquals("FEED", bySpec.get("feed_landscape").getChannel());
        assertEquals("9:16", bySpec.get("feed_portrait").getRatio());
        assertEquals("PORTRAIT", bySpec.get("feed_portrait").getOrientation());

        // 产出版本：任务 → 版本 → 产物一串可翻
        AssetVersion version = assetService.listVersions(result.getAsset().getId()).stream()
                .filter(v -> v.getId().equals(job.getVersionId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1, version.getVersionNo());
        assertEquals(2, assetService.listFiles(version.getId()).size());
    }

    @Test
    void 失败重试各有状态_重试链可翻_全任务失败逐明细落原因() throws Exception {
        byte[] bytes = png(noise(1600, 900, 106));
        UploadResult result = assetService.upload(pngFile("retry-chain.png", bytes), "FEED", "tester", null);
        TranscodeJob first = awaitTerminal(result.getJob().getId());
        assertEquals(AssetEnums.JOB_SUCCESS, first.getStatus());
        Asset asset = result.getAsset();

        // 把落盘原图换成坏数据再重跑：全任务失败，逐明细同落原因
        Path original = Paths.get(properties.getStorageRoot()).resolve(asset.getStoragePath());
        byte[] backup = Files.readAllBytes(original);
        Files.write(original, "broken-bytes".getBytes());

        TranscodeJob failed = awaitTerminal(assetService.retry(asset.getId(), "运营小李").getId());
        assertEquals(AssetEnums.JOB_FAILED, failed.getStatus());
        assertNotNull(failed.getFailReason());
        // 失败重试链：重试任务挂来源任务
        assertEquals(first.getId(), failed.getRetryOfJobId());
        assertEquals("运营小李", failed.getOperatorName());
        // 全任务失败时逐规格明细都是 FAILED 且带原因
        List<TranscodeJobItem> failedItems = transcodeService.listItems(failed.getId());
        assertEquals(2, failedItems.size());
        for (TranscodeJobItem item : failedItems) {
            assertEquals(AssetEnums.JOB_FAILED, item.getStatus());
            assertNotNull(item.getFailReason());
        }

        // 原图恢复后再重跑：成功，重试链再挂一环
        Files.write(original, backup);
        TranscodeJob recovered = awaitTerminal(assetService.retry(asset.getId(), "tester").getId());
        assertEquals(AssetEnums.JOB_SUCCESS, recovered.getStatus());
        assertEquals(failed.getId(), recovered.getRetryOfJobId());

        // 三个版本各就各位：成功 / 失败 / 成功，历史不抹
        List<AssetVersion> versions = assetService.listVersions(asset.getId());
        assertEquals(3, versions.size());
        assertEquals(AssetEnums.JOB_SUCCESS, versions.get(0).getTranscodeStatus());
        assertEquals(AssetEnums.JOB_FAILED, versions.get(1).getTranscodeStatus());
        assertEquals(AssetEnums.JOB_SUCCESS, versions.get(2).getTranscodeStatus());
        assertEquals("RETRY", versions.get(1).getSource());
    }

    @Test
    void 重新转档后旧版本产物仍按原版本下载() throws Exception {
        byte[] bytes = png(noise(1600, 900, 107));
        UploadResult result = assetService.upload(pngFile("versioned.png", bytes), "FEED", "tester", null);
        TranscodeJob first = awaitTerminal(result.getJob().getId());
        assertEquals(AssetEnums.JOB_SUCCESS, first.getStatus());
        Long assetId = result.getAsset().getId();

        // v1 产物先记住
        List<AssetFile> v1Files = assetService.listFiles(first.getVersionId());
        assertEquals(2, v1Files.size());
        AssetFile v1Landscape = v1Files.stream()
                .filter(f -> "feed_landscape".equals(f.getUsage())).findFirst().orElseThrow(AssertionError::new);
        long v1Size = v1Landscape.getFileSize();

        // 再跑一轮出 v2，最新指针跟到 v2
        TranscodeJob second = awaitTerminal(assetService.retry(assetId, "tester").getId());
        assertEquals(AssetEnums.JOB_SUCCESS, second.getStatus());
        assertEquals(second.getVersionId(), assetService.requireById(assetId).getLatestVersionId());

        // v1 产物行还在、字节还能按原版本读回，不被新版抹掉
        List<AssetFile> v1After = assetService.listFiles(first.getVersionId());
        assertEquals(2, v1After.size());
        byte[] v1Bytes = assetService.readProduct(v1Landscape);
        assertEquals(v1Size, v1Bytes.length);
        // v2 是独立版本目录，产物各自可下载
        assertEquals(2, assetService.listFiles(second.getVersionId()).size());
        assertTrue(assetService.readProduct(v1Landscape).length > 0);
    }

    @Test
    void 不支持的投放用途给出可读原因() {
        byte[] bytes = png(noise(800, 600, 108));
        MockMultipartFile file = pngFile("wrong-purpose.png", bytes);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.upload(file, "TV", "tester", null));
        assertTrue(ex.getMessage().contains("投放用途不支持"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FEED"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SPLASH"), ex.getMessage());
    }

    @Test
    void 底账版本登记包含f12转档生产化版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f12-transcode-pipeline-1"));
    }
}
