package dev.workbench.evaluation.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** BFCL Python Schema 的类型词汇涵盖 dict、tuple、float 和 any。 */
final class BfclArgumentSchema {
  private BfclArgumentSchema() {}

  static void validate(JsonNode schema, JsonNode value) {
    if (!valid(schema, value)) throw new IllegalArgumentException("BFCL 参数类型或必选字段不匹配。");
  }

  private static boolean valid(JsonNode schema, JsonNode value) {
    if (!schema.isObject()) return schema.isBoolean() && schema.asBoolean();
    var allowed = schema.path("enum");
    if (allowed.isArray() && allowed.valueStream().noneMatch(value::equals)) return false;
    var type = schema.path("type");
    boolean typeMatches =
        type.isArray()
            ? type.valueStream().anyMatch(entry -> matches(entry.asText(), value))
            : matches(type.asText("any"), value);
    if (!typeMatches) return false;
    if (value.isObject()) {
      for (var name : AgentEvalCase.strings(schema.path("required")))
        if (!value.has(name)) return false;
      var properties = schema.path("properties");
      var entries = value.fields();
      while (entries.hasNext()) {
        var entry = entries.next();
        var rule = properties.path(entry.getKey());
        if (rule.isMissingNode()) rule = schema.path("additionalProperties");
        if (!rule.isMissingNode() && !valid(rule, entry.getValue())) return false;
      }
    }
    var items = schema.path("items");
    if (value.isArray() && items.isObject())
      return value.valueStream().allMatch(item -> valid(items, item));
    if (value.isArray() && items.isArray()) {
      if (value.size() != items.size()) return false;
      for (int i = 0; i < items.size(); i++) if (!valid(items.get(i), value.get(i))) return false;
    }
    return true;
  }

  private static boolean matches(String type, JsonNode value) {
    return switch (type) {
      case "object", "dict" -> value.isObject();
      case "array", "tuple", "list" -> value.isArray();
      case "integer", "int" -> value.isIntegralNumber();
      case "number", "float" -> value.isNumber();
      case "string", "str" -> value.isTextual();
      case "boolean", "bool" -> value.isBoolean();
      case "null" -> value.isNull();
      case "any" -> !value.isMissingNode();
      default -> false;
    };
  }

  static JsonNode fixture(JsonNode schema) {
    var allowed = schema.path("enum");
    if (allowed.isArray() && !allowed.isEmpty()) return allowed.get(0);
    if (schema.has("default")) return schema.get("default");
    var nodes = JsonNodeFactory.instance;
    return switch (schema.path("type").asText("any")) {
      case "object", "dict" -> {
        var object = nodes.objectNode();
        for (String name : AgentEvalCase.strings(schema.path("required")))
          object.set(name, fixture(schema.path("properties").path(name)));
        yield object;
      }
      case "array", "list", "tuple" -> {
        var array = nodes.arrayNode();
        if (schema.path("items").isArray())
          schema.path("items").forEach(item -> array.add(fixture(item)));
        yield array;
      }
      case "string", "str" -> nodes.textNode("fixture");
      case "boolean", "bool" -> nodes.booleanNode(false);
      case "integer", "int", "number", "float" -> nodes.numberNode(1);
      default -> nodes.nullNode();
    };
  }
}
