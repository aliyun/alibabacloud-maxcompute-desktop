package com.aliyun.odps.agentic.session;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 事件总线。
 *
 * <p>用于在会话运行过程中以发布订阅方式分发 {@link AgentEvent}，支持全局订阅、按类型订阅、按会话订阅和最近事件回放。
 */
public class EventBus {

    private final Map<Class<? extends AgentEvent>, List<Consumer<? extends AgentEvent>>> subscribers =
        new ConcurrentHashMap<>();

    /** 接收全部事件的全局订阅者。 */
    private final List<Consumer<AgentEvent>> globalSubscribers = new CopyOnWriteArrayList<>();

    /** 按会话隔离的订阅者。 */
    private final Map<String, List<Consumer<AgentEvent>>> sessionSubscribers = new ConcurrentHashMap<>();

    /** 最近事件缓冲区，用于晚加入订阅者回放。 */
    private final Deque<AgentEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 1000;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 订阅全部事件。
     *
     * @param handler 事件处理器
     * @return 可取消的订阅句柄
     */
    public Subscription subscribe(Consumer<AgentEvent> handler) {
        globalSubscribers.add(handler);
        return new Subscription(() -> globalSubscribers.remove(handler));
    }

    /**
     * 订阅指定类型的事件。
     *
     * @param eventType 事件类型
     * @param handler 处理器
     * @param <T> 事件泛型
     */
    public <T extends AgentEvent> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * 订阅某个会话相关的事件。
     *
     * @param sessionId 会话 ID
     * @param handler 事件处理器
     * @return 可取消的订阅句柄
     */
    public Subscription subscribeForSession(String sessionId, Consumer<AgentEvent> handler) {
        sessionSubscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(handler);
        return new Subscription(() -> {
            List<Consumer<AgentEvent>> handlers = sessionSubscribers.get(sessionId);
            if (handlers != null) handlers.remove(handler);
        });
    }

    /**
     * 取消指定类型事件的订阅。
     *
     * @param eventType 事件类型
     * @param handler 处理器
     * @param <T> 事件泛型
     */
    public <T extends AgentEvent> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<? extends AgentEvent>> handlers = subscribers.get(eventType);
        if (handlers != null) {
            handlers.remove(handler);
        }
    }

    /**
     * 异步发出事件。
     * 会通知事件本身类型、基类类型以及全局订阅者。
     *
     * @param event 待分发事件
     */
    @SuppressWarnings("unchecked")
    public void emit(AgentEvent event) {
        if (event == null) return;

        recentEvents.addLast(event);
        while (recentEvents.size() > MAX_RECENT) {
            recentEvents.removeFirst();
        }

        notifySubscribers(event, event.getClass());
        if (event.getClass() != AgentEvent.class) {
            notifySubscribers(event, AgentEvent.class);
        }

        for (Consumer<AgentEvent> handler : globalSubscribers) {
            executor.submit(() -> {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    // 尽力而为地通知订阅者，不让异常影响总线本身。
                }
            });
        }
    }

    /**
     * 为指定会话发出事件。
     * 该事件也会同步发往全局订阅链路。
     *
     * @param sessionId 会话 ID
     * @param event 待分发事件
     */
    public void emitForSession(String sessionId, AgentEvent event) {
        if (event == null) return;
        emit(event);
        List<Consumer<AgentEvent>> handlers = sessionSubscribers.get(sessionId);
        if (handlers != null) {
            for (Consumer<AgentEvent> handler : handlers) {
                executor.submit(() -> {
                    try {
                        handler.accept(event);
                    } catch (Exception e) {
                        // 尽力而为通知。
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void notifySubscribers(AgentEvent event, Class<? extends AgentEvent> eventType) {
        List<Consumer<? extends AgentEvent>> handlers = subscribers.get(eventType);
        if (handlers == null) return;
        for (Consumer<? extends AgentEvent> handler : handlers) {
            executor.submit(() -> {
                try {
                    ((Consumer<AgentEvent>) handler).accept(event);
                } catch (Exception e) {
                    // 订阅者异常不应影响其他处理器。
                }
            });
        }
    }

    /**
     * 同步发出事件，并等待当前类型订阅者执行完成。
     *
     * @param event 待分发事件
     */
    @SuppressWarnings("unchecked")
    public void emitSync(AgentEvent event) {
        if (event == null) return;
        List<Consumer<? extends AgentEvent>> handlers = subscribers.get(event.getClass());
        if (handlers != null) {
            for (Consumer<? extends AgentEvent> handler : handlers) {
                try {
                    ((Consumer<AgentEvent>) handler).accept(event);
                } catch (Exception e) {
                    // 尽力而为。
                }
            }
        }
    }

    /**
     * 获取最近事件列表，用于回放。
     *
     * @param maxCount 最多返回数量
     * @return 最近事件列表
     */
    public List<AgentEvent> getRecentEvents(int maxCount) {
        List<AgentEvent> events = new ArrayList<>(recentEvents);
        int start = Math.max(0, events.size() - maxCount);
        return events.subList(start, events.size());
    }

    /**
     * 关闭事件总线并清理内部资源。
     */
    public void shutdown() {
        executor.shutdownNow();
        subscribers.clear();
        globalSubscribers.clear();
        sessionSubscribers.clear();
        recentEvents.clear();
    }

    /**
     * 可取消的订阅句柄。
     */
    public static class Subscription {
        private final Runnable unsubscribeAction;
        private volatile boolean active = true;

        Subscription(Runnable unsubscribeAction) {
            this.unsubscribeAction = unsubscribeAction;
        }

        /**
         * 取消当前订阅。
         */
        public void unsubscribe() {
            if (active) {
                unsubscribeAction.run();
                active = false;
            }
        }

        /**
         * 判断订阅是否仍然有效。
         *
         * @return 有效返回 {@code true}
         */
        public boolean isActive() {
            return active;
        }
    }
}
