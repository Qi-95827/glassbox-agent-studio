package dev.workbench.modelconnection;

/**
 * 一次模型调用的运行时连接视图。
 *
 * <p>明文密钥只在构造 AgentScope 模型时短暂存在于内存，不写日志、不进入 Trace、不持久化。
 */
public record RuntimeModelConnection(
    String id,
    String displayName,
    ModelProviderType providerType,
    String baseUrl,
    String modelName,
    String apiKey,
    Double inputPricePer1m,
    Double outputPricePer1m,
    String currency) {}
