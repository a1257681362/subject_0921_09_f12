package com.evops.asset.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/** 素材多规格转档配置：投放渠道、常规规格、长边限定、体积上限与质量压缩档位。 */
@Data
@Component
@ConfigurationProperties(prefix = "asset")
public class AssetProperties {

    /** 原图与产物落盘根目录 */
    private String storageRoot = "./data/assets";

    /** 投放渠道（素材用途）：上传按用途分档，同图不同用途各开各的档 */
    private List<Channel> channels = new ArrayList<>();

    /** 常规规格：挂在投放渠道下，横竖两版 */
    private List<Spec> specs = new ArrayList<>();

    /** 产物体积上限，超标再压一轮质量 */
    private DataSize maxFileSize = DataSize.ofKilobytes(300);

    private Quality quality = new Quality();

    @Data
    public static class Channel {
        /** 渠道/用途标识，落库 purpose，如 FEED */
        private String code;
        /** 渠道说明（标清用途），如 信息流投放 */
        private String name;
    }

    @Data
    public static class Spec {
        /** 规格标识，落库 usage code，如 feed_landscape */
        private String name;
        /** 用途说明（标清用途），如 信息流横版 */
        private String usage;
        /** 所属投放渠道（缺省取第一个渠道） */
        private String channel;
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

        /** 横竖朝向按比例推导：宽>高横版、宽<高竖版、相等方版。 */
        public String orientation() {
            int[] parts = ratioParts();
            if (parts[0] > parts[1]) {
                return "LANDSCAPE";
            }
            if (parts[0] < parts[1]) {
                return "PORTRAIT";
            }
            return "SQUARE";
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

    /** 缺省用途：上传未指定时取配置的第一个渠道。 */
    public String defaultPurpose() {
        return channels.isEmpty() ? null : channels.get(0).getCode();
    }

    /** 渠道是否存在。 */
    public boolean hasChannel(String code) {
        return code != null && channels.stream().anyMatch(c -> code.equals(c.getCode()));
    }

    /** 渠道中文名，查不到原样返回。 */
    public String channelName(String code) {
        return channels.stream().filter(c -> c.getCode().equals(code))
                .map(Channel::getName).findFirst().orElse(code);
    }

    /** 渠道说明拼接，用于报错提示（如 "FEED（信息流投放）、SPLASH（开屏投放）"）。 */
    public String channelOptionsText() {
        StringBuilder sb = new StringBuilder();
        for (Channel c : channels) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(c.getCode()).append("（").append(c.getName()).append("）");
        }
        return sb.toString();
    }

    /** 某渠道要跑的规格清单；规格没配渠道时归到缺省渠道。 */
    public List<Spec> specsForChannel(String channelCode) {
        List<Spec> result = new ArrayList<>();
        for (Spec spec : specs) {
            String owner = spec.getChannel() == null || spec.getChannel().trim().isEmpty()
                    ? defaultPurpose() : spec.getChannel();
            if (owner != null && owner.equals(channelCode)) {
                result.add(spec);
            }
        }
        return result;
    }
}
