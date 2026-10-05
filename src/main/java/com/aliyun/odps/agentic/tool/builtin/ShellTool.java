package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置 Shell 工具，在持久化的 bash 会话中执行命令。
 * 与每次新建进程的实现不同，它会复用同一个 {@code /bin/bash} 进程以保留环境变量和当前目录状态。
 *
 * <p>工具支持超时控制、权限确认、危险命令拦截、输出截断以及执行进度上报。
 */
public class ShellTool implements ToolDef, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);

    private static final String ID = "shell";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final String DESCRIPTION_TEMPLATE = ResourceLoader.load("tools/shell.txt");

    /** 输出截断阈值。 */
    private static final int MAX_LINES = 4000;
    private static final int MAX_BYTES = 50 * 1024; // 50 KB

    /**
     * 哨兵标记前缀，每次命令执行追加随机 UUID 生成唯一标记。
     */
    private static final String SENTINEL_PREFIX = "__HARNESS_EXIT_CODE_";

    /**
     * 禁止执行的命令列表。
     * 匹配这些模式的命令会在执行前被拦截，以防止不可逆的系统损害。
     */
    private static final Set<String> BANNED_COMMANDS = Set.of(
        // 破坏性系统命令
        "rm -rf /",
        "rm -rf /*",
        "rm -rf ~",
        "rm -rf ~/*",
        "rm -rf $HOME",
        "rm -rf $HOME/",
        "rm -rf $HOME/*",
        // 格式化 / 磁盘擦除
        "mkfs",
        "mkfs.ext4",
        "mkfs.ext3",
        "mkfs.xfs",
        "mkfs.btrfs",
        "mkfs.vfat",
        "dd if=/dev/zero",
        "dd if=/dev/random",
        "dd if=/dev/urandom",
        // Fork 炸弹
        ":(){ :|:& };:",
        // 系统关机/重启
        "shutdown",
        "reboot",
        "halt",
        "poweroff",
        "init 0",
        "init 6"
    );

    /**
     * 影响工作目录的命令集合。
     */
    private static final Set<String> CWD_COMMANDS = Set.of(
        "cd", "chdir", "popd", "pushd", "push-location", "set-location"
    );

    // --------------- 持久化 Shell 会话状态（由 this 锁保护）-----

    /** 长期运行的 bash 进程。首次命令前为 {@code null}。 */
    private Process shellProcess;

    /** 连接到 bash 进程 stdin 的写入器。 */
    private BufferedWriter shellStdin;

    /**
     * 从 bash 进程合并 stdout+stderr 读取的行。
     * 守护线程持续读取并入队。
     */
    private BlockingQueue<String> outputLines;

    /** 守护读取线程。 */
    private Thread readerThread;

    /** 调用 {@link #close()} 后设为 {@code true}。 */
    private volatile boolean closed;

    // -----------------------------------------------------------------------

    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        // 使用 bash/unix 默认值渲染描述模板
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String shell = "bash";
        String tmp = System.getProperty("java.io.tmpdir", "/tmp");

        // bash 描述模板参数
        String intro = "Executes a given bash command in a persistent shell session with optional timeout, ensuring proper handling and security measures.";
        String workdirSection = "All commands run in the current working directory by default. Use the `workdir` parameter if you need to run a command in a different directory. AVOID using `cd <directory> && <command>` patterns - use `workdir` instead.";
        String commandSection = buildBashCommandSection();
        String gitCommands = "bash commands";
        String toolName = ID;
        String gitCommandRestriction = "git bash commands";
        String createPrInstruction = "Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.";
        String createPrExample = "gh pr create --title \"the pr title\" --body \"$(cat <<'EOF'\n## Summary\n<1-3 bullet points>";

        return DESCRIPTION_TEMPLATE
            .replace("${intro}", intro)
            .replace("${os}", os)
            .replace("${shell}", shell)
            .replace("${tmp}", tmp)
            .replace("${workdirSection}", workdirSection)
            .replace("${commandSection}", commandSection);
    }

    /**
     * 构建 bash 专用的命令说明段落。
     */
    private String buildBashCommandSection() {
        String chain = "If the commands depend on each other and must run sequentially, " +
            "use a single Bash call with '&&' to chain them together " +
            "(e.g., `git add . && git commit -m \"message\" && git push`). " +
            "For instance, if one operation must complete before another starts " +
            "(like mkdir before cp, Write before Bash for git operations, " +
            "or git add before git commit), run these operations sequentially instead.";

        return "Before executing the command, please follow these steps:\n" +
            "\n" +
            "1. Directory Verification:\n" +
            "   - If the command will create new directories or files, first use `ls` to verify the parent directory exists and is the correct location\n" +
            "   - For example, before running \"mkdir foo/bar\", first use `ls foo` to check that \"foo\" exists and is the intended parent directory\n" +
            "\n" +
            "2. Command Execution:\n" +
            "   - Always quote file paths that contain spaces with double quotes (e.g., rm \"path with spaces/file.txt\")\n" +
            "   - Examples of proper quoting:\n" +
            "     - mkdir \"/Users/name/My Documents\" (correct)\n" +
            "     - mkdir /Users/name/My Documents (incorrect - will fail)\n" +
            "     - python \"/path/with spaces/script.py\" (correct)\n" +
            "     - python /path/with spaces/script.py (incorrect - will fail)\n" +
            "   - After ensuring proper quoting, execute the command.\n" +
            "   - Capture the output of the command.\n" +
            "\n" +
            "Usage notes:\n" +
            "  - The command argument is required.\n" +
            "  - You can specify an optional timeout in milliseconds. If not specified, commands will time out after " + DEFAULT_TIMEOUT_MS + "ms.\n" +
            "  - It is very helpful if you write a clear, concise description of what this command does in 5-10 words.\n" +
            "  - If the output exceeds " + MAX_LINES + " lines or " + MAX_BYTES + " bytes, it will be truncated and the full output will be written to a file. You can use Read with offset/limit to read specific sections or Grep to search the full content. Do NOT use `head`, `tail`, or other truncation commands to limit output; the full output will already be captured to a file for more precise searching.\n" +
            "\n" +
            "  - Avoid using Bash with the `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or when these commands are truly necessary for the task. Instead, always prefer using the dedicated tools for these commands:\n" +
            "    - File search: Use Glob (NOT find or ls)\n" +
            "    - Content search: Use Grep (NOT grep or rg)\n" +
            "    - Read files: Use Read (NOT cat/head/tail)\n" +
            "    - Edit files: Use Edit (NOT sed/awk)\n" +
            "    - Write files: Use Write (NOT echo >/cat <<EOF)\n" +
            "    - Communication: Output text directly (NOT echo/printf)\n" +
            "  - When issuing multiple commands:\n" +
            "    - If the commands are independent and can run in parallel, make multiple bash tool calls in a single message. For example, if you need to run \"git status\" and \"git diff\", send a single message with two bash tool calls in parallel.\n" +
            "    - " + chain + "\n" +
            "    - Use ';' only when you need to run commands sequentially but don't care if earlier commands fail\n" +
            "    - DO NOT use newlines to separate commands (newlines are ok in quoted strings)\n" +
            "  - AVOID using `cd <directory> && <command>`. Use the `workdir` parameter to change directories instead.\n" +
            "    <good-example>\n" +
            "    Use workdir=\"/foo/bar\" with command: pytest tests\n" +
            "    </good-example>\n" +
            "    <bad-example>\n" +
            "    cd /foo/bar && pytest tests\n" +
            "    </bad-example>";
    }

    /**
     * 返回 shell 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "command": "git status",
     *   "description": "Show working tree status",
     *   "timeout": 120000,
     *   "workdir": "/repo"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        // 参数 Schema 定义
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode command = MAPPER.createObjectNode();
        command.put("type", "string");
        command.put("description", "The command to execute");
        properties.set("command", command);

        ObjectNode timeout = MAPPER.createObjectNode();
        timeout.put("type", "number");
        timeout.put("description", "Optional timeout in milliseconds");
        properties.set("timeout", timeout);

        ObjectNode workdir = MAPPER.createObjectNode();
        workdir.put("type", "string");
        workdir.put("description", "The working directory to run the command in. Defaults to the current directory. Use this instead of 'cd' commands.");
        properties.set("workdir", workdir);

        ObjectNode description = MAPPER.createObjectNode();
        description.put("type", "string");
        description.put("description",
            "Clear, concise description of what this command does in 5-10 words. Examples:\n" +
            "Input: ls\nOutput: Lists files in current directory\n\n" +
            "Input: git status\nOutput: Shows working tree status\n\n" +
            "Input: npm install\nOutput: Installs package dependencies\n\n" +
            "Input: mkdir foo\nOutput: Creates directory 'foo'");
        properties.set("description", description);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("command");
        required.add("description");
        schema.set("required", required);

        return schema;
    }

    // ====================== 持久会话管理 ==================

    /**
     * 确保持久化 bash 进程存活。若未启动或已退出则重新创建（惰性初始化/自动重启）。
     * 调用方须持有内置锁。
     */
    private void ensureShellAlive() throws IOException, InterruptedException {
        if (closed) {
            throw new IllegalStateException("ShellTool has been closed");
        }
        if (shellProcess != null && shellProcess.isAlive()) {
            return;
        }
        // Previous process died or was never started — (re)create it.
        if (shellProcess != null) {
            log.info("Persistent bash process died (exit={}); restarting",
                     shellProcess.exitValue());
        }
        startShell();
    }

    /**
     * 启动新的 {@code /bin/bash} 进程及其读取线程。
     * 会阻塞至 shell 就绪（收到初始哨兵），或超时退出。调用方须持有内置锁。
     */
    private void startShell() throws IOException, InterruptedException {
        // 清理上一次会话的残留资源
        destroyShellResources();

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "--norc", "--noprofile");
        pb.redirectErrorStream(true);
        // 抑制提示符以避免污染命令输出
        pb.environment().put("PS1", "");
        pb.environment().put("PS2", "");
        pb.environment().put("PROMPT_COMMAND", "");

        shellProcess = pb.start();
        shellStdin = new BufferedWriter(
            new OutputStreamWriter(shellProcess.getOutputStream(), StandardCharsets.UTF_8));
        outputLines = new LinkedBlockingQueue<>();

        // Daemon thread that continuously reads lines from the process.
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(shellProcess.getInputStream(), StandardCharsets.UTF_8));
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.offer(line);
                }
            } catch (IOException e) {
                // Process died — nothing to do; ensureShellAlive() will restart.
                if (!closed) {
                    log.debug("Shell reader thread IOException: {}", e.getMessage());
                }
            }
        }, "shell-reader-" + shellProcess.pid());
        readerThread.setDaemon(true);
        readerThread.start();

        // 发送初始化哨兵并等待响应，确保 shell 已完全启动
        String initMarker = SENTINEL_PREFIX + "INIT__";
        shellStdin.write("echo \"" + initMarker + " 0\"");
        shellStdin.newLine();
        shellStdin.flush();

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            String line = outputLines.poll(remaining, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(initMarker)) {
                break;
            }
            // 丢弃其他启动噪声
        }
        log.debug("Persistent bash session started (pid={})", shellProcess.pid());
    }

    /**
     * 强制终止当前 bash 进程并中断读取线程。即使为 {@code null} 也可安全调用。
     * 调用方须持有内置锁。
     */
    private void destroyShellResources() {
        if (shellProcess != null) {
            shellProcess.destroyForcibly();
            try {
                shellProcess.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            shellProcess = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        shellStdin = null;
        outputLines = null;
    }

    // ====================== 危险命令检测 ======================

    /**
     * 检查命令是否为禁止命令。
     *
     * @param command 待检查的命令字符串
     * @return 命令被禁止时返回错误消息，允许执行时返回 {@code null}
     */
    private String checkBannedCommand(String command) {
        String trimmed = command.trim();
        String lower = trimmed.toLowerCase();

        for (String banned : BANNED_COMMANDS) {
            if (lower.startsWith(banned.toLowerCase())) {
                return "Command rejected: '" + trimmed + "' matches banned pattern '" + banned + "'. "
                    + "This command could cause irreversible damage to the system.";
            }
        }
        return null;
    }

    // ========================= 命令执行 ===========================

    /**
     * 在持久化 bash 会话中执行命令。
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验 {@code command} 参数和 {@code timeout}（默认 120 秒）</li>
     *   <li>对命令进行禁止列表检查（{@code rm -rf /}、{@code mkfs} 等），拦截高危命令</li>
     *   <li>通过 {@link ToolContext#permissionAsker()} 请求执行权限</li>
     *   <li>确保 bash 进程存活（惰性初始化/自动重启），通过哨兵标记判定命令结束</li>
     *   <li>若指定了 {@code workdir}，在该目录下执行命令</li>
     *   <li>逐行读取输出并通过 {@link ToolContext#progressReporter()} 上报进度</li>
     *   <li>输出超过 4000 行或 50KB 时截断，完整内容写入临时文件</li>
     *   <li>超时后销毁当前 bash 会话，下次调用自动重启</li>
     * </ol>
     *
     * <p>方法上有 {@code synchronized}：同一 ShellTool 实例的并发调用会串行执行。
     */
    @Override
    public synchronized ToolResult execute(JsonNode args, ToolContext context) {
        if (!args.has("command") || args.get("command").isNull()) {
            return ToolResult.error("command is required");
        }
        String command = args.get("command").asText();
        String workdir = args.has("workdir") && !args.get("workdir").isNull() ? args.get("workdir").asText() : null;
        String description = args.has("description") && !args.get("description").isNull() ? args.get("description").asText() : command;

        // 超时参数校验
        int timeoutMs;
        if (args.has("timeout") && !args.get("timeout").isNull()) {
            timeoutMs = args.get("timeout").asInt(DEFAULT_TIMEOUT_MS);
            if (timeoutMs < 0) {
                return ToolResult.error(
                    "Invalid timeout value: " + timeoutMs + ". Timeout must be a positive number.");
            }
        } else {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }

        // 禁止命令检查
        String bannedError = checkBannedCommand(command);
        if (bannedError != null) {
            return ToolResult.error(bannedError);
        }

        // 权限检查 —— Shell 命令执行前需要用户确认
        if (context.permissionAsker() != null) {
            boolean allowed = context.permissionAsker().ask("bash", command, description);
            if (!allowed) {
                return ToolResult.error("Permission denied for command: " + command);
            }
        }

        // 生成唯一哨兵标记，不会出现在真实命令输出中
        String marker = SENTINEL_PREFIX + UUID.randomUUID().toString().replace("-", "") + "__";

        try {
            ensureShellAlive();

            // ---- 构建命令载荷 --------------------------------
            // 工作目录管理
            StringBuilder payload = new StringBuilder();
            if (workdir != null) {
                payload.append("cd ").append(shellEscape(workdir)).append(" && ");
            }
            payload.append(command);

            // 上报初始进度元数据
            if (context.progressReporter() != null) {
                context.progressReporter().report(
                    description,
                    Map.of("output", "", "description", description)
                );
            }

            // 写入命令和哨兵标记
            boolean sentinelWritten = true;
            try {
                shellStdin.write(payload.toString());
                shellStdin.newLine();
                shellStdin.write("__harness_ec=$?; echo \"" + marker + " $__harness_ec\"");
                shellStdin.newLine();
                shellStdin.flush();
            } catch (IOException writeEx) {
                sentinelWritten = false;
                log.debug("Stdin write failed (process may have exited): {}",
                          writeEx.getMessage());
            }

            // ---- 读取输出直到收到哨兵或超时 --------------------
            StringBuilder output = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean foundSentinel = false;
            boolean processDied = false;
            boolean expired = false;

            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;

                String line = outputLines.poll(
                    Math.min(remaining, 200), TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (shellProcess != null && !shellProcess.isAlive()) {
                        drainRemaining(output);
                        processDied = true;
                        break;
                    }
                    if (!sentinelWritten) {
                        processDied = true;
                        break;
                    }
                    continue;
                }

                if (line.startsWith(marker)) {
                    foundSentinel = true;
                    break;
                }

                output.append(line).append('\n');

                // 上报执行进度
                if (context.progressReporter() != null) {
                    context.progressReporter().report(
                        "Running: " + description,
                        Map.of("output", output.toString(), "description", description)
                    );
                }
            }

            if (!foundSentinel && !processDied) {
                expired = true;
            }

            if (!foundSentinel) {
                // Kill the session so it auto-restarts on the next call.
                destroyShellResources();

                String partial = output.toString();
                if (partial.isEmpty()) {
                    partial = "(no output)";
                } else {
                    partial = truncateIfNeeded(partial);
                }

                if (processDied) {
                    return ToolResult.of(description, partial);
                }

                // 命令超时
                String meta = "\n\n<shell_metadata>\n" +
                    "shell tool terminated command after exceeding timeout "
                    + timeoutMs + " ms. If this command is expected to take longer "
                    + "and is not waiting for interactive input, retry with a larger "
                    + "timeout value in milliseconds.\n" +
                    "</shell_metadata>";

                return ToolResult.of(description, partial + meta);
            }

            String result = output.toString();
            if (result.isEmpty()) {
                result = "(no output)";
            } else {
                result = truncateIfNeeded(result);
            }

            return ToolResult.of(description, result);

        } catch (Exception e) {
            destroyShellResources();
            return ToolResult.error("Shell execution failed: " + e.getMessage());
        }
    }

    /**
     * 非阻塞地将输出队列中剩余行刷入构建器。
     */
    private void drainRemaining(StringBuilder sb) {
        if (outputLines == null) return;
        String line;
        while ((line = outputLines.poll()) != null) {
            sb.append(line).append('\n');
        }
    }

    /**
     * 对字符串进行单引号转义以安全地嵌入 bash 命令。
     */
    private static String shellEscape(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ========================= 输出截断 ===========================

    /**
     * 当输出超过行数或字节阈值时进行截断。
     * 完整输出写入临时文件，结果仅保留末尾部分。
     */
    private String truncateIfNeeded(String fullOutput) {
        byte[] bytes = fullOutput.getBytes(StandardCharsets.UTF_8);
        List<String> lines = fullOutput.lines().toList();

        boolean exceedsBytes = bytes.length > MAX_BYTES;
        boolean exceedsLines = lines.size() > MAX_LINES;

        if (!exceedsBytes && !exceedsLines) {
            return fullOutput;
        }

        // 将完整输出写入临时文件
        String tempFilePath;
        try {
            Path tempFile = Files.createTempFile("harness-shell-", ".txt");
            Files.write(tempFile, bytes);
            tempFilePath = tempFile.toAbsolutePath().toString();
        } catch (Exception e) {
            return fullOutput;
        }

        // 保留最后 N 行，不超过字节限制
        int maxLines = MAX_LINES;
        int maxBytes = MAX_BYTES;
        List<String> tailLines = new java.util.ArrayList<>();
        int tailBytes = 0;

        for (int i = lines.size() - 1; i >= 0 && tailLines.size() < maxLines; i--) {
            int lineBytes = lines.get(i).getBytes(StandardCharsets.UTF_8).length
                + (tailLines.isEmpty() ? 0 : 1);
            if (tailBytes + lineBytes > maxBytes) {
                if (tailLines.isEmpty()) {
                    // 单行过大，从末尾截取
                    byte[] lineBuf = lines.get(i).getBytes(StandardCharsets.UTF_8);
                    int start = Math.max(0, lineBuf.length - maxBytes);
                    // 对齐到 UTF-8 字符边界
                    while (start < lineBuf.length && (lineBuf[start] & 0xC0) == 0x80) start++;
                    tailLines.addFirst(new String(lineBuf, start, lineBuf.length - start, StandardCharsets.UTF_8));
                }
                break;
            }
            tailLines.addFirst(lines.get(i));
            tailBytes += lineBytes;
        }

        String tailText = String.join("\n", tailLines);

        // 添加截断标记和完整输出路径
        return "...output truncated...\n\nFull output saved to: " + tempFilePath + "\n\n" + tailText;
    }

    // ========================= 生命周期 ===================================

    /**
     * 关闭持久化 bash 会话。返回后此工具不可再用。
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        destroyShellResources();
        log.debug("ShellTool closed");
    }
}
