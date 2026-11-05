/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire serialization of requests and parsing of responses. Optional values that
 * are {@code null} are omitted.
 */
final class DecisionsWire {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final BigDecimal MAX_FLOAT_INTEGER = BigDecimal.valueOf(9007199254740991L);

    private enum ContentPosition {
        USER, DEVELOPER, ASSISTANT, TOOL, QUESTION
    }

    private DecisionsWire() {
    }

    static ObjectNode serialize(DecisionsRequest request) {
        if (request.getModel() == null || request.getModel().isEmpty()) {
            throw new IllegalArgumentException("DecisionsRequest.model is required.");
        }
        if (request.getInputText() == null && request.getInputItems() == null) {
            throw new IllegalArgumentException("DecisionsRequest.input is required.");
        }
        if (request.getQuestions() == null) {
            throw new IllegalArgumentException("DecisionsRequest.questions is required.");
        }
        ObjectNode out = NODES.objectNode();
        out.put("model", request.getModel());
        if (request.getInputText() != null) {
            out.put("input", request.getInputText());
        } else {
            ArrayNode input = out.putArray("input");
            for (DecisionsInputItem item : request.getInputItems()) {
                input.add(serializeItem(item));
            }
        }
        ArrayNode questions = out.putArray("questions");
        for (DecisionsQuestion question : request.getQuestions()) {
            questions.add(serializeQuestion(question));
        }
        putIfPresent(out, "prompt_cache_key", request.getPromptCacheKey());
        DecisionsPromptCacheOptions options = request.getPromptCacheOptions();
        if (options != null) {
            ObjectNode o = out.putObject("prompt_cache_options");
            putIfPresent(o, "mode", options.mode());
            putIfPresent(o, "ttl", options.ttl());
        }
        putIfPresent(out, "safety_identifier", request.getSafetyIdentifier());
        return out;
    }

