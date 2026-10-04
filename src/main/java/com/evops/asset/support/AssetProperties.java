package com.evops.asset.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 素材多规格转档配置：多规格转换器（横版/竖版 × 投放渠道）、长边限定、体积上限与质量压缩档位。 */
@Data
@Component
@ConfigurationProperties(prefix = "asset")
public class AssetProperties {

    /** 原图与产物落盘根目录 */
    private String storageRoot = "./data/assets";

    /** 多规格转换器：按 横版/竖版 × 投放渠道 配置若干规格 */
    private List<Spec> specs = new ArrayList<>();

    /** 产物体积上限，超标再压一轮质量 */
    private DataSize maxFileSize = DataSize.ofKilobytes(300);

    private Quality quality = new Quality();

    /** 按投放渠道取规格；channel 为空取全部规格。渠道不存在给空表（由调用方决定是否报错）。 */
    public List<Spec> specsForChannel(String channel) {
        if (channel == null || channel.trim().isEmpty()) {
            return specs;
        }
        String want = channel.trim().toLowerCase();
        List<Spec> hit = new ArrayList<>();
        for (Spec spec : specs) {
            if (want.equals(spec.getChannel())) {
                hit.add(spec);
            }
        }
        return hit;
    }

    /** 按规格名取规格，找不到返回 null。 */
    public Spec specByName(String name) {
        if (name == null) {
            return null;
        }
        for (Spec spec : specs) {
            if (name.equals(spec.getName())) {
                return spec;
            }
        }
        return null;
    }

    /** 已配置的投放渠道（按首次出现顺序去重）。 */
    public List<String> channels() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Spec spec : specs) {
            seen.putIfAbsent(spec.getChannel(), Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }

    @Data
    public static class Spec {
        /** 规格标识，落库 usage code，如 feed_landscape */
        private String name;
        /** 用途说明（标清用途），如 信息流横版 */
        private String usage;
        /** 投放渠道，如 feed / wechat_moments / app_splash；不填默认 feed */
        private String channel = "feed";
        /** 横竖朝向：LANDSCAPE 横版 / PORTRAIT 竖版；不填按规格名兜底推断 */
        private String orientation;
        /** 目标比例 宽:高，如 16:9 */
        private String ratio;
        /** 长边限定像素 */
        private int longEdge;

        /** 解析 "16:9" 为 [宽, 高]。 */
        public int[] ratioParts() {
            String[] parts = ratio.split(":");
            if (parts.length != 2) {
                throw new IllegalStateException("规格比例配置非法: " + name + "=" + ratio);
            }
            return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
        }
    }

    @Data
    public static class Quality {
        /** 首轮 JPEG 质量 */
        private float initial = 0.85f;
        /** 质量下限：压到这档仍超标也不再降，按最好成绩交差 */
        private float floor = 0.5f;
        /** 每超一轮上限的质量降幅 */
        private float decrement = 0.15f;
    }
}
