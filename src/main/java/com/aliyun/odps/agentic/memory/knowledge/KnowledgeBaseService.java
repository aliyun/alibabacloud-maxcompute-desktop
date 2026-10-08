package com.aliyun.odps.agentic.memory.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地知识库服务——设计文档 docs/design/memory-knowledge-base-design.md(v1.7)的实现。
 *
 * <p>目录即注册表：{@code ~/.maxquery/workspace/knowledge/<kbId>/} 内含
 * manifest.json（契约+状态）+ index.db（kb_document/kb_chunk/kb_chunk_vec/kb_chunk_fts
 * + knowledge_topic/topic_chunk）。向量与内容同库事务（vec0 行 rowid 对齐 chunk id）。</p>
 *
 * <p>生命周期：CREATING→BUILDING→READY（构建失败 FAILED；源目录缺失 DEGRADED；
 * 删除 DELETING→取消任务→关连接→删目录）。全量重建走世代化（index-N.building.db→
 * 校验→原子换名）；增量刷新原地 per-file 事务。</p>
 */
public class KnowledgeBaseService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int KNN_K = 20;
    /** 余弦距离上限(0=完全相同 1=正交 2=相反):实测强命中 ≈0.26、弱命中 0.57–0.63(POC/E2E),
        0.6 截断防「永远有结果」式误召回——没有就是没有,别把无关内容喂给模型。 */
    public static final double MAX_COSINE_DISTANCE = 0.6;
    private static final int RRF_K = 60;

    private final Path knowledgeRoot;
    private final KnowledgeRetrievalProvider retrievalProvider;
    private final com.aliyun.odps.agentic.config.AgentAdvancedSettings advancedConfig;
    private final DocumentTextExtractor extractor;
    private final Chunker chunker;
    private final KnowledgePrivacyGuard privacyGuard;
    /** 检索距离上限(测试可放宽——假向量距离语义与真模型不同档) */
    private double maxDistance = MAX_COSINE_DISTANCE;

    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kb-build");
        t.setDaemon(true);
        return t;
    });
    /** vec0 加载失败(平台二进制缺失)的库路径——这些库退化为 FTS-only(评审 Major-3)。 */
    private final Set<Path> vecUnavailable = ConcurrentHashMap.newKeySet();
    /** kbId → 构建任务（取消用）。 */
    private final Map<String, Future<?>> buildingTasks = new ConcurrentHashMap<>();
    /** kbId → 打开中的 SQLite 连接（删除前须关闭，Windows 硬要求）。 */
    private final Map<String, Connection> openConnections = new ConcurrentHashMap<>();

    public KnowledgeBaseService(String dataDir,
                                KnowledgeRetrievalProvider retrievalProvider,
                                com.aliyun.odps.agentic.config.AgentAdvancedSettings advancedConfig,
                                DocumentTextExtractor extractor,
                                Chunker chunker,
                                KnowledgePrivacyGuard privacyGuard) {
        this.knowledgeRoot = Paths.get(dataDir, "workspace", "knowledge");
        this.retrievalProvider = retrievalProvider;
        this.advancedConfig = advancedConfig;
        this.extractor = extractor;
        this.chunker = chunker;
        this.privacyGuard = privacyGuard;
    }

    /** 仅测试:放宽距离上限(假向量的距离分布与真模型不同档,截断会误杀机制测试)。 */
    public void setMaxDistanceForTest(double d) { this.maxDistance = d; }

    public void shutdown() {
        buildingTasks.values().forEach(f -> f.cancel(true));
        openConnections.values().forEach(this::closeQuietly);
        buildExecutor.shutdownNow();
        ioProbeExecutor.shutdownNow();
    }

    // ==================== 注册表（目录扫描） ====================

    public List<Map<String, Object>> listBases() {
        if (!Files.isDirectory(knowledgeRoot)) return List.of();
        try (Stream<Path> dirs = Files.list(knowledgeRoot)) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> readManifest(d.getFileName().toString()))
                    .filter(Objects::nonNull)
                    .sorted((a, b) -> Long.compare(longOf(b, "createdAt"), longOf(a, "createdAt")))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[KB] list bases failed: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> getBase(String kbId) {
        Map<String, Object> m = readManifest(kbId);
        if (m == null) throw new NoSuchElementException("知识库不存在: " + kbId);
        return m;
    }

    // ==================== 创建 + 构建 ====================

    public Map<String, Object> createBase(String folderPath, String name,
                                          String embeddingModel, Integer dimension,
                                          Set<String> fileExts, String schedule) {
        return createBase(folderPath, name, embeddingModel, dimension, fileExts, schedule, null);
    }

    public Map<String, Object> createBase(String folderPath, String name,
                                                       String embeddingModel, Integer dimension,
                                                       Set<String> fileExts, String schedule,
                                                       Boolean rerankEnabled) {
        // R61-P1:慢 IO(网络盘/TCC 挂载点的 toRealPath 可挂起数十分钟)必须移出全局锁,
        // 否则一次建库卡死连锁排队所有 KB 操作(jstack 实证)。锁内只剩 id 分配+manifest 落盘。
        Path folder = validateKbFolder(Paths.get(folderPath));

        String model = (embeddingModel != null && com.aliyun.odps.agentic.config.AgentAdvancedSettings.EMBEDDING_MODELS.contains(embeddingModel))
                ? embeddingModel
                : (advancedConfig != null ? advancedConfig.getEmbeddingModel() : com.aliyun.odps.agentic.config.AgentAdvancedSettings.DEFAULT_EMBEDDING_MODEL);
        int dim = (dimension != null && dimension > 0) ? dimension : 1024;
        String baseName = (name == null || name.isBlank()) ? folder.getFileName().toString() : name.trim();

        String kbId;
        Path kbDir;
        synchronized (this) {
            kbId = newKbId(baseName);
            kbDir = knowledgeRoot.resolve(kbId);
            try {
                Files.createDirectories(kbDir);
            } catch (IOException e) {
                throw new IllegalStateException("创建知识库目录失败: " + e.getMessage(), e);
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kbId", kbId);
        manifest.put("name", baseName);
        manifest.put("folderPath", folder.toAbsolutePath().toString());
        manifest.put("embeddingModel", model);
        manifest.put("dimension", dim);
        manifest.put("rerankModel", advancedConfig != null ? advancedConfig.getRerankModel() : com.aliyun.odps.agentic.config.AgentAdvancedSettings.DEFAULT_RERANK_MODEL);
        manifest.put("rerankEnabled", rerankEnabled == null || rerankEnabled);
        manifest.put("fileExts", fileExts == null || fileExts.isEmpty()
                ? new ArrayList<>(DocumentTextExtractor.SUPPORTED_EXTS) : new ArrayList<>(fileExts));
        manifest.put("schedule", schedule == null ? "off" : schedule); // off|daily|weekly|monthly
        manifest.put("status", "CREATING");
        manifest.put("distanceMetric", "cosine");
        manifest.put("documentTextType", "document");
        manifest.put("queryTextType", "query");
        manifest.put("createdAt", System.currentTimeMillis());
        manifest.put("lastRefreshAt", 0L);
        manifest.put("activeGeneration", 1);
        manifest.put("progress", Map.of("total", 0, "done", 0, "failed", 0));
        manifest.put("stats", Map.of("documents", 0, "chunks", 0, "tokensEmbedded", 0));
        writeManifest(kbId, manifest);

        startBuild(kbId, false);
        return manifest;
    }

    /** 建库文件夹校验：存在性 + 敏感路径,全程锁外且 5s 超时兜底(R61-P1:
        网络盘/挂起挂载点上 toRealPath 可无限阻塞;超时按不可达拒绝,不拖死服务)。 */
    private Path validateKbFolder(Path folder) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (!Files.isDirectory(folder)) {
                    throw new IllegalArgumentException("文件夹不存在: " + folder);
                }
                if (privacyGuard.isSensitivePath(folder.toString())) {
                    throw new IllegalArgumentException("敏感目录不可建库: " + folder);
                }
                return folder;
            }, ioProbeExecutor).get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalArgumentException("文件夹路径探测超时(网络盘/挂载点不可达?): " + folder);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalStateException("文件夹校验失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件夹校验被中断", e);
        }
    }

    /** 建库路径探测(带超时)专用守护池——与构建池隔离,慢挂载不占构建位。 */
    private final ExecutorService ioProbeExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kb-io-probe");
        t.setDaemon(true);
        return t;
    });

    /** 启动构建（full=false 增量）。已在构建中则跳过（不自叠加）。 */
    /** 单文件重试(评审 P0):失败文档行级自助修复,不用整库刷新。 */
    public synchronized Map<String, Object> retryDocument(String kbId, String relPath) {
        // 与构建/刷新互斥(EBUSY 实证:双写同库必撞):构建中拒绝,刷新完成会自动重试失败文档
        if (buildingTasks.containsKey(kbId)) {
            throw new IllegalStateException("该知识空间正在同步中,完成后再试(失败来源会被自动重试)");
        }
        Map<String, Object> manifest = getBase(kbId);
        Path folder = Paths.get((String) manifest.get("folderPath"));
        Path file = folder.resolve(relPath).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在于该知识空间: " + relPath);
        }
        var cap = retrievalProvider.capability();
        if (!cap.available()) throw new IllegalStateException(cap.reason());
        DashScopeRetrievalClient client = retrievalProvider.client();
        String model = (String) manifest.get("embeddingModel");
        int dim = ((Number) manifest.get("dimension")).intValue();
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        try (Connection conn = openDb(dbPath, dim, true)) {
            openConnections.put(kbId, conn);
            try {
                indexOneFile(conn, client, folder, file, relPath, model, dim, false, new AtomicInteger(0),
                        !vecUnavailable.contains(dbPath)); // 返回值重试场景不消费
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("重试被中断", ie);
            } catch (Exception e) {
                throw new IllegalStateException("重试失败: " + e.getMessage(), e);
            }
            rebuildTopics(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("重试失败: " + e.getMessage(), e);
        } finally {
            openConnections.remove(kbId);
        }
        Map<String, Object> m = getBase(kbId);
        m.put("lastRefreshAt", System.currentTimeMillis());
        writeManifest(kbId, m);
        return m;
    }

    /** 更新可编辑设置（schedule/rerankEnabled)——模型/维度不可改(改=全量重建,走 refresh?full=true)。 */
    public synchronized Map<String, Object> updateSettings(String kbId, String schedule, Boolean rerankEnabled) {
        Map<String, Object> m = getBase(kbId);
        if (schedule != null) {
            if (!Set.of("off", "daily", "weekly", "monthly").contains(schedule)) {
                throw new IllegalArgumentException("非法 schedule: " + schedule);
            }
            m.put("schedule", schedule);
            m.put("scheduleFailures", 0); // 改设置=人工介入,失败计数清零
        }
        if (rerankEnabled != null) m.put("rerankEnabled", rerankEnabled);
        writeManifest(kbId, m);
        return m;
    }

    public synchronized void startBuild(String kbId, boolean fullRebuild) {
        if (buildingTasks.containsKey(kbId)) {
            log.info("[KB] build already running, skip: {}", kbId);
            return;
        }
        Map<String, Object> manifest = getBase(kbId);
        if ("DELETING".equals(manifest.get("status"))) return;
        manifest.put("status", "BUILDING");
        writeManifest(kbId, manifest);
        Future<?> f = buildExecutor.submit(() -> buildSafely(kbId, fullRebuild));
        buildingTasks.put(kbId, f);
    }

    public synchronized boolean cancelBuild(String kbId) {
        Future<?> f = buildingTasks.get(kbId);
        if (f == null) return false;
        boolean cancelled = f.cancel(true);
        buildingTasks.remove(kbId);
        Map<String, Object> m = readManifest(kbId);
        if (m != null) {
            m.put("status", "PAUSED");
            writeManifest(kbId, m);
        }
        return cancelled;
    }

    private void buildSafely(String kbId, boolean fullRebuild) {
        try {
            build(kbId, fullRebuild);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateStatus(kbId, "PAUSED", null);
        } catch (Exception e) {
            log.error("[KB] build failed: {}", kbId, e);
            updateStatus(kbId, "FAILED", e.getMessage());
            // 定时库连错 3 次自停(评审 Major-4 裁定):防坏库每周期白烧 embedding 配额
            Map<String, Object> m = readManifest(kbId);
            if (m != null && !"off".equals(m.get("schedule"))) {
                int fails = (m.get("scheduleFailures") instanceof Number n ? n.intValue() : 0) + 1;
                m.put("scheduleFailures", fails);
                if (fails >= 3) {
                    m.put("schedule", "paused");
                    log.warn("[KB] 定时刷新连续失败 {} 次,自动暂停: {}", fails, kbId);
                }
                writeManifest(kbId, m);
            }
        } finally {
            buildingTasks.remove(kbId);
        }
    }

    @SuppressWarnings("unchecked")
    private void build(String kbId, boolean fullRebuild) throws Exception {
        long buildStart = System.currentTimeMillis();
        Map<String, Object> manifest = getBase(kbId);
        Path kbDir = knowledgeRoot.resolve(kbId);
        Path folder = Paths.get((String) manifest.get("folderPath"));
        if (!Files.isDirectory(folder)) {
            updateStatus(kbId, "DEGRADED", "源文件夹不存在: " + folder);
            return;
        }

        KnowledgeRetrievalProvider.Capability cap = retrievalProvider.capability();
        if (!cap.available()) {
            updateStatus(kbId, "FAILED", cap.reason());
            return;
        }
        DashScopeRetrievalClient client = retrievalProvider.client();

        String model = (String) manifest.get("embeddingModel");
        int dim = ((Number) manifest.get("dimension")).intValue();
        Set<String> exts = new HashSet<>((List<String>) manifest.get("fileExts"));

        // 世代化：全量重建建新库,增量复用现役库
        int generation = ((Number) manifest.getOrDefault("activeGeneration", 1)).intValue();
        int buildGeneration = fullRebuild ? generation + 1 : generation;
        Path dbPath = kbDir.resolve(fullRebuild ? "index-" + buildGeneration + ".building.db" : "index.db");
        // 评审 Major-2:上次构建崩溃可能残留同代 .building.db(CREATE IF NOT EXISTS 会混入旧代内容)——建前清残留
        if (fullRebuild) {
            try (Stream<Path> stale = Files.list(kbDir)) {
                for (Path p : stale.filter(x -> x.getFileName().toString().matches("index-.*\\.building\\.db.*")).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }

        List<String> ignoreRules = privacyGuard.readKnowledgeIgnore(folder);
        List<Path> files = new ArrayList<>();
        // walkFileTree + 目录剪枝(2026-10-07):.开头目录/构建依赖目录(.git/node_modules/target 等)
        // 整个子树不进入——此前 Files.walk 只滤文件,隐藏目录内容会被收进来,且巨型依赖目录每次都全量遍历。
        Files.walkFileTree(folder, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!dir.equals(folder) && privacyGuard.isIgnoredDir(dir, folder)) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return java.nio.file.FileVisitResult.TERMINATE;
                if (privacyGuard.isJunkFileName(p.getFileName().toString())) return java.nio.file.FileVisitResult.CONTINUE;
                if (!exts.contains(DocumentTextExtractor.extOf(p.getFileName().toString()))) return java.nio.file.FileVisitResult.CONTINUE;
                if (privacyGuard.ignoredByRules(p, folder, ignoreRules)) return java.nio.file.FileVisitResult.CONTINUE;
                files.add(p);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });

        int deletedCnt = 0;
        int total = files.size();
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger tokens = new AtomicInteger(0);
        // 同步可信感(评审 P0):本次新增/更新/删除分类计数
        AtomicInteger addedCnt = new AtomicInteger(0);
        AtomicInteger updatedCnt = new AtomicInteger(0);
        updateProgress(kbId, total, 0, 0);

        try (Connection conn = openDb(dbPath, dim, true)) {
            openConnections.put(kbId, conn);
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Path real = file.toRealPath();
                var verdict = privacyGuard.check(real, folder);
                String rel = folder.relativize(file).toString();
                if (!verdict.allowed()) {
                    recordSkipped(conn, rel, file, "privacy", verdict.reason());
                    failed.incrementAndGet();
                    updateProgress(kbId, total, done.get(), failed.get());
                    continue;
                }
                try {
                    boolean existed = docExists(conn, rel);
                    boolean reindexed = indexOneFile(conn, client, folder, file, rel, model, dim, fullRebuild, tokens, !vecUnavailable.contains(dbPath));
                    done.incrementAndGet();
                    // 只统计真重嵌的:新增/更新;未变化跳过不计(实测:全计入会把「无变化」说成「更新」)
                    if (reindexed) {
                        if (existed) updatedCnt.incrementAndGet(); else addedCnt.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.warn("[KB] index failed {}: {}", rel, e.getMessage());
                    recordSkipped(conn, rel, file, "extract_failed", e.getMessage());
                    failed.incrementAndGet();
                }
                updateProgress(kbId, total, done.get(), failed.get());
            }
            // 增量:删除已消失文件的文档
            if (!fullRebuild) {
                deletedCnt = pruneDeletedFiles(conn, files, folder);
            }
            rebuildTopics(conn);
        } finally {
            openConnections.remove(kbId);
        }

        // 全量重建:发布前完整性校验(评审 Major-2)——有文件却零成功=索引为空,不切换(旧代继续服务)
        if (fullRebuild) {
            if (total > 0 && done.get() == 0) {
                updateStatus(kbId, "FAILED", "全量重建零文档入库,已保留旧索引;请检查抽取失败原因后重试");
                Files.deleteIfExists(dbPath);
                return;
            }
            Path finalDb = kbDir.resolve("index.db");
            Files.move(dbPath, finalDb, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        Map<String, Object> m = getBase(kbId);
        m.put("status", "READY");
        m.put("lastRefreshAt", System.currentTimeMillis());
        m.put("lastSuccessfulRefreshAt", System.currentTimeMillis());
        m.put("scheduleFailures", 0);
        Map<String, Object> lastChange = new LinkedHashMap<>();
        lastChange.put("added", addedCnt.get());
        lastChange.put("updated", updatedCnt.get());
        lastChange.put("deleted", deletedCnt);
        lastChange.put("failed", failed.get());
        m.put("lastChange", lastChange);
        m.put("lastBuildMs", System.currentTimeMillis() - buildStart);
        m.put("vectorAvailable", !vecUnavailable.contains(kbDir.resolve("index.db")));
        m.put("activeGeneration", buildGeneration);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documents", done.get());
        stats.put("chunks", countChunks(kbDir.resolve("index.db")));
        stats.put("tokensEmbedded", tokens.get());
        try {
            stats.put("indexBytes", Files.size(kbDir.resolve("index.db")));
        } catch (IOException ignore) { /* 体积缺失不阻断 */ }
        m.put("stats", stats);
        writeManifest(kbId, m);
        log.info("[KB] built {}: {}/{} docs, failed={}", kbId, done.get(), total, failed.get());
    }

    /** @return true=实际重嵌,false=未变化跳过 */
    private boolean indexOneFile(Connection conn, DashScopeRetrievalClient client, Path folder, Path file,
                              String rel, String model, int dim, boolean fullRebuild, AtomicInteger tokens,
                              boolean vecOk) throws Exception {
        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        if (!fullRebuild) {
            // 增量快筛:mtime+size 一致则跳过
            // 仅 status='ok' 的快照可跳过重嵌;失败/跳过的文档刷新时重试
            // (否则一次 too_large 标记会随 mtime 不变被永久冻结——2026-10-07 实测)
            if (!fullRebuild) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, file_mtime, file_size, status FROM kb_document WHERE rel_path=?")) {
                    ps.setString(1, rel);
                    var rs = ps.executeQuery();
                    if (rs.next() && "ok".equals(rs.getString("status"))
                            && rs.getLong("file_mtime") == mtime && rs.getLong("file_size") == size) {
                        return false; // 未变化且上次成功
                    }
                }
            }
        }

        var extracted = extractor.extract(file);
        if (!extracted.ok()) {
            recordSkipped(conn, rel, file, extracted.status(), extracted.error());
            return false;
        }
        List<Chunker.Chunk> chunks = chunker.chunk(extracted.text(), extracted.headings());
        String hash = sha256(extracted.text());

        conn.setAutoCommit(false);
        try {
            // 删旧
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM kb_document WHERE rel_path=?")) {
                ps.setString(1, rel);
                var rs = ps.executeQuery();
                if (rs.next()) {
                    long oldDocId = rs.getLong(1);
                    deleteDocCascade(conn, oldDocId);
                }
            }
            long docId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kb_document(rel_path,file_name,file_ext,file_size,file_mtime,content_hash,chunk_count,status,indexed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, rel);
                ps.setString(2, file.getFileName().toString());
                ps.setString(3, DocumentTextExtractor.extOf(file.getFileName().toString()));
                ps.setLong(4, size);
                ps.setLong(5, mtime);
                ps.setString(6, hash);
                ps.setInt(7, chunks.size());
                ps.setString(8, "ok");
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
                docId = ps.getGeneratedKeys().getLong(1);
            }
            // chunk 落库与向量写入解耦(评审 P0 实证修复:vecOk=false 时 chunk 不能跟着消失,
            // 否则 FTS-only 降级是空话——文档 chunk_count 有数而检索无米)
            // 第一遍:chunk 行永远写(FTS 触发器随之同步)
            List<Long> chunkIds = new ArrayList<>();
            if (!chunks.isEmpty()) {
                for (int ci = 0; ci < chunks.size(); ci++) {
                    Chunker.Chunk c = chunks.get(ci);
                    long chunkId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO kb_chunk(doc_id,chunk_index,content,header_path,char_start,char_end,embedding_model,stale) VALUES(?,?,?,?,?,?,?,0)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, docId);
                        ps.setInt(2, ci);
                        ps.setString(3, c.content());
                        ps.setString(4, c.headerPath());
                        ps.setInt(5, c.charStart());
                        ps.setInt(6, c.charEnd());
                        ps.setString(7, model);
                        ps.executeUpdate();
                        chunkId = ps.getGeneratedKeys().getLong(1);
                    }
                    chunkIds.add(chunkId);
                }
            }
            // 第二遍:仅 vec 可用时批量 embedding + 写向量
            if (vecOk && !chunkIds.isEmpty()) {
                int batchLimit = DashScopeRetrievalClient.batchLimitOf(model);
                for (int i = 0; i < chunks.size(); i += batchLimit) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    List<Chunker.Chunk> batch = chunks.subList(i, Math.min(i + batchLimit, chunks.size()));
                    float[][] vecs = client.embed(model, dim,
                            batch.stream().map(Chunker.Chunk::content).toList(), "document", null);
                    tokens.addAndGet(batch.stream().mapToInt(c -> c.content().length() / 2).sum()); // 粗估
                    for (int j = 0; j < batch.size(); j++) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO kb_chunk_vec(rowid, embedding) VALUES (?, ?)")) {
                            ps.setLong(1, chunkIds.get(i + j));
                            ps.setString(2, floatArrayToJson(vecs[j]));
                            ps.executeUpdate();
                        }
                    }
                }
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return true;
    }

    // ==================== 检索 ====================

    /**
     * 统一检索：scope = null/all(记忆+全部 KB)、memory、库名(单个)、逗号分隔(多库)。
     * 跨模型 KB：按 (model,dimension) 分组各自 embed query,RRF 只融名次不融距离(设计 §7.5-#1)。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, String scope, int topK) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> kbs = resolveScope(scope);
        if (kbs.isEmpty()) return out;

        // 按 (model,dimension) 分组
        Map<String, List<Map<String, Object>>> groups = kbs.stream().collect(Collectors.groupingBy(
                m -> m.get("embeddingModel") + ":" + m.get("dimension")));

        KnowledgeRetrievalProvider.Capability cap = retrievalProvider.capability();
        DashScopeRetrievalClient client = cap.available() ? retrievalProvider.client() : null;

        List<Map<String, Object>> merged = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            String model = (String) group.get(0).get("embeddingModel");
            int dim = ((Number) group.get(0).get("dimension")).intValue();
            float[] qvec = null;
            if (client != null) {
                // query 指令(仅 text_type=query 生效,英文撰写;2026-10-08 用户裁定:强调中英文公司+个人资料检索)
                float[][] r = client.embed(model, dim, List.of(query), "query",
                        "Given a Chinese or English question, retrieve relevant passages from the user's company and personal documents (manuals, tables, slides, reports, notes)");
                qvec = r[0];
            }
            for (Map<String, Object> kb : group) {
                merged.addAll(searchOneBase((String) kb.get("kbId"), (String) kb.get("name"), query, qvec, KNN_K));
            }
        }

        // RRF 名次融合(跨向量空间只融名次)
        Map<String, Double> rrf = new HashMap<>();
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        // 简单按各源内名次做 RRF:向量路与全文路各自排序后融合
        List<Map<String, Object>> vecRanked = merged.stream().filter(m -> m.get("vecRank") != null)
                .sorted(Comparator.comparingInt(m -> (int) m.get("vecRank"))).toList();
        List<Map<String, Object>> ftsRanked = merged.stream().filter(m -> m.get("ftsRank") != null)
                .sorted(Comparator.comparingInt(m -> (int) m.get("ftsRank"))).toList();
        int rank = 1;
        for (var m : vecRanked) {
            String k = key(m);
            rrf.merge(k, 1.0 / (RRF_K + rank++), Double::sum);
            byKey.putIfAbsent(k, m);
        }
        rank = 1;
        for (var m : ftsRanked) {
            String k = key(m);
            rrf.merge(k, 1.0 / (RRF_K + rank++), Double::sum);
            byKey.putIfAbsent(k, m);
        }
        List<Map<String, Object>> fused = new ArrayList<>(byKey.values());
        fused.sort((a, b) -> Double.compare(rrf.get(key(b)), rrf.get(key(a))));
        List<Map<String, Object>> top = new ArrayList<>(fused.stream().limit(Math.max(topK * 2, 8)).toList());

        // 可选 rerank：跨空间查询中必须尊重每个空间的关闭设置；任一空间关闭则整体保留 RRF 顺序。
        boolean rerankEnabled = !kbs.isEmpty() && kbs.stream().allMatch(k -> Boolean.TRUE.equals(k.get("rerankEnabled")));
        if (rerankEnabled && client != null && !top.isEmpty()) {
            try {
                double[] scores = client.rerank(advancedConfig != null ? advancedConfig.getRerankModel() : com.aliyun.odps.agentic.config.AgentAdvancedSettings.DEFAULT_RERANK_MODEL, query,
                        top.stream().map(m -> (String) m.get("content")).toList());
                for (int i = 0; i < top.size(); i++) top.get(i).put("rerankScore", scores[i]);
                top.sort((a, b) -> Double.compare(
                        ((Number) b.getOrDefault("rerankScore", 0.0)).doubleValue(),
                        ((Number) a.getOrDefault("rerankScore", 0.0)).doubleValue()));
            } catch (Exception e) {
                log.warn("[KB] rerank failed, fallback to RRF order: {}", e.getMessage());
            }
        }
        return top.stream().limit(topK).peek(m -> {
            // 命中渠道透出(「为什么命中」徽章):vecRank→向量,ftsRank→全文
            m.put("viaVec", m.remove("vecRank") != null);
            m.put("viaFts", m.remove("ftsRank") != null);
        }).collect(Collectors.toList());
    }

    /** 单库检索：向量 KNN ∥ FTS5,各自带路内名次。 */
    private List<Map<String, Object>> searchOneBase(String kbId, String kbName, String query, float[] qvec, int k) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        if (!Files.isRegularFile(dbPath)) return out;
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        try (Connection conn = openDb(dbPath, dim, false)) {
            if (qvec != null && !vecUnavailable.contains(dbPath)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT v.rowid, v.distance FROM kb_chunk_vec v WHERE v.embedding MATCH ? AND k=? "
                                + "AND v.distance < " + maxDistance + " "
                                + "AND EXISTS(SELECT 1 FROM kb_chunk c JOIN kb_document d ON d.id=c.doc_id AND d.status='ok' WHERE c.id=v.rowid) ORDER BY v.distance")) {
                    ps.setString(1, floatArrayToJson(qvec));
                    ps.setInt(2, k);
                    var rs = ps.executeQuery();
                    int r = 1;
                    while (rs.next()) {
                        out.add(chunkHit(conn, kbId, kbName, rs.getLong(1), rs.getDouble(2), r, null));
                        r++;
                    }
                } catch (SQLException e) {
                    log.debug("[KB] vec search skipped for {}: {}", kbId, e.getMessage());
                }
            }
            // FTS5(unicode61——中文分词对比实测见 M1;trigram 备选)
            try (PreparedStatement ps = conn.prepareStatement(
                    // FTS5 MATCH 左值=裸表名(别名本体 f MATCH 也报 no such column——python+java 双实证)
                    "SELECT kb_chunk_fts.rowid, rank FROM kb_chunk_fts WHERE kb_chunk_fts MATCH ? "
                            + "AND EXISTS(SELECT 1 FROM kb_chunk c JOIN kb_document d ON d.id=c.doc_id AND d.status='ok' WHERE c.id=kb_chunk_fts.rowid) ORDER BY rank LIMIT ?")) {
                ps.setString(1, ftsQueryOf(query));
                ps.setInt(2, k);
                var rs = ps.executeQuery();
                int r = 1;
                while (rs.next()) {
                    out.add(chunkHit(conn, kbId, kbName, rs.getLong(1), null, null, r));
                    r++;
                }
            } catch (SQLException e) {
                log.debug("[KB] fts search skipped for {}: {}", kbId, e.getMessage());
            }
        } catch (SQLException e) {
            log.warn("[KB] search open failed {}: {}", kbId, e.getMessage());
        }
        return out;
    }

    private Map<String, Object> chunkHit(Connection conn, String kbId, String kbName, long chunkId,
                                         Double vecDistance, Integer vecRank, Integer ftsRank) throws SQLException {
        Map<String, Object> hit = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.id, c.content, c.header_path, c.chunk_index, d.rel_path, d.file_name FROM kb_chunk c JOIN kb_document d ON d.id=c.doc_id AND d.status='ok' WHERE c.id=?")) {
            ps.setLong(1, chunkId);
            var rs = ps.executeQuery();
            if (!rs.next()) return hit;
            hit.put("source", "kb");
            hit.put("kbId", kbId);
            hit.put("kbName", kbName);
            hit.put("chunkId", rs.getLong(1));
            hit.put("content", rs.getString(2));
            hit.put("headerPath", rs.getString(3));
            hit.put("chunkIndex", rs.getInt(4));
            // 内容寻址锚(引用稳定锚):哈希不随重建漂移,序号会
            hit.put("contentHash", sha256(rs.getString(2)).substring(0, 12));
            hit.put("relPath", rs.getString(5));
            hit.put("fileName", rs.getString(6));
        }
        if (vecDistance != null) hit.put("vecDistance", vecDistance);
        if (vecRank != null) hit.put("vecRank", vecRank);
        if (ftsRank != null) hit.put("ftsRank", ftsRank);
        return hit;
    }

    // ==================== 文档列表 / 主题 ====================

    public List<Map<String, Object>> listDocuments(String kbId) {
        return listDocumentsInternal(kbId, 0, 200);
    }

    public List<Map<String, Object>> listDocuments(String kbId, int offset, int limit) {
        return listDocumentsInternal(kbId, offset, limit);
    }

    /** 分页文档列表(评审:万级文件全量返回会撑爆):失败来源永远置顶(可自助修复是 P0),其余按路径。 */
    public Map<String, Object> listDocumentsPaged(String kbId, int offset, int limit) {
        List<Map<String, Object>> page = listDocumentsInternal(kbId, offset, limit);
        // failedCount 一并下发:统计口径不受分页污染(实证:581/19 曾只算了第一页)
        return Map.of("items", page, "total", countDocuments(kbId), "failedCount", countDocumentsByStatus(kbId, false), "offset", offset, "limit", limit);
    }

    private int countDocumentsByStatus(String kbId, boolean ok) {
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        if (!Files.isRegularFile(dbPath)) return 0;
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        try (Connection conn = openDb(dbPath, dim, false);
             var ps = conn.prepareStatement(ok ? "SELECT COUNT(*) FROM kb_document WHERE status='ok'" : "SELECT COUNT(*) FROM kb_document WHERE status!='ok'")) {
            var rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public int countDocuments(String kbId) {
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        if (!Files.isRegularFile(dbPath)) return 0;
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        try (Connection conn = openDb(dbPath, dim, false);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM kb_document")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    private List<Map<String, Object>> listDocumentsInternal(String kbId, int offset, int limit) {
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        if (!Files.isRegularFile(dbPath)) return List.of();
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        List<Map<String, Object>> docs = new ArrayList<>();
        try (Connection conn = openDb(dbPath, dim, false);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, rel_path, file_name, file_ext, file_size, file_mtime, chunk_count, status, error, indexed_at FROM kb_document "
                             + "ORDER BY (status != 'ok') DESC, rel_path LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            var rs = ps.executeQuery();
            while (rs.next()) {
                docs.add(documentRow(rs));
            }
        } catch (SQLException e) {
            log.warn("[KB] list documents failed {}: {}", kbId, e.getMessage());
        }
        return docs;
    }

    /**
     * 按 relPath 精确定位文档(引用锚定专用)——不受分页影响。
     *
     * <p>实证:1670 来源库点第一页以外的引用,前端在已加载页 find 落空误报「来源已移除」。</p>
     *
     * @return 文档行;不存在返回 null
     */
    public Map<String, Object> findDocumentByRelPath(String kbId, String relPath) {
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        if (!Files.isRegularFile(dbPath) || relPath == null || relPath.isBlank()) return null;
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        try (Connection conn = openDb(dbPath, dim, false);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, rel_path, file_name, file_ext, file_size, file_mtime, chunk_count, status, error, indexed_at FROM kb_document WHERE rel_path=?")) {
            ps.setString(1, relPath);
            var rs = ps.executeQuery();
            return rs.next() ? documentRow(rs) : null;
        } catch (SQLException e) {
            log.warn("[KB] lookup document failed {}: {}", kbId, e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> documentRow(java.sql.ResultSet rs) throws SQLException {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", rs.getLong(1));
        d.put("relPath", rs.getString(2));
        d.put("fileName", rs.getString(3));
        d.put("fileExt", rs.getString(4));
        d.put("fileSize", rs.getLong(5));
        d.put("fileMtime", rs.getLong(6));
        d.put("chunkCount", rs.getInt(7));
        d.put("status", rs.getString(8));
        d.put("error", rs.getString(9));
        d.put("indexedAt", rs.getLong(10));
        return d;
    }

    public List<Map<String, Object>> listChunks(String kbId, long docId) {
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        List<Map<String, Object>> chunks = new ArrayList<>();
        try (Connection conn = openDb(dbPath, dim, false);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, chunk_index, content, header_path, char_start, char_end, stale FROM kb_chunk WHERE doc_id=? ORDER BY chunk_index")) {
            ps.setLong(1, docId);
            var rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", rs.getLong(1));
                c.put("chunkIndex", rs.getInt(2));
                c.put("content", rs.getString(3));
                c.put("headerPath", rs.getString(4));
                c.put("charStart", rs.getInt(5));
                c.put("charEnd", rs.getInt(6));
                c.put("stale", rs.getInt(7) != 0);
                c.put("contentHash", sha256(rs.getString(3)).substring(0, 12));
                chunks.add(c);
            }
        } catch (SQLException e) {
            log.warn("[KB] list chunks failed {}: {}", kbId, e.getMessage());
        }
        return chunks;
    }

    /** 主题列表(v1 机械派生:文件夹层级=L1,文档标题/header_path=L2)。 */
    public List<Map<String, Object>> listTopics(String kbId) {
        Path dbPath = knowledgeRoot.resolve(kbId).resolve("index.db");
        if (!Files.isRegularFile(dbPath)) return List.of();
        Map<String, Object> manifest = readManifest(kbId);
        int dim = manifest != null ? ((Number) manifest.get("dimension")).intValue() : 1024;
        List<Map<String, Object>> topics = new ArrayList<>();
        try (Connection conn = openDb(dbPath, dim, false);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT t.id, t.title, t.parent_id, t.source_count, t.updated_at FROM knowledge_topic t ORDER BY (t.parent_id IS NULL) ASC, t.parent_id, t.title");
             ) {
            var rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> tp = new LinkedHashMap<>();
                tp.put("id", rs.getLong(1));
                tp.put("title", rs.getString(2));
                long pid = rs.getLong(3);
                tp.put("parentId", rs.wasNull() ? null : pid);
                tp.put("sourceCount", rs.getInt(4));
                tp.put("updatedAt", rs.getLong(5));
                topics.add(tp);
            }
        } catch (SQLException e) {
            log.debug("[KB] list topics skipped {}: {}", kbId, e.getMessage());
        }
        return topics;
    }

    /** 机械主题派生:一级=源文件夹内子目录,二级=文档文件名(去扩展名),关联=归属 chunk 数。 */
    private void rebuildTopics(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM topic_chunk");
            st.execute("DELETE FROM knowledge_topic");
        }
        Map<String, Long> topicIds = new HashMap<>();
        try (PreparedStatement docs = conn.prepareStatement(
                     "SELECT id, rel_path, file_name FROM kb_document WHERE status='ok'");
             var rs = docs.executeQuery()) {
            while (rs.next()) {
                long docId = rs.getLong(1);
                String rel = rs.getString(2);
                String fileName = rs.getString(3);
                // L1: 子目录(无子目录时=库根 "全部")
                String l1 = rel.contains(FileSystems.getDefault().getSeparator()) || rel.contains("/")
                        ? rel.replace('\\', '/').split("/")[0] : "根目录";
                long l1Id = topicIds.computeIfAbsent("L1:" + l1, k -> insertTopic(conn, l1, null));
                // L2: 文件名去扩展名
                String l2 = fileName.replaceFirst("\\.[^.]+$", "");
                long l2Id = topicIds.computeIfAbsent("L2:" + l1 + "/" + l2, k -> insertTopic(conn, l2, l1Id));
                // 关联 chunks
                try (PreparedStatement cs = conn.prepareStatement("SELECT id FROM kb_chunk WHERE doc_id=?")) {
                    cs.setLong(1, docId);
                    var crs = cs.executeQuery();
                    while (crs.next()) {
                        try (PreparedStatement link = conn.prepareStatement(
                                "INSERT OR IGNORE INTO topic_chunk(topic_id, chunk_id, relevance) VALUES(?,?,1.0)")) {
                            link.setLong(1, l2Id);
                            link.setLong(2, crs.getLong(1));
                            link.executeUpdate();
                        }
                        try (PreparedStatement link = conn.prepareStatement(
                                "INSERT OR IGNORE INTO topic_chunk(topic_id, chunk_id, relevance) VALUES(?,?,0.6)")) {
                            link.setLong(1, l1Id);
                            link.setLong(2, crs.getLong(1));
                            link.executeUpdate();
                        }
                    }
                }
            }
        }
        // source_count 回填
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE knowledge_topic SET source_count=(SELECT COUNT(DISTINCT c.doc_id) FROM topic_chunk tc JOIN kb_chunk c ON c.id=tc.chunk_id WHERE tc.topic_id=knowledge_topic.id)");
        }
    }

    private long insertTopic(Connection conn, String title, Long parentId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO knowledge_topic(title, parent_id, source_count, updated_at) VALUES(?,?,0,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            if (parentId == null) ps.setNull(2, Types.INTEGER); else ps.setLong(2, parentId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return ps.getGeneratedKeys().getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 删除 ====================

    public void deleteBase(String kbId) {
        Map<String, Object> m = readManifest(kbId);
        if (m == null) return;
        m.put("status", "DELETING");
        writeManifest(kbId, m);
        cancelBuildQuietly(kbId);
        Connection c = openConnections.remove(kbId);
        closeQuietly(c);
        Path dir = knowledgeRoot.resolve(kbId);
        // 句柄释放轮询(Windows 关连接不即释;评审 Major-4:固定 50ms 是赌博)——至多等 5s
        long deadline = System.currentTimeMillis() + 5000;
        while (Files.exists(dir) && System.currentTimeMillis() < deadline) {
            try {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignore) { /* 下轮再试 */ }
                });
            } catch (IOException ignore) { /* 下轮再试 */ }
            if (!Files.exists(dir)) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 删不干净必须让调用方知道(评审 Major-4:逐文件失败被静默后 REST 报成功是假成功)
        if (Files.exists(dir)) {
            updateStatus(kbId, "FAILED", "删除未完成:文件被占用或权限不足");
            throw new IllegalStateException("知识库目录删除未完成(文件可能被占用): " + dir);
        }
    }

    // ==================== 内部工具 ====================

    private Connection openDb(Path dbPath, int dimension, boolean create) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("创建目录失败", e);
        }
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.enableLoadExtension(true);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());
        boolean vecOk = true;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");  // 写锁短等待(底防线;EBUSY 实证)
            // FTS 分词器迁移:老库若是 unicode61(CJK 全文失效) → 重建为 trigram 并重建索引
            var ms = st.executeQuery("SELECT sql FROM sqlite_master WHERE name='kb_chunk_fts'");
            if (ms.next()) {
                String ddl = String.valueOf(ms.getString(1));
                if (ddl != null && !ddl.contains("trigram")) {
                    st.execute("DROP TABLE kb_chunk_fts");
                    st.execute("CREATE VIRTUAL TABLE kb_chunk_fts USING fts5(content, content='kb_chunk', content_rowid='id', tokenize='trigram')");
                    st.execute("INSERT INTO kb_chunk_fts(kb_chunk_fts) VALUES('rebuild')");
                    log.info("[KB] FTS 分词器迁移 unicode61→trigram: {}", dbPath);
                }
            }
            // vec0 平台二进制缺失时降级 FTS-only(评审 Major-3:不得整库不可用)
            try {
                SqliteVecExtension.load(conn);
            } catch (IllegalStateException e) {
                vecOk = false;
                vecUnavailable.add(dbPath);
                log.warn("[KB] sqlite-vec 不可用,退化为全文检索 {}: {}", dbPath, e.getMessage());
            }
            if (create) {
                createKbTables(st, vecOk, dimension);
            }
        }
        return conn;
    }

    private void createKbTables(Statement st, boolean vecOk, int dimension) throws SQLException {
        {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS kb_document(
                      id INTEGER PRIMARY KEY, rel_path TEXT NOT NULL UNIQUE, file_name TEXT NOT NULL,
                      file_ext TEXT NOT NULL, file_size INTEGER, file_mtime INTEGER,
                      content_hash TEXT, chunk_count INTEGER NOT NULL DEFAULT 0,
                      status TEXT NOT NULL DEFAULT 'ok', error TEXT, indexed_at INTEGER NOT NULL)""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS kb_chunk(
                      id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
                      chunk_index INTEGER NOT NULL, content TEXT NOT NULL, header_path TEXT,
                      char_start INTEGER, char_end INTEGER, embedding_model TEXT, stale INTEGER NOT NULL DEFAULT 0)""");
                if (vecOk) {
                    st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS kb_chunk_vec USING vec0(embedding float[" + dimension + "] distance_metric=cosine)");
                }
                // trigram:CJK 子串可命中(unicode61 把整段中文当一个 token,中文全文等于没有——实证)
                st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS kb_chunk_fts USING fts5(content, content='kb_chunk', content_rowid='id', tokenize='trigram')");
                // FTS 触发器(沿用 memory semantic_fact 先例)
                st.execute("""
                    CREATE TRIGGER IF NOT EXISTS kb_chunk_ai AFTER INSERT ON kb_chunk BEGIN
                      INSERT INTO kb_chunk_fts(rowid, content) VALUES (new.id, new.content); END""");
                st.execute("""
                    CREATE TRIGGER IF NOT EXISTS kb_chunk_ad AFTER DELETE ON kb_chunk BEGIN
                      INSERT INTO kb_chunk_fts(kb_chunk_fts, rowid, content) VALUES('delete', old.id, old.content); END""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_topic(
                      id INTEGER PRIMARY KEY, title TEXT NOT NULL, parent_id INTEGER,
                      source_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS topic_chunk(
                      topic_id INTEGER NOT NULL, chunk_id INTEGER NOT NULL, relevance REAL,
                      PRIMARY KEY(topic_id, chunk_id))""");
        }
    }

    private void recordSkipped(Connection conn, String rel, Path file, String status, String error) {
        try {
            conn.setAutoCommit(false);
            // 评审 Major-1:此前只改状态不清旧 chunk——曾经成功、本次失败的文档会把旧内容留在索引里继续被召回。
            // 先清既有 chunk(vec/fts/topic 级联),再落失败状态。
            try (PreparedStatement find = conn.prepareStatement("SELECT id FROM kb_document WHERE rel_path=?")) {
                find.setString(1, rel);
                var rs = find.executeQuery();
                if (rs.next()) {
                    long docId = rs.getLong(1);
                    try (PreparedStatement chunks = conn.prepareStatement("SELECT id FROM kb_chunk WHERE doc_id=?")) {
                        chunks.setLong(1, docId);
                        var crs = chunks.executeQuery();
                        List<Long> ids = new ArrayList<>();
                        while (crs.next()) ids.add(crs.getLong(1));
                        for (Long cid : ids) {
                            try (PreparedStatement dv = conn.prepareStatement("DELETE FROM kb_chunk_vec WHERE rowid=?")) {
                                dv.setLong(1, cid); dv.executeUpdate();
                            }
                            try (PreparedStatement dt = conn.prepareStatement("DELETE FROM topic_chunk WHERE chunk_id=?")) {
                                dt.setLong(1, cid); dt.executeUpdate();
                            }
                        }
                    }
                    try (PreparedStatement dc = conn.prepareStatement("DELETE FROM kb_chunk WHERE doc_id=?")) {
                        dc.setLong(1, docId); dc.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kb_document(rel_path,file_name,file_ext,file_size,file_mtime,content_hash,chunk_count,status,error,indexed_at) VALUES(?,?,?,?,?,?,0,?,?,?) "
                            + "ON CONFLICT(rel_path) DO UPDATE SET status=excluded.status, error=excluded.error, chunk_count=0, indexed_at=excluded.indexed_at")) {
                ps.setString(1, rel);
                ps.setString(2, file.getFileName().toString());
                ps.setString(3, DocumentTextExtractor.extOf(file.getFileName().toString()));
                try { ps.setLong(4, Files.size(file)); } catch (IOException e) { ps.setLong(4, 0); }
                try { ps.setLong(5, Files.getLastModifiedTime(file).toMillis()); } catch (IOException e) { ps.setLong(5, 0); }
                ps.setString(6, "");
                ps.setString(7, status);
                ps.setString(8, error);
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            log.debug("[KB] record skipped failed: {}", e.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
        }
    }

    private void deleteDocCascade(Connection conn, long docId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM kb_chunk WHERE doc_id=?")) {
            ps.setLong(1, docId);
            var rs = ps.executeQuery();
            List<Long> chunkIds = new ArrayList<>();
            while (rs.next()) chunkIds.add(rs.getLong(1));
            for (Long cid : chunkIds) {
                try (PreparedStatement dv = conn.prepareStatement("DELETE FROM kb_chunk_vec WHERE rowid=?")) {
                    dv.setLong(1, cid);
                    dv.executeUpdate();
                }
                try (PreparedStatement dt = conn.prepareStatement("DELETE FROM topic_chunk WHERE chunk_id=?")) {
                    dt.setLong(1, cid);
                    dt.executeUpdate();
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM kb_chunk WHERE doc_id=?")) {
            ps.setLong(1, docId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM kb_document WHERE id=?")) {
            ps.setLong(1, docId);
            ps.executeUpdate();
        }
    }

    private boolean docExists(Connection conn, String rel) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM kb_document WHERE rel_path=? AND status='ok'")) {
            ps.setString(1, rel);
            return ps.executeQuery().next();
        }
    }

    private int pruneDeletedFiles(Connection conn, List<Path> existing, Path folder) throws SQLException {
        Set<String> existingRel = existing.stream().map(p -> folder.relativize(p).toString()).collect(Collectors.toSet());
        List<Long> toDelete = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, rel_path FROM kb_document");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!existingRel.contains(rs.getString(2))) toDelete.add(rs.getLong(1));
            }
        }
        for (Long id : toDelete) deleteDocCascade(conn, id);
        return toDelete.size();
    }

    private int countChunks(Path dbPath) {
        if (!Files.isRegularFile(dbPath)) return 0;
        try (Connection conn = openDb(dbPath, 1024, false);
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM kb_chunk")) {
            return rs.getInt(1);
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Map<String, Object>> resolveScope(String scope) {
        List<Map<String, Object>> all = listBases().stream()
                .filter(m -> "READY".equals(m.get("status")) || "DEGRADED".equals(m.get("status")))
                .toList();
        if (scope == null || scope.isBlank() || "all".equals(scope)) return all;
        if ("memory".equals(scope)) return List.of();
        Set<String> names = Arrays.stream(scope.split(",")).map(String::trim).collect(Collectors.toSet());
        return all.stream().filter(m -> names.contains(m.get("name")) || names.contains(m.get("kbId"))).toList();
    }

    /** scope 拼错/无命中时返回可用库名(不静默退化为全库)。 */
    public List<String> availableBaseNames() {
        return listBases().stream().map(m -> (String) m.get("name")).toList();
    }

    /** trigram 查询构造:CJK 连续段整段加引号(trigram 子串命中);<3 字的 CJK 段跳过
        (trigram 最短 3  gram,短词由向量路兜底);非 CJK 词加引号。 */
    private String ftsQueryOf(String query) {
        // unicode61 下中文按 CJK 单字 bigram 近似:逐字 OR 太宽,做连续双字 token
        StringBuilder sb = new StringBuilder();
        String clean = query.replaceAll("[\"'*()\\[\\]]", " ").trim();
        for (String tok : clean.split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.matches("[\\p{IsHan}]{3,}")) {
                // trigram:CJK 连续段整段短语匹配(子串命中)
                if (sb.length() > 0) sb.append(" AND ");
                sb.append('"').append(tok).append('"');
            } else if (tok.matches("[\\p{IsHan}]+")) {
                // <3 字 CJK:trigram 不可查,跳过(向量路兜底)
                continue;
            } else {
                if (sb.length() > 0) sb.append(" AND ");
                sb.append('"').append(tok.replace("\"", "")).append('"');
            }
        }
        return sb.toString();
    }

    private String key(Map<String, Object> m) {
        return m.get("kbId") + ":" + m.get("chunkId");
    }

    private String newKbId(String name) {
        String base = "kb_" + name.replaceAll("[^\\p{IsHan}\\w-]", "_");
        String id = base;
        int i = 2;
        while (Files.exists(knowledgeRoot.resolve(id))) id = base + "_" + i++;
        return id;
    }

    Map<String, Object> readManifest(String kbId) {
        Path f = knowledgeRoot.resolve(kbId).resolve("manifest.json");
        if (!Files.isRegularFile(f)) return null;
        try {
            return MAPPER.readValue(Files.readString(f, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            log.warn("[KB] manifest read failed {}: {}", kbId, e.getMessage());
            return null;
        }
    }

    private synchronized void writeManifest(String kbId, Map<String, Object> manifest) {
        Path dir = knowledgeRoot.resolve(kbId);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("manifest.json.tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[KB] manifest write failed {}: {}", kbId, e.getMessage());
        }
    }

    private void updateProgress(String kbId, int total, int done, int failed) {
        Map<String, Object> m = readManifest(kbId);
        if (m == null) return;
        m.put("progress", Map.of("total", total, "done", done, "failed", failed));
        writeManifest(kbId, m);
    }

    private void updateStatus(String kbId, String status, String error) {
        Map<String, Object> m = readManifest(kbId);
        if (m == null) return;
        m.put("status", status);
        if (error != null) m.put("lastError", error);
        writeManifest(kbId, m);
    }

    private void cancelBuildQuietly(String kbId) {
        try { cancelBuild(kbId); } catch (Exception ignore) {}
    }

    private void closeQuietly(Connection c) {
        if (c != null) try { c.close(); } catch (SQLException ignore) {}
    }

    private long longOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String floatArrayToJson(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
    @Override public void close() { shutdown(); }
}
