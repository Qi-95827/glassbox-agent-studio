package dev.workbench.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** 工具限额使用受控枚举和整数，拒绝重复或未知工具。 */
public final class ToolBudgetMapDeserializer extends JsonDeserializer<Map<ToolKind, Integer>> {
  @Override
  public Map<ToolKind, Integer> deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    if (parser.currentToken() != JsonToken.START_OBJECT)
      throw JsonMappingException.from(parser, "工具限额策略必须是对象。");
    var result = new EnumMap<ToolKind, Integer>(ToolKind.class);
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken() != JsonToken.FIELD_NAME)
        throw JsonMappingException.from(parser, "工具限额策略无效。");
      ToolKind kind;
      try {
        kind = ToolKind.valueOf(parser.currentName());
      } catch (IllegalArgumentException invalid) {
        throw JsonMappingException.from(parser, "工具限额包含未知工具。", invalid);
      }
      if (result.containsKey(kind)) throw JsonMappingException.from(parser, "工具限额包含重复工具。");
      if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT)
        throw JsonMappingException.from(parser, "工具调用上限必须为整数。");
      int limit = parser.getIntValue();
      if (limit < 0 || limit > 20) throw JsonMappingException.from(parser, "工具调用上限须在 0 至 20 之间。");
      result.put(kind, limit);
    }
    return Map.copyOf(result);
  }
}