    private static void putIfPresent(ObjectNode target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static JsonNode serializeContent(DecisionsContent<?> content, ContentPosition position) {
        if (content.text() != null) {
            return NODES.textNode(content.text());
        }
        ArrayNode parts = NODES.arrayNode();
        for (Object value : content.parts()) {
            if (!(value instanceof DecisionsContentPart part) || !allowedPart(part, position)) {
                throw new IllegalArgumentException("Invalid Decisions content part for this position.");
            }
            parts.add(serializePart(part, position != ContentPosition.QUESTION));
        }
        return parts;
    }

    private static boolean allowedPart(DecisionsContentPart part, ContentPosition position) {
        return switch (position) {
            case USER -> part instanceof DecisionsContentPart.UserPart;
            case DEVELOPER -> part instanceof DecisionsContentPart.DeveloperPart;
            case ASSISTANT -> part instanceof DecisionsContentPart.AssistantPart;
            case TOOL -> part instanceof DecisionsContentPart.ToolOutputPart;
            case QUESTION -> part instanceof DecisionsContentPart.QuestionPart;
        };
    }

    private static ObjectNode serializePart(DecisionsContentPart part, boolean includeBreakpoint) {
        ObjectNode out = NODES.objectNode();
        if (part instanceof DecisionsContentPart.InputText p) {
            out.put("type", "input_text");
            out.put("text", p.text());
            if (includeBreakpoint) {
                putBreakpoint(out, p.promptCacheBreakpoint());
            }
        } else if (part instanceof DecisionsContentPart.InputImage p) {
            out.put("type", "input_image");
            out.put("image_url", p.imageUrl());
            putIfPresent(out, "detail", p.detail());
            if (includeBreakpoint) {
                putBreakpoint(out, p.promptCacheBreakpoint());
            }
        } else if (part instanceof DecisionsContentPart.InputJson p) {
            out.put("type", "input_json");
            out.set("data", p.data());
        } else if (part instanceof DecisionsContentPart.OutputText p) {
            out.put("type", "output_text");
            out.put("text", p.text());
        } else if (part instanceof DecisionsContentPart.Refusal p) {
            out.put("type", "refusal");
            out.put("refusal", p.refusal());
        } else {
            throw new IllegalArgumentException("Unsupported Decisions content part.");
        }
        return out;
    }

    private static void putBreakpoint(ObjectNode out, DecisionsCacheBreakpoint breakpoint) {
        if (breakpoint != null) {
            out.putObject("prompt_cache_breakpoint").put("mode", breakpoint.mode());
        }
    }

    private static ObjectNode serializeMessage(boolean explicitType, String role, DecisionsContent<?> content) {
        ObjectNode out = NODES.objectNode();
        if (explicitType) {
            out.put("type", "message");
        }
        out.put("role", role);
        ContentPosition position = switch (role) {
            case "user" -> ContentPosition.USER;
            case "developer", "system" -> ContentPosition.DEVELOPER;
            case "assistant" -> ContentPosition.ASSISTANT;
            default -> throw new IllegalArgumentException("Unsupported Decisions message role.");
        };
        out.set("content", serializeContent(content, position));
        return out;
    }

    private static ObjectNode serializeItem(DecisionsInputItem item) {
        if (item instanceof DecisionsInputItem.Raw raw) {
            return raw.raw();
        }
        if (item instanceof DecisionsInputItem.UserMessage m) {
            return serializeMessage(m.explicitType(), "user", m.content());
        }
        if (item instanceof DecisionsInputItem.DeveloperMessage m) {
            return serializeMessage(m.explicitType(), m.role(), m.content());
        }
        if (item instanceof DecisionsInputItem.AssistantMessage m) {
            ObjectNode out = serializeMessage(m.explicitType(), "assistant", m.content());
            putIfPresent(out, "phase", m.phase());
            return out;
        }
        ObjectNode out = NODES.objectNode();
        if (item instanceof DecisionsInputItem.FunctionCall c) {
            out.put("type", "function_call");
            out.put("call_id", c.callId());
            out.put("name", c.name());
            out.put("arguments", c.arguments());
            putIfPresent(out, "id", c.id());
            putIfPresent(out, "namespace", c.namespace());
            putIfPresent(out, "status", c.status());
        } else if (item instanceof DecisionsInputItem.CustomToolCall c) {
            out.put("type", "custom_tool_call");
            out.put("call_id", c.callId());
            out.put("name", c.name());
            out.put("input", c.input());
            putIfPresent(out, "id", c.id());
            putIfPresent(out, "namespace", c.namespace());
            putIfPresent(out, "status", c.status());
        } else if (item instanceof DecisionsInputItem.FunctionCallOutput o) {
            out.put("type", "function_call_output");
            out.put("call_id", o.callId());
            out.set("output", serializeContent(o.output(), ContentPosition.TOOL));
            putIfPresent(out, "id", o.id());
            putIfPresent(out, "status", o.status());
        } else if (item instanceof DecisionsInputItem.CustomToolCallOutput o) {
            out.put("type", "custom_tool_call_output");
            out.put("call_id", o.callId());
            out.set("output", serializeContent(o.output(), ContentPosition.TOOL));
            putIfPresent(out, "id", o.id());
            putIfPresent(out, "status", o.status());
        } else {
            throw new IllegalArgumentException("Unsupported Decisions input item.");
        }
        return out;
    }

    private static ObjectNode serializeQuestion(DecisionsQuestion question) {
        ObjectNode out = NODES.objectNode();
        String type;
        if (question instanceof DecisionsQuestion.Predicate) {
            type = "predicate";
        } else if (question instanceof DecisionsQuestion.Choice) {
            type = "choice";
        } else if (question instanceof DecisionsQuestion.Score) {
            type = "score";
        } else {
            throw new IllegalArgumentException("Unsupported Decisions question.");
        }
        out.put("type", type);
        putIfPresent(out, "name", question.name());
        out.set("instructions", serializeContent(question.instructions(), ContentPosition.QUESTION));
        if (question instanceof DecisionsQuestion.Choice choice) {
            ArrayNode choices = out.putArray("choices");
            for (DecisionsQuestion.ChoiceOption option : choice.choices()) {
                ObjectNode c = choices.addObject();
                DecisionsChoiceValue value = option.value();
                if (value instanceof DecisionsChoiceValue.BooleanValue b) {
                    c.put("value", b.value());
                } else {
                    c.put("value", ((DecisionsChoiceValue.StringValue) value).value());
                }
                putIfPresent(c, "description", option.description());
            }
        } else if (question instanceof DecisionsQuestion.Score score) {
            ArrayNode levels = out.putArray("levels");
            for (DecisionsQuestion.ScoreLevel level : score.levels()) {
                ObjectNode l = levels.addObject();
                l.put("label", level.label());
                putIfPresent(l, "description", level.description());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    private static final class InvalidResponse extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidResponse(String message) {
            super(message, null, false, false);
        }
    }

    static DecisionsResult parseResult(JsonNode json) {
        try {
            if (json == null || !json.isObject()) {
                throw new InvalidResponse("Expected a JSON object.");
            }
            JsonNode usage = json.get("usage");
            if (usage == null || !usage.isObject()) {
                throw new InvalidResponse("Expected object 'usage'.");
            }
            JsonNode inputDetails = objectOrNull(usage.get("input_tokens_details"));
            JsonNode outputDetails = objectOrNull(usage.get("output_tokens_details"));
            List<DecisionsAnswer> answers = new ArrayList<>();
            for (JsonNode answer : arr(json, "answers")) {
                answers.add(parseAnswer((ObjectNode) answer));
            }
            String model = str(json, "model");
            DecisionsUsage parsedUsage = new DecisionsUsage(optionalCount(usage, "input_tokens"),
                    new DecisionsUsage.InputTokensDetails(optionalCount(inputDetails, "cached_tokens"),
                            optionalCount(inputDetails, "cache_write_tokens")),
                    optionalCount(usage, "output_tokens"),
                    new DecisionsUsage.OutputTokensDetails(optionalCount(outputDetails, "reasoning_tokens")),
                    optionalCount(usage, "total_tokens"));
            return new DecisionsResult(answers, model, parsedUsage);
        } catch (InvalidResponse e) {
            throw new DecisionsException(DecisionsErrorKind.INVALID_RESPONSE,
                    "The Decisions API returned an unexpected response: " + e.getMessage());
        }
    }

    private static JsonNode objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }

    private static double num(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new InvalidResponse("Expected number '" + key + "'.");
        }
        return value.doubleValue();
    }

    private static long optionalCount(JsonNode obj, String key) {
        if (obj == null) {
            return 0;
        }
        JsonNode value = obj.get(key);
        if (value == null) {
            return 0;
        }
        return integer(obj, key);
    }

    private static long integer(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        if (value == null || !value.isNumber()) {
            throw new InvalidResponse("Expected signed integer '" + key + "'.");
        }
        if (value.isIntegralNumber()) {
            if (value.canConvertToLong()) {
                return value.longValue();
            }
        } else {
            try {
                BigDecimal decimal = value.decimalValue();
                if (decimal.abs().compareTo(MAX_FLOAT_INTEGER) <= 0) {
                    return decimal.longValueExact();
                }
            } catch (ArithmeticException | NumberFormatException e) {
                throw new InvalidResponse("Expected signed integer '" + key + "'.");
            }
        }
        throw new InvalidResponse("Expected signed integer '" + key + "'.");
    }

    private static double probability(JsonNode obj, String key) {
        double value = num(obj, key);
        if (value < 0 || value > 1) {
            throw new InvalidResponse("Expected probability '" + key + "' between zero and one.");
        }
        return value;
    }

    private static String str(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        if (value == null || !value.isTextual()) {
            throw new InvalidResponse("Expected string '" + key + "'.");
        }
        return value.textValue();
    }

    private static List<JsonNode> arr(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        if (value == null || !value.isArray()) {
            throw new InvalidResponse("Expected array of objects '" + key + "'.");
        }
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new InvalidResponse("Expected array of objects '" + key + "'.");
            }
            items.add(item);
        }
        return items;
    }

    private static DecisionsChoiceValue choiceValue(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        if (value != null && value.isTextual()) {
            return new DecisionsChoiceValue.StringValue(value.textValue());
        }
        if (value != null && value.isBoolean()) {
            return new DecisionsChoiceValue.BooleanValue(value.booleanValue());
        }
        throw new InvalidResponse("Expected string or boolean '" + key + "'.");
    }

    private static String answerName(JsonNode obj) {
        JsonNode value = obj.get("name");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidResponse("Expected string 'name'.");
        }
        return value.textValue();
    }

    private static DecisionsAnswer parseAnswer(ObjectNode obj) {
        String type = str(obj, "type");
        String name = answerName(obj);
        switch (type) {
            case "predicate" :
                return new DecisionsAnswer.Predicate(name, probability(obj, "probability"));
            case "choice" : {
                DecisionsChoiceValue choice = choiceValue(obj, "choice");
                double confidence = probability(obj, "confidence");
                List<DecisionsAnswer.ChoiceProbability> probabilities = new ArrayList<>();
                for (JsonNode p : arr(obj, "probabilities")) {
                    probabilities.add(new DecisionsAnswer.ChoiceProbability(choiceValue(p, "value"),
                            probability(p, "probability")));
                }
                return new DecisionsAnswer.Choice(name, choice, confidence, probabilities);
            }
            case "score" : {
                double score = num(obj, "score");
                double confidence = probability(obj, "confidence");
                List<DecisionsAnswer.ScoreProbability> probabilities = new ArrayList<>();
                for (JsonNode p : arr(obj, "probabilities")) {
                    probabilities.add(new DecisionsAnswer.ScoreProbability(str(p, "label"), integer(p, "value"),
                            probability(p, "probability")));
                }
                return new DecisionsAnswer.Score(name, score, confidence, probabilities);
            }
            case "refusal" :
                return new DecisionsAnswer.Refusal(name);
            default :
                return new DecisionsAnswer.Unknown(name, type, obj);
        }
    }
}
