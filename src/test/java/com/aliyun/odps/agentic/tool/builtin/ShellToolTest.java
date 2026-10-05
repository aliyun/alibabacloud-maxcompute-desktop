package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ShellTool}（641 行持久化 bash 工具）的测试安全网。
 *
 * <p>此前该文件无测试。覆盖：参数校验、禁命令、权限门、真实执行与超时。
 */
@Timeout(30)
class ShellToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode args(String command) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("command", command);
        return n;
    }

    private static ToolContext ctx(Boolean permission) {
        return new ToolContext(
            "s1", "m1", "agent", "c1", List.of(), Map.of(),
            md -> {},
            permission == null ? null : (perm, target, desc) -> permission,
            null
        );
    }

    @Test
    void rejectsMissingCommand() {
        ShellTool tool = new ShellTool();
        try {
            ToolResult r = tool.execute(MAPPER.createObjectNode(), ctx(true));
            assertTrue(r.isError());
            assertTrue(r.output().contains("command is required"));
        } finally {
            tool.close();
        }
    }

    @Test
    void rejectsBannedCommand() {
        ShellTool tool = new ShellTool();
        try {
            ToolResult r = tool.execute(args("rm -rf /"), ctx(true));
            assertTrue(r.isError());
            assertTrue(r.output().contains("banned") || r.output().contains("rejected"),
                "banned command should be rejected: " + r.output());
        } finally {
            tool.close();
        }
    }

    @Test
    void respectsPermissionDenial() {
        ShellTool tool = new ShellTool();
        try {
            ToolResult r = tool.execute(args("echo hello"), ctx(false));
            assertTrue(r.isError());
            assertTrue(r.output().contains("Permission denied"), "denied by permission asker: " + r.output());
        } finally {
            tool.close();
        }
    }

    @Test
    void executesRealCommand() {
        ShellTool tool = new ShellTool();
        try {
            ToolResult r = tool.execute(args("echo harness-shell-ok"), ctx(true));
            assertFalse(r.isError(), "echo should succeed: " + r.output());
            assertTrue(r.output().contains("harness-shell-ok"), "output should contain echoed text: " + r.output());
        } finally {
            tool.close();
        }
    }

    @Test
    void capturesNonZeroExitWithoutThrowing() {
        ShellTool tool = new ShellTool();
        try {
            // 命令失败但工具不应抛异常——以结果形式返回
            ToolResult r = tool.execute(args("echo before-fail; exit 3"), ctx(true));
            assertNotNull(r);
            assertTrue(r.output().contains("before-fail"), "should capture output before failure: " + r.output());
        } finally {
            tool.close();
        }
    }

    @Test
    void enforcesTimeout() {
        ShellTool tool = new ShellTool();
        try {
            ObjectNode a = args("sleep 30");
            a.put("timeout", 500); // 500ms 超时
            long start = System.nanoTime();
            ToolResult r = tool.execute(a, ctx(true));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertNotNull(r);
            assertTrue(elapsedMs < 10_000, "timeout should cut off the command, took " + elapsedMs + "ms");
            assertTrue(r.output().contains("timeout") || r.output().contains("terminated")
                || r.output().contains("no output"), "should report timeout: " + r.output());
        } finally {
            tool.close();
        }
    }
}
