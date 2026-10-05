package com.aliyun.odps.agentic.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 鉴权管线工具类，用于组合凭证解析与请求头注入逻辑。
 *
 * <p>该类型提供可组合的凭证来源、鉴权函数及其工厂方法，
 * 便于为不同提供者统一构建认证请求。
 */
public final class Auth {

    private Auth() {}

    // ── 错误 ────────────────────────────────────────────────────────────

    /**
     * 当凭证来源无法解析出有效值时抛出的异常。
     */
    public static final class MissingCredentialError extends RuntimeException {
        private final String source;

        public MissingCredentialError(String source) {
            super("Missing auth credential: " + source);
            this.source = source;
        }

        /**
         * 返回缺失凭证的来源名称。
         *
         * @return 来源名称
         */
        public String source() {
            return source;
        }
    }

    // ── 鉴权输入 ────────────────────────────────────────────────────────

    /**
     * 鉴权函数的输入参数。
     *
     * @param method  HTTP 方法
     * @param url     请求 URL
     * @param body    请求体字符串
     * @param headers 当前请求头，鉴权逻辑会在其基础上追加或覆盖
     */
    public record AuthInput(
        String method,
        String url,
        String body,
        Map<String, String> headers
    ) {
        /**
         * 返回一个仅替换请求头的新输入对象。
         *
         * @param newHeaders 新请求头
         * @return 新的鉴权输入对象
         */
        public AuthInput withHeaders(Map<String, String> newHeaders) {
            return new AuthInput(method, url, body, newHeaders);
        }
    }

    // ── 凭证 ───────────────────────────────────────────────────────

    /**
     * 凭证来源接口，用于加载密钥或令牌。
     */
    public interface Credential {

        /**
         * 加载凭证值；若不可用则抛出 {@link MissingCredentialError}。
         *
         * @return 凭证值
         */
        String load();

        /**
         * 优先使用当前凭证，失败时回退到另一个凭证来源。
         *
         * @param that 备用凭证来源
         * @return 组合后的凭证来源
         */
        default Credential orElse(Credential that) {
            var self = this;
            return () -> {
                try {
                    return self.load();
                } catch (MissingCredentialError e) {
                    return that.load();
                }
            };
        }

        /**
         * 创建一个使用 {@code Authorization: Bearer <secret>} 的鉴权函数。
         *
         * @return Bearer 鉴权函数
         */
        default AuthFn bearer() {
            return fromCredential(this, secret -> Map.of("authorization", "Bearer " + secret));
        }

        /**
         * 创建一个将凭证写入指定请求头的鉴权函数。
         *
         * @param name 请求头名称
         * @return 鉴权函数
         */
        default AuthFn header(String name) {
            return fromCredential(this, secret -> Map.of(name, secret));
        }
    }

    // ── 鉴权函数 ───────────────────────────────────────────────────────────

    /**
     * 可组合的鉴权函数。
     *
     * <p>它接收 {@link AuthInput} 并返回应用鉴权后的请求头集合。
     */
    @FunctionalInterface
    public interface AuthFn {

        /**
         * 应用鉴权逻辑并返回最终请求头。
         *
         * @param input 鉴权输入
         * @return 应用鉴权后的请求头
         */
        Map<String, String> apply(AuthInput input);

        /**
         * 按顺序组合两个鉴权函数，先执行当前函数，再执行下一个函数。
         *
         * @param that 后续鉴权函数
         * @return 组合后的鉴权函数
         */
        default AuthFn andThen(AuthFn that) {
            var self = this;
            return input -> {
                var headers = self.apply(input);
                return that.apply(input.withHeaders(headers));
            };
        }

        /**
         * 优先执行当前鉴权函数，发生凭证缺失时回退到另一个鉴权函数。
         *
         * @param that 备用鉴权函数
         * @return 组合后的鉴权函数
         */
        default AuthFn orElse(AuthFn that) {
            var self = this;
            return input -> {
                try {
                    return self.apply(input);
                } catch (MissingCredentialError e) {
                    return that.apply(input);
                }
            };
        }
    }

    // ── 内部辅助方法 ─────────────────────────────────────────────────

    /**
     * 通过凭证来源和请求头渲染函数创建鉴权函数。
     */
    private static AuthFn fromCredential(Credential source, Function<String, Map<String, String>> render) {
        return input -> {
            String secret = source.load();
            var result = new LinkedHashMap<>(input.headers());
            render.apply(secret).forEach(result::put);
            return Collections.unmodifiableMap(result);
        };
    }

    /**
     * 通过固定密钥字符串创建凭证来源。
     */
    private static Credential credentialFromSecret(String secret, String source) {
        return () -> {
            if (secret == null || secret.isEmpty()) {
                throw new MissingCredentialError(source);
            }
            return secret;
        };
    }

    /**
     * 将输入值规范化为 {@link Credential}。
     */
    private static Credential credentialInput(Object source) {
        if (source instanceof String s) return credentialFromSecret(s, "value");
        if (source instanceof Credential c) return c;
        throw new IllegalArgumentException("Expected String or Credential, got: " + source.getClass());
    }

    // ── 凭证工厂方法 ─────────────────────────────────────────────

