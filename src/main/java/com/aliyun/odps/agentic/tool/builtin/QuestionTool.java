package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 内置提问工具，用于在执行过程中向用户发起问题。
 * 支持批量问题、选项列表以及自动回答回退逻辑。
 */
public class QuestionTool implements ToolDef {

    private static final String ID = "question";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile Function<QuestionRequest, QuestionResponse> questionHandler;

    /**
     * 创建不带问题处理器的提问工具。
     */
    public QuestionTool() {
        this(null);
    }

    /**
     * 创建提问工具并设置问题处理器。
     *
     * @param questionHandler 问题处理器
     */
    public QuestionTool(Function<QuestionRequest, QuestionResponse> questionHandler) {
        this.questionHandler = questionHandler;
    }

    /**
     * 设置问题处理器。
     *
     * @param handler 问题处理器
     */
    public void setQuestionHandler(Function<QuestionRequest, QuestionResponse> handler) {
        this.questionHandler = handler;
    }

    /**
     * 返回工具 ID。
     */
    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        return ResourceLoader.load("tools/question.txt");
    }

    /**
     * 返回 question 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "questions": [
     *     {
     *       "question": "Which database should we use?",
     *       "header": "Database",
     *       "options": [
     *         {"label": "PostgreSQL", "description": "Relational database"},
     *         {"label": "MongoDB",    "description": "Document store"}
     *       ]
     *     }
     *   ]
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode questions = MAPPER.createObjectNode();
        questions.put("type", "array");
        questions.put("description", "Questions to ask");
        ObjectNode questionItem = MAPPER.createObjectNode();
        questionItem.put("type", "object");
        ObjectNode qProps = MAPPER.createObjectNode();

        ObjectNode question = MAPPER.createObjectNode();
        question.put("type", "string");
        question.put("description", "Complete question");
        qProps.set("question", question);

        ObjectNode header = MAPPER.createObjectNode();
        header.put("type", "string");
        header.put("description", "Very short label (max 30 chars)");
        qProps.set("header", header);

        ObjectNode options = MAPPER.createObjectNode();
        options.put("type", "array");
        options.put("description", "Available choices");
        ObjectNode optionItem = MAPPER.createObjectNode();
        optionItem.put("type", "object");
        ObjectNode optProps = MAPPER.createObjectNode();
        ObjectNode label = MAPPER.createObjectNode();
        label.put("type", "string");
        label.put("description", "Display text (1-5 words, concise)");
        optProps.set("label", label);
        ObjectNode desc = MAPPER.createObjectNode();
        desc.put("type", "string");
        desc.put("description", "Explanation of choice");
        optProps.set("description", desc);
        optionItem.set("properties", optProps);
        ArrayNode optReq = MAPPER.createArrayNode();
        optReq.add("label");
        optReq.add("description");
        optionItem.set("required", optReq);
        options.set("items", optionItem);
        qProps.set("options", options);

        ObjectNode multiple = MAPPER.createObjectNode();
        multiple.put("type", "boolean");
        multiple.put("description", "Allow selecting multiple choices");
        qProps.set("multiple", multiple);

        questionItem.set("properties", qProps);
        ArrayNode qReq = MAPPER.createArrayNode();
        qReq.add("question");
        qReq.add("header");
        qReq.add("options");
        questionItem.set("required", qReq);
        questions.set("items", questionItem);
        properties.set("questions", questions);

        schema.set("properties", properties);
        ArrayNode required = MAPPER.createArrayNode();
        required.add("questions");
        schema.set("required", required);

        return schema;
    }

    /**
     * 向用户发起问题并收集答案。
     *
     * <p>执行流程：
     * <ol>
     *   <li>从 {@code args.questions} 数组中解析每个问题的文本、标题和选项列表</li>
     *   <li>若已注册 {@code questionHandler}，将所有问题封装为 {@link QuestionRequest} 交给处理器，
     *       由处理器负责展示问题和收集用户选择</li>
     *   <li>若未注册处理器，自动以每题的第一个选项作为默认答案（无阻塞回退）</li>
     *   <li>将所有答案格式化为 {@code "问题"="答案"} 的文本返回给 LLM</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        JsonNode questionsNode = args.get("questions");
        if (questionsNode == null || !questionsNode.isArray()) {
            return ToolResult.error("Invalid questions format");
        }

        List<SingleQuestion> questions = new ArrayList<>();
        for (JsonNode qNode : questionsNode) {
            String questionText = qNode.has("question") ? qNode.get("question").asText() : "";
            String header = qNode.has("header") ? qNode.get("header").asText() : "";
            List<String> optionLabels = new ArrayList<>();
            if (qNode.has("options") && qNode.get("options").isArray()) {
                for (JsonNode opt : qNode.get("options")) {
                    optionLabels.add(opt.has("label") ? opt.get("label").asText() : "");
                }
            }
            questions.add(new SingleQuestion(questionText, header, optionLabels));
        }

        if (questionHandler != null) {
            QuestionResponse response = questionHandler.apply(new QuestionRequest(questions));
            StringBuilder sb = new StringBuilder("User has answered your questions:");
            for (int i = 0; i < questions.size(); i++) {
                String answer = i < response.answers().size() ? response.answers().get(i) : "Unanswered";
                sb.append(" \"").append(questions.get(i).question()).append("\"=\"").append(answer).append("\"");
                if (i < questions.size() - 1) sb.append(",");
            }
            sb.append(". You can now continue with the user's answers in mind.");
            return ToolResult.of("Question answered", sb.toString());
        }

        StringBuilder sb = new StringBuilder("User has answered your questions:");
        for (SingleQuestion q : questions) {
            String defaultAnswer = q.options().isEmpty() ? "Approved" : q.options().getFirst();
            sb.append(" \"").append(q.question()).append("\"=\"").append(defaultAnswer).append("\"");
        }
        sb.append(". You can now continue with the user's answers in mind.");
        return ToolResult.of("Question auto-answered", sb.toString());
    }

    /**
     * 单个问题定义。
     *
     * @param question 问题正文
     * @param header   问题短标题
     * @param options  可选项标签列表
     */
    public record SingleQuestion(String question, String header, List<String> options) {}

    /**
     * 提问请求。
     *
     * @param questions 待提问的问题列表
     */
    public record QuestionRequest(List<SingleQuestion> questions) {}

    /**
     * 提问响应。
     *
     * @param answers 用户给出的答案列表
     */
    public record QuestionResponse(List<String> answers) {}
}
