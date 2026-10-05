package com.aliyun.odps.agentic.skill;

/**
 * 技能元数据 -- 描述一个可用技能。
 *
 * @param name        技能名称，作为唯一标识
 * @param description 技能描述，可为空
 * @param location    技能所在的文件路径或 URL
 * @param content     技能内容或指令文本
 */
public record SkillInfo(
    String name,
    String description,
    String location,
    String content
) {}
