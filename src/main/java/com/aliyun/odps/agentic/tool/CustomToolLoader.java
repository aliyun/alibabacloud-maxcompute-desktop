package com.aliyun.odps.agentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.odps.agentic.tool.SkillParameter;
import com.aliyun.odps.agentic.tool.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads custom tool definitions from JSON files in the configured data directory.
 *
 * Each tool is defined as a JSON file with the following structure:
 * <pre>
 * {
 *   "name": "my_tool",
 *   "description": "What this tool does",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "param1": { "type": "string", "description": "..." }
 *     },
 *     "required": ["param1"]
 *   },
 *   "execution": {
 *     "type": "command",       // "command" | "http" | "script"
 *     "command": "python3",
 *     "args": ["my_script.py", "{{param1}}"],
 *     "cwd": "/path/to/dir",
 *     "timeout_ms": 30000
 *   }
 * }
 * </pre>
 *
 * Execution types:
 * - "command": runs a local process, passes parameters as template variables in args
 * - "http": makes an HTTP POST to a URL with parameters as JSON body
 * - "script": runs inline shell script with parameters as environment variables
 */
public class CustomToolLoader<C> {

    private static final Logger log = LoggerFactory.getLogger(CustomToolLoader.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_BYTES = 100 * 1024; // 100KB

    private String dataDir;

    private final java.util.function.Consumer<ContextTool<C,SkillResult>> register;
    private final java.util.function.Consumer<String> unregister;
    private final java.util.function.Function<String,ContextTool<C,SkillResult>> resolve;
    private final ObjectMapper objectMapper;
    private final Map<String, String> loadedTools = new ConcurrentHashMap<>();

    public CustomToolLoader(java.nio.file.Path dataDir,
        java.util.function.Consumer<ContextTool<C,SkillResult>> register,
        java.util.function.Consumer<String> unregister,
        java.util.function.Function<String,ContextTool<C,SkillResult>> resolve,ObjectMapper mapper) {
        this.dataDir=dataDir.toString(); this.register=register; this.unregister=unregister;
        this.resolve=resolve; this.objectMapper=mapper;
    }

    public void init() {
        loadTools();
    }

    /**
     * Load (or reload) all custom tools from the tools directory.
     */
    public int loadTools() {
        // Unregister previously loaded tools
        for (String name : loadedTools.keySet()) {
            unregister.accept(name);
        }
        loadedTools.clear();

        Path toolsDir = Path.of(dataDir, "tools");
        if (!Files.isDirectory(toolsDir)) {
            log.info("[CustomToolLoader] No tools directory at {}", toolsDir);
            return 0;
        }

        int count = 0;
        try (var stream = Files.list(toolsDir)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();

            for (Path file : files) {
                try {
                    loadToolFromFile(file);
                    count++;
                } catch (Exception e) {
                    log.warn("[CustomToolLoader] Failed to load tool from {}: {}",
                        file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[CustomToolLoader] Failed to scan tools directory: {}", e.getMessage());
        }

        log.info("[CustomToolLoader] Loaded {} custom tool(s) from {}", count, toolsDir);
        return count;
    }

    /**
     * List all loaded custom tools.
     */
    public List<Map<String, Object>> listLoadedTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : loadedTools.entrySet()) {
            ContextTool<C,SkillResult> skill = resolve.apply(entry.getKey());
            if (skill != null) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("name", entry.getKey());
                view.put("description", skill.getDescription());
                view.put("source", entry.getValue());
                result.add(view);
            }
        }
        return result;
    }

    public Path getToolsDirectory() {
        return Path.of(dataDir, "tools");
    }

    private void loadToolFromFile(Path file) throws Exception {
        JsonNode def = objectMapper.readTree(file.toFile());

        String name = def.has("name") ? def.get("name").asText() : null;
        if (name == null || name.isBlank()) {
            name = file.getFileName().toString().replace(".json", "");
        }
        String toolName = "custom_" + name;

        String description = def.has("description") ? def.get("description").asText() : "Custom tool: " + name;
        JsonNode parametersNode = def.get("parameters");
        JsonNode executionNode = def.get("execution");

        if (executionNode == null) {
            throw new IllegalArgumentException("Missing 'execution' field");
        }

        SkillParameter[] params = extractParameters(parametersNode);
        ExecutionConfig execConfig = parseExecution(executionNode);

        ContextTool<C,SkillResult> skill = new CustomToolSkill<>(toolName, description, params, execConfig, objectMapper);
        register.accept(skill);
        loadedTools.put(toolName, file.getFileName().toString());
    }

    private SkillParameter[] extractParameters(JsonNode schema) {
        if (schema == null || !schema.has("properties")) {
            return new SkillParameter[0];
        }
        JsonNode properties = schema.get("properties");
        Set<String> required = new HashSet<>();
        if (schema.has("required") && schema.get("required").isArray()) {
            for (JsonNode r : schema.get("required")) required.add(r.asText());
        }

        List<SkillParameter> params = new ArrayList<>();
        var fields = properties.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String pName = entry.getKey();
            JsonNode prop = entry.getValue();
            String type = prop.has("type") ? prop.get("type").asText() : "string";
            String desc = prop.has("description") ? prop.get("description").asText() : "";
            params.add(new SkillParameter(pName, type, desc, required.contains(pName)));
        }
        return params.toArray(new SkillParameter[0]);
    }

