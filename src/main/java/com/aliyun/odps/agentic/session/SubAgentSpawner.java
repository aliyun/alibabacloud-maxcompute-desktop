package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理启动器。
 *
 * <p>用于在独立会话中运行子代理任务，并将其最终文本结果以异步方式返回给父流程。
 */
public class SubAgentSpawner {

    private final ExecutorService executor;
    private final HarnessEngine engine;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Map<String, Future<String>> runningTasks = new ConcurrentHashMap<>();

    /**
     * 子代理任务定义。
     *
     * @param taskId 任务 ID
     * @param prompt 子代理输入提示词
     * @param agentDef 子代理定义
     * @param parentSessionId 父会话 ID
     * @param maxSteps 最大步数
     * @param isolated 是否隔离运行
     */
    public record SubAgentTask(
        String taskId,
        String prompt,
        AgentDef agentDef,
        String parentSessionId,
        int maxSteps,
        boolean isolated
    ) {}

    /**
     * 使用引擎实例创建子代理启动器。
     *
     * @param engine Harness 引擎
     */
    public SubAgentSpawner(HarnessEngine engine) {
        this.engine = engine;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 启动一个子代理任务。
     *
     * @param task 子代理任务定义
     * @return 返回最终文本结果的 {@link Future}
     */
    public Future<String> spawn(SubAgentTask task) {
        if (activeCount.get() >= 10) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Maximum concurrent sub-agents reached (10)")
            );
        }

        activeCount.incrementAndGet();
        Future<String> future = executor.submit(() -> {
            try {
                Session session = engine.createSession(task.agentDef());
                Message result = engine.run(session, task.agentDef(), task.prompt);
                return result != null ? result.getTextContent() : "Sub-agent completed with no output";
            } catch (Exception e) {
                return "Sub-agent failed: " + e.getMessage();
            } finally {
                activeCount.decrementAndGet();
                runningTasks.remove(task.taskId());
            }
        });

        runningTasks.put(task.taskId(), future);
        return future;
    }

    /**
     * 取消正在运行的子代理任务。
     *
     * @param taskId 任务 ID
     * @param mayInterruptIfRunning 是否允许中断执行线程
     * @return 成功取消返回 {@code true}
     */
    public boolean cancel(String taskId, boolean mayInterruptIfRunning) {
        Future<String> future = runningTasks.remove(taskId);
        if (future != null) {
            return future.cancel(mayInterruptIfRunning);
        }
        return false;
    }

    /**
     * 返回当前活跃子代理数量。
     *
     * @return 活跃数量
     */
    public int activeCount() {
        return activeCount.get();
    }

    /**
     * 关闭启动器并取消全部运行中的任务。
     */
    public void shutdown() {
        runningTasks.values().forEach(f -> f.cancel(true));
        runningTasks.clear();
        executor.shutdownNow();
    }
}
