package com.aliyun.odps.agentic.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限服务 -- 管理待处理请求、求值规则、阻塞等待回复的核心服务。
 * 通过 {@link CompletableFuture} 实现异步权限确认流程。
 */
public class PermissionService {
    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final PermissionEngine engine;
    private final AsyncPermissionAsker asker;
    // 待处理请求：requestId -> CompletableFuture
    private final Map<String, CompletableFuture<PermissionReply>> pending = new ConcurrentHashMap<>();
    // 待处理请求数据：requestId -> PermissionRequest
    private final Map<String, PermissionRequest> pendingRequests = new ConcurrentHashMap<>();
    // 运行时批准规则（来自 "always" 回复）
    private final List<Rule> runtimeRules = new ArrayList<>();
    // 已经向监听器广播过 Asked 的请求 id。只有它们才配得到一条 Replied ——
    // 同步放行的请求从未在 UI 上出现，不该留下一对孤儿事件。
    private final java.util.Set<String> announced = ConcurrentHashMap.newKeySet();
    // 事件监听器
    private volatile java.util.function.Consumer<PermissionEvent> eventListener;
    // 询问超时（毫秒）。宿主 asker 永不回复时，超时后 fail-closed 为 REJECT，
    // 避免整个 run 死锁。默认 5 分钟；<=0 表示不设超时（保持旧行为，仅用于显式选择）。
    private final long askTimeoutMs;

    public PermissionService(PermissionEngine engine, AsyncPermissionAsker asker) {
        this(engine, asker, java.time.Duration.ofMinutes(5));
    }

    /**
     * 创建带询问超时的权限服务。
     *
     * @param engine     权限规则引擎
     * @param asker      异步询问器
     * @param askTimeout 询问超时；{@code null} 或 ≤0 表示不超时
     */
    public PermissionService(PermissionEngine engine, AsyncPermissionAsker asker, java.time.Duration askTimeout) {
        this.engine = engine;
        this.asker = asker;
        this.askTimeoutMs = askTimeout == null ? 0 : Math.max(0, askTimeout.toMillis());
    }

    /**
     * 设置权限事件监听器。
     *
     * @param listener 事件消费者
     */
    public void setEventListener(java.util.function.Consumer<PermissionEvent> listener) {
        this.eventListener = listener;
    }

    /**
     * 权限事件 -- 权限请求和回复的事件通知。
     */
    public sealed interface PermissionEvent {
        /** 权限请求已发起。 */
        record Asked(PermissionRequest request) implements PermissionEvent {}
        /** 权限请求已回复。 */
        record Replied(String requestId, PermissionReply reply) implements PermissionEvent {}
    }

