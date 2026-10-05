package com.aliyun.odps.agentic.llm.provider;

import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.llm.Route;

/**
 * 提供者实例，表示已经配置好的提供者入口。
 *
 * @param id    提供者标识
 * @param route 已预配置完成的路由
 */
public record ProviderInstance(String id, Route route) {

    /**
     * 使用默认限制创建模型对象。
     *
     * @param modelId 模型标识
     * @return 模型对象
     */
    public Model model(String modelId) {
        return route.model(modelId, null);
    }

    /**
     * 使用指定限制创建模型对象。
     *
     * @param modelId 模型标识
     * @param limit   模型限制
     * @return 模型对象
     */
    public Model model(String modelId, ModelLimit limit) {
        return route.model(modelId, limit);
    }
}