    private ExecutionConfig parseExecution(JsonNode node) {
        ExecutionConfig config = new ExecutionConfig();
        config.type = node.has("type") ? node.get("type").asText() : "command";
        config.command = node.has("command") ? node.get("command").asText() : null;
        config.url = node.has("url") ? node.get("url").asText() : null;
        config.script = node.has("script") ? node.get("script").asText() : null;
        config.cwd = node.has("cwd") ? node.get("cwd").asText() : null;
        config.timeoutMs = node.has("timeout_ms") ? node.get("timeout_ms").asLong() : DEFAULT_TIMEOUT_MS;

        if (node.has("args") && node.get("args").isArray()) {
            config.args = new ArrayList<>();
            for (JsonNode arg : node.get("args")) {
                config.args.add(arg.asText());
            }
        }
        if (node.has("env") && node.get("env").isObject()) {
            config.env = new LinkedHashMap<>();
            var fields = node.get("env").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                config.env.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return config;
    }

    // --- Execution Config ---

    private static class ExecutionConfig {
        String type;        // "command" | "http" | "script"
        String command;
        List<String> args;
        String url;
        String script;
        String cwd;
        long timeoutMs;
        Map<String, String> env;
    }

    // --- Custom Tool Skill ---

    private static class CustomToolSkill<C> implements ContextTool<C,SkillResult> {
        private final String name;
        private final String description;
        private final SkillParameter[] parameters;
        private final ExecutionConfig execConfig;
        private final ObjectMapper objectMapper;

        CustomToolSkill(String name, String description, SkillParameter[] parameters,
                        ExecutionConfig execConfig, ObjectMapper objectMapper) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.execConfig = execConfig;
            this.objectMapper = objectMapper;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public SkillParameter[] getParameters() { return parameters; }

        @Override
        public SkillResult execute(Map<String, Object> args, C context) {
            try {
                return switch (execConfig.type) {
                    case "command" -> executeCommand(args);
                    case "http" -> executeHttp(args);
                    case "script" -> executeScript(args);
                    default -> SkillResult.failure("Unknown execution type: " + execConfig.type);
                };
            } catch (Exception e) {
                return SkillResult.failure("Custom tool execution failed: " + e.getMessage());
            }
        }

        private SkillResult executeCommand(Map<String, Object> args) throws Exception {
            List<String> cmd = new ArrayList<>();
            cmd.add(execConfig.command);
            if (execConfig.args != null) {
                for (String arg : execConfig.args) {
                    cmd.add(templateReplace(arg, args));
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (execConfig.cwd != null) pb.directory(new File(execConfig.cwd));
            if (execConfig.env != null) pb.environment().putAll(execConfig.env);
            // Pass args as env vars too
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                pb.environment().put("TOOL_" + entry.getKey().toUpperCase(),
                    String.valueOf(entry.getValue()));
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
            boolean finished = process.waitFor(execConfig.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return SkillResult.failure("Command timed out after " + execConfig.timeoutMs + "ms");
            }

            String result = new String(output).trim();
            return process.exitValue() == 0
                ? SkillResult.success(result)
                : SkillResult.failure("Exit code " + process.exitValue() + ": " + result);
        }

        private SkillResult executeHttp(Map<String, Object> args) throws Exception {
            String targetUrl = templateReplace(execConfig.url, args);
            String body = objectMapper.writeValueAsString(args);

            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(targetUrl))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofMillis(execConfig.timeoutMs))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();

            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return SkillResult.success(response.body());
            }
            return SkillResult.failure("HTTP " + response.statusCode() + ": " + response.body());
        }

        private SkillResult executeScript(Map<String, Object> args) throws Exception {
            String script = templateReplace(execConfig.script, args);
            List<String> cmd = List.of("/bin/sh", "-c", script);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (execConfig.cwd != null) pb.directory(new File(execConfig.cwd));
            if (execConfig.env != null) pb.environment().putAll(execConfig.env);
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                pb.environment().put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
            boolean finished = process.waitFor(execConfig.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return SkillResult.failure("Script timed out after " + execConfig.timeoutMs + "ms");
            }

            String result = new String(output).trim();
            return process.exitValue() == 0
                ? SkillResult.success(result)
                : SkillResult.failure("Exit code " + process.exitValue() + ": " + result);
        }

        private static String templateReplace(String template, Map<String, Object> args) {
            if (template == null) return "";
            String result = template;
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
            return result;
        }
    }
}
