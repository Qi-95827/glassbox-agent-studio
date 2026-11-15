package dev.workbench.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** 解析受控角色到限额的映射，拒绝未知或重复角色。 */
public final class RoleBudgetMapDeserializer extends JsonDeserializer<Map<AgentRole, RoleBudget>> {
  @Override
  public Map<AgentRole, RoleBudget> deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    if (parser.currentToken() != JsonToken.START_OBJECT)
      throw JsonMappingException.from(parser, "角色限额策略必须是对象。");
    var result = new EnumMap<AgentRole, RoleBudget>(AgentRole.class);
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken() != JsonToken.FIELD_NAME)
        throw JsonMappingException.from(parser, "角色限额策略无效。");
      AgentRole role;
      try {
        role = AgentRole.valueOf(parser.currentName());
      } catch (IllegalArgumentException invalid) {
        throw JsonMappingException.from(parser, "角色限额包含未知角色。", invalid);
      }
      if (result.containsKey(role)) throw JsonMappingException.from(parser, "角色限额包含重复角色。");
      if (parser.nextToken() == JsonToken.VALUE_NULL)
        throw JsonMappingException.from(parser, "角色限额不能为 null。");
      RoleBudget budget = context.readValue(parser, RoleBudget.class);
      result.put(role, budget);
    }
    return Map.copyOf(result);
  }
}