    /**
     * 通过固定字符串值创建凭证来源。
     *
     * @param secret 密钥值
     * @return 凭证来源
     */
    public static Credential value(String secret) {
        return value(secret, "value");
    }

    /**
     * 通过固定字符串值创建带来源名称的凭证来源。
     *
     * @param secret 密钥值
     * @param source 来源名称
     * @return 凭证来源
     */
    public static Credential value(String secret, String source) {
        return credentialFromSecret(secret, source);
    }

    /**
     * 创建一个可为空的凭证来源；为空时会在加载时抛出异常。
     *
     * @param secret 密钥值
     * @return 凭证来源
     */
    public static Credential optional(String secret) {
        return optional(secret, "optional value");
    }

    /**
     * 创建一个带来源名称的可为空凭证来源。
     *
     * @param secret 密钥值
     * @param source 来源名称
     * @return 凭证来源
     */
    public static Credential optional(String secret, String source) {
        if (secret == null) {
            return () -> { throw new MissingCredentialError(source); };
        }
        return credentialFromSecret(secret, source);
    }

    /**
     * 从环境变量中加载凭证。
     *
     * @param envVarName 环境变量名
     * @return 凭证来源
     */
    public static Credential config(String envVarName) {
        return () -> {
            String val = System.getenv(envVarName);
            if (val == null || val.isEmpty()) {
                throw new MissingCredentialError(envVarName);
            }
            return val;
        };
    }

    /**
     * 直接返回已有的凭证来源。
     *
     * @param load 凭证来源
     * @return 原凭证来源
     */
    public static Credential effect(Credential load) {
        return load;
    }

    // ── 鉴权工厂方法 ───────────────────────────────────────────────────

    /**
     * 不做任何修改的鉴权函数。
     */
    public static final AuthFn none = input -> input.headers();

    /**
     * {@link #none} 的别名。
     */
    public static final AuthFn passthrough = none;

    /**
     * 创建一个附加静态请求头的鉴权函数。
     *
     * @param extra 额外请求头
     * @return 鉴权函数
     */
    public static AuthFn headers(Map<String, String> extra) {
        return input -> {
            var result = new LinkedHashMap<>(input.headers());
            result.putAll(extra);
            return Collections.unmodifiableMap(result);
        };
    }

    /**
     * 创建一个移除指定请求头的鉴权函数。
     *
     * @param name 请求头名称
     * @return 鉴权函数
     */
    public static AuthFn remove(String name) {
        return input -> {
            var result = new LinkedHashMap<>(input.headers());
            result.remove(name);
            return Collections.unmodifiableMap(result);
        };
    }

    /**
     * 直接使用自定义鉴权函数。
     *
     * @param apply 鉴权函数
     * @return 鉴权函数本身
     */
    public static AuthFn custom(AuthFn apply) {
        return apply;
    }

    /**
     * 根据密钥字符串创建 Bearer 鉴权函数。
     *
     * @param secret 密钥字符串
     * @return Bearer 鉴权函数
     */
    public static AuthFn bearer(String secret) {
        return credentialInput(secret).bearer();
    }

    /**
     * 根据凭证来源创建 Bearer 鉴权函数。
     *
     * @param credential 凭证来源
     * @return Bearer 鉴权函数
     */
    public static AuthFn bearer(Credential credential) {
        return credential.bearer();
    }

    /**
     * {@link #bearer(String)} 的别名。
     *
     * @param secret 密钥字符串
     * @return 鉴权函数
     */
    public static AuthFn apiKey(String secret) {
        return bearer(secret);
    }

    /**
     * {@link #bearer(Credential)} 的别名。
     *
     * @param credential 凭证来源
     * @return 鉴权函数
     */
    public static AuthFn apiKey(Credential credential) {
        return bearer(credential);
    }

    /**
     * 创建一个将密钥写入指定请求头的鉴权函数。
     *
     * @param name   请求头名称
     * @param secret 密钥字符串
     * @return 鉴权函数
     */
    public static AuthFn header(String name, String secret) {
        return credentialInput(secret).header(name);
    }

    /**
     * 创建一个将凭证来源写入指定请求头的鉴权函数。
     *
     * @param name       请求头名称
     * @param credential 凭证来源
     * @return 鉴权函数
     */
    public static AuthFn header(String name, Credential credential) {
        return credential.header(name);
    }

    /**
     * 创建一个在指定请求头中写入 Bearer 前缀密钥的鉴权函数。
     *
     * @param name   请求头名称
     * @param secret 密钥字符串
     * @return 鉴权函数
     */
    public static AuthFn bearerHeader(String name, String secret) {
        return fromCredential(credentialInput(secret), s -> Map.of(name, "Bearer " + s));
    }

    /**
     * 创建一个在指定请求头中写入 Bearer 前缀凭证的鉴权函数。
     *
     * @param name       请求头名称
     * @param credential 凭证来源
     * @return 鉴权函数
     */
    public static AuthFn bearerHeader(String name, Credential credential) {
        return fromCredential(credential, s -> Map.of(name, "Bearer " + s));
    }

    /**
     * 判断对象是否为 {@link AuthFn} 实例。
     *
     * @param input 待检查对象
     * @return 是鉴权函数时返回 {@code true}
     */
    public static boolean isAuth(Object input) {
        return input instanceof AuthFn;
    }
}
