package dev.workbench.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** 角色限额必须使用明确字段和整数，拒绝重复字段与隐式数值转换。 */
public final class RoleBudgetDeserializer extends JsonDeserializer<RoleBudget> {
  private static final Set<String> FIELDS = Set.of("maxModelCalls", "maxToolCalls");

  @Override
  public RoleBudget deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    if (parser.currentToken() != JsonToken.START_OBJECT)
      throw JsonMappingException.from(parser, "角色限额必须是对象。");
    Integer models = null;
    Integer tools = null;
    var seen = new HashSet<String>();
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken() != JsonToken.FIELD_NAME)
        throw JsonMappingException.from(parser, "角色限额字段无效。");
      String field = parser.currentName();
      if (!FIELDS.contains(field) || !seen.add(field))
        throw JsonMappingException.from(parser, "角色限额包含未知或重复字段。");
      JsonToken value = parser.nextToken();
      Integer parsed;
      if (value == JsonToken.VALUE_NULL) parsed = null;
      else if (value == JsonToken.VALUE_NUMBER_INT) parsed = parser.getIntValue();
      else throw JsonMappingException.from(parser, "角色调用上限必须为整数。");
      if (field.equals("maxModelCalls")) models = parsed;
      else tools = parsed;
    }
    try {
      return new RoleBudget(models, tools);
    } catch (IllegalArgumentException invalid) {
      throw JsonMappingException.from(parser, invalid.getMessage(), invalid);
    }
  }
}
