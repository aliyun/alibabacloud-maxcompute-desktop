package com.aliyun.odps.agentic.skill;

/**
 * 技能配置 -- 定义技能的来源位置。
 * 可通过本地路径或远程 URL 定位技能。
 *
 * @param path 本地技能文件路径，可为空
 * @param url  远程技能 URL，可为空
 */
public record SkillConfig(
    String path,
    String url
) {
    /**
     * 使用本地路径创建技能配置。
     *
     * @param path 本地路径
     */
    public SkillConfig(String path) {
        this(path, null);
    }

    /**
     * 从本地路径创建技能配置。
     *
     * @param path 本地路径
     * @return 技能配置
     */
    public static SkillConfig fromPath(String path) {
        return new SkillConfig(path, null);
    }

    /**
     * 从远程 URL 创建技能配置。
     *
     * @param url 远程 URL
     * @return 技能配置
     */
    public static SkillConfig fromUrl(String url) {
        return new SkillConfig(null, url);
    }
}