    /**
     * 求值权限并在需要询问时阻塞。
     * 流程：先检查运行时规则，再检查静态规则，最后通过异步询问器阻塞等待用户回复。
     *
     * @param request 权限请求
     * @return 是否允许该操作
     */
    public boolean ask(PermissionRequest request) {
        // 先检查运行时批准的规则
        synchronized (runtimeRules) {
            for (Rule rule : runtimeRules) {
                if (matchesRequest(rule, request)) {
                    log.debug("Permission auto-approved by runtime rule: {}", request.permission());
                    return true;
                }
            }
        }

        // 再检查静态规则
        Action action = engine.evaluate(request.permission(), request.target());

        if (action == Action.ALLOW) return true;
        if (action == Action.DENY) return false;

        // ASK -- 通过 CompletableFuture 阻塞等待用户回复
        CompletableFuture<PermissionReply> future = new CompletableFuture<>();
        pending.put(request.id(), future);
        pendingRequests.put(request.id(), request);

        try {
            // 先问，再决定要不要广播「已发起询问」。
            //
            // 同步型询问器（CLI 自动放行、宿主的会话级自动批准）返回的是一个**已完成**的
            // future —— 也就是说这次询问根本不需要人看。此前 Asked 事件无条件先于 askAsync
            // 发出，UI 于是闪出一张随即消失的审批卡；而这个先后顺序在服务端，宿主无论怎么
            // 过滤事件都只能追在后面擦，擦不干净。已完成 == 无人需要看到，直接不播。
            //
            // 顺序不可再颠倒：pending.put 必须早于 askAsync —— 同步完成的回调会立刻走进
            // reply()，那时若 future 还没进 pending，就会误报「无此待处理请求」并把回复丢掉。
            CompletableFuture<PermissionReply> askResult = asker.askAsync(request);
            if (!askResult.isDone() && eventListener != null) {
                announced.add(request.id());
                eventListener.accept(new PermissionEvent.Asked(request));
            }
            // fail-closed：asker 的 future 若异常完成（宿主询问器内部出错、长超时策略触发等），
            // 必须让 pending 的 future 以 REJECT 收尾——否则 thenAccept 不回调、future.get() 永久悬挂，
            // 整个 run 死锁。whenComplete 同时覆盖正常与异常两条路径。
            askResult.whenComplete((reply, err) -> {
                if (err != null) {
                    log.warn("Permission asker completed exceptionally for {}; failing closed to REJECT",
                        request.id(), err);
                    reply(request.id(), PermissionReply.REJECT);
                } else {
                    reply(request.id(), reply);
                }
            });

            // 阻塞当前线程直到收到回复（或超时 fail-closed）
            PermissionReply reply = (askTimeoutMs > 0)
                ? future.get(askTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                : future.get();

            if (reply == PermissionReply.ALWAYS) {
                // 添加到运行时规则
                synchronized (runtimeRules) {
                    runtimeRules.add(new Rule(request.permission(), request.target(), Action.ALLOW));
                }
                // 自动解决其他匹配的待处理请求
                autoResolvePending();
            }

            return reply != PermissionReply.REJECT;
        } catch (java.util.concurrent.TimeoutException e) {
            // 询问超时 —— 清理 pending future 以免悬挂，然后抛出 PermissionTimeoutException
            // 让 RunLoop 能区分"超时终止"和"用户拒绝"。
            log.warn("Permission ask timed out after {}ms for {}; throwing PermissionTimeoutException",
                askTimeoutMs, request.id());
            future.complete(PermissionReply.REJECT);
            throw new PermissionTimeoutException(request.tool(), askTimeoutMs);
        } catch (Exception e) {
            log.warn("Permission ask interrupted: {}", e.getMessage());
            return false;
        } finally {
            pending.remove(request.id());
            pendingRequests.remove(request.id());
            announced.remove(request.id());
        }
    }

    /**
     * 回复一个待处理的权限请求。
     * 由 HTTP API 或 UI 调用。
     *
     * @param requestId 请求 ID
     * @param reply     权限回复
     */
    public void reply(String requestId, PermissionReply reply) {
        CompletableFuture<PermissionReply> future = pending.get(requestId);
        if (future == null) {
            log.warn("No pending permission request with id: {}", requestId);
            return;
        }

        // 发布权限回复事件 —— 仅当这条请求确实广播过 Asked。
        // 自动放行的请求不该凭空冒出一条 Replied：宿主拿它去关审批卡，
        // 而那张卡从来没被创建过。
        if (eventListener != null && announced.contains(requestId)) {
            eventListener.accept(new PermissionEvent.Replied(requestId, reply));
        }

        if (reply == PermissionReply.REJECT) {
            // 级联拒绝同一会话中的所有待处理请求
            PermissionRequest req = pendingRequests.get(requestId);
            if (req != null) {
                cascadeReject(req.sessionId());
            }
        } else {
            future.complete(reply);
        }
    }

    /** 级联拒绝指定会话中的所有待处理请求。 */
    private void cascadeReject(String sessionId) {
        for (var entry : pendingRequests.entrySet()) {
            if (entry.getValue().sessionId().equals(sessionId)) {
                CompletableFuture<PermissionReply> f = pending.get(entry.getKey());
                if (f != null && !f.isDone()) {
                    f.complete(PermissionReply.REJECT);
                }
            }
        }
    }

    /** 自动解决与运行时规则匹配的待处理请求。 */
    private void autoResolvePending() {
        synchronized (runtimeRules) {
            for (var entry : pendingRequests.entrySet()) {
                for (Rule rule : runtimeRules) {
                    if (matchesRequest(rule, entry.getValue())) {
                        CompletableFuture<PermissionReply> f = pending.get(entry.getKey());
                        if (f != null && !f.isDone()) {
                            f.complete(PermissionReply.ALWAYS);
                        }
                    }
                }
            }
        }
    }

    private boolean matchesRequest(Rule rule, PermissionRequest request) {
        return ("*".equals(rule.permission()) || rule.permission().equals(request.permission()))
            && ("*".equals(rule.pattern()) || rule.pattern().equals(request.target()));
    }

    /**
     * 列出所有待处理的权限请求。
     *
     * @return 待处理请求列表
     */
    public List<PermissionRequest> listPending() {
        return List.copyOf(pendingRequests.values());
    }

    /**
     * 列出指定会话的待处理权限请求。
     *
     * @param sessionId 会话 ID
     * @return 该会话的待处理请求列表
     */
    public List<PermissionRequest> listPending(String sessionId) {
        return pendingRequests.values().stream()
            .filter(r -> r.sessionId().equals(sessionId))
            .toList();
    }

    /**
     * 关闭服务时拒绝所有待处理请求。
     */
    public void shutdown() {
        for (var future : pending.values()) {
            if (!future.isDone()) {
                future.complete(PermissionReply.REJECT);
            }
        }
        pending.clear();
        pendingRequests.clear();
        announced.clear();
    }
}
