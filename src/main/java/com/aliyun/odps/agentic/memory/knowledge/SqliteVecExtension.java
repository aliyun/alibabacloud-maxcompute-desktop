package com.aliyun.odps.agentic.memory.knowledge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

/**
 * sqlite-vec 扩展加载器——vec0 原生库按平台内置 resources，运行时解压到临时目录加载。
 *
 * <p>二进制来源：sqlite-vec v0.1.9 官方 release（MIT/Apache 双许可），资源路径
 * {@code sqlite-vec/<platform>/vec0.<ext>}。平台缺失时抛错，调用方（KnowledgeBaseService）
 * 降级为 FTS-only 检索（向量列留空），不炸构建。</p>
 */
public final class SqliteVecExtension {

    private SqliteVecExtension() {}

    /** 当前平台资源目录名；不支持的平台返回 null（调用方走降级）。 */
    public static String platformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("mac")) return aarch64 ? "macos-aarch64" : "macos-x86_64";
        if (os.contains("win")) return "windows-x86_64";
        if (os.contains("linux")) return aarch64 ? "linux-aarch64" : "linux-x86_64";
        return null;
    }

    /**
     * 把当前平台的 vec0 加载进给定 SQLite 连接。
     *
     * @throws IllegalStateException 平台不支持或资源缺失/加载失败
     */
    /** 仅测试:强制 vec 不可用(验证 FTS-only 降级路径,防「声明与实现不符」再犯)。 */
    public static volatile boolean forceUnavailableForTest = false;

    public static void load(Connection conn) {
        if (forceUnavailableForTest) {
            throw new IllegalStateException("vec0 forced unavailable for test");
        }
        String platform = platformDir();
        if (platform == null) {
            throw new IllegalStateException("sqlite-vec 不支持当前平台: " + System.getProperty("os.name"));
        }
        String ext = platform.startsWith("windows") ? "dll" : platform.startsWith("linux") ? "so" : "dylib";
        String resource = "/sqlite-vec/" + platform + "/vec0." + ext;
        try (InputStream in = SqliteVecExtension.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("sqlite-vec 资源缺失: " + resource + "（打包可能漏带该平台二进制）");
            }
            Path tmp = Files.createTempDirectory("mq-sqlite-vec").resolve("vec0." + ext);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT load_extension('" + tmp.toString().replace("'", "''") + "')");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("sqlite-vec 加载失败: " + e.getMessage(), e);
        }
    }
}
