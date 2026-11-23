package dev.workbench.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.evaluation.dataset.SampleJson;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.ownership.OwnershipService;
import dev.workbench.persistence.entity.EvalCustomDatasetEntity;
import dev.workbench.persistence.entity.EvalCustomSampleEntity;
import dev.workbench.persistence.repository.EvalCustomDatasetRepository;
import dev.workbench.persistence.repository.EvalCustomSampleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户自定义评测集。
 *
 * <p>每条数据集与样本都按 {@link OwnershipService} 的当前账号隔离；所有列表、按 ID 读写与开跑解析
 * 都先校验 owner，绝不把请求体里的用户标识当作授权依据。
 */
@Service
public class EvalCustomDatasetService {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final EvalCustomDatasetRepository datasets;
  private final EvalCustomSampleRepository samples;
  private final OwnershipService ownership;

  public EvalCustomDatasetService(
      EvalCustomDatasetRepository datasets,
      EvalCustomSampleRepository samples,
      OwnershipService ownership) {
    this.datasets = datasets;
    this.samples = samples;
    this.ownership = ownership;
  }

  public record DatasetInput(String code, String name, String category, String description) {}

  public record DatasetView(
      String id,
      String code,
      String name,
      String category,
      String description,
      int sampleCount,
      String createdAt,
      String updatedAt) {}

  public record SampleInput(
      String sampleKey,
      String titleZh,
      String titleEn,
      String prompt,
      Object setup,
      Object assertions,
      Object judgeRubric) {}

  public record SampleView(
      String id,
      String sampleKey,
      String titleZh,
      String titleEn,
      String prompt,
      Map<String, Object> setup,
      List<Map<String, Object>> assertions,
      List<String> judgeRubric,
      String updatedAt) {}

  public record ImportResult(String datasetId, int imported, int total) {}

  @Transactional
  public DatasetView create(DatasetInput input) {
    if (input == null) throw new IllegalArgumentException("评测集不能为空。");
    String code = code(input.code());
    String owner = owner();
    if (datasets.existsByOwnerScopeKeyAndCode(owner, code))
      throw new IllegalArgumentException("当前账号已存在同名评测集编码：" + code);
    String name = required(input.name(), 120, "评测集名称");
    EvalCategory category = EvalCategory.parse(input.category());
    String now = dev.workbench.domain.WorkbenchTime.now();
    var saved =
        datasets.saveAndFlush(
            new EvalCustomDatasetEntity(
                dev.workbench.domain.WorkbenchIds.newId(),
                owner,
                code,
                name,
                category.name(),
                optional(input.description(), 500, "说明"),
                now,
                now));
    return view(saved);
  }

  @Transactional
  public DatasetView update(String id, DatasetInput input) {
    var row = requireDataset(id);
    String name = required(input == null ? null : input.name(), 120, "评测集名称");
    EvalCategory category = EvalCategory.parse(input.category());
    row.update(
        name,
        category.name(),
        optional(input.description(), 500, "说明"),
        dev.workbench.domain.WorkbenchTime.now());
    return view(datasets.save(row));
  }

  @Transactional
  public void delete(String id) {
    var row = requireDataset(id);
    samples.deleteAll(samples.findByDatasetIdOrderBySampleKeyAsc(row.getId()));
    datasets.delete(row);
  }

  public List<DatasetView> list() {
    return datasets.findByOwnerScopeKeyOrderByUpdatedAtDesc(owner()).stream()
        .map(row -> view(row))
        .toList();
  }

  public List<SampleView> samples(String datasetId) {
    var dataset = requireDataset(datasetId);
    return samples.findByDatasetIdOrderBySampleKeyAsc(dataset.getId()).stream()
        .map(EvalCustomDatasetService::view)
        .toList();
  }

  @Transactional
  public SampleView upsertSample(String datasetId, String sampleKey, SampleInput input) {
    var dataset = requireDataset(datasetId);
    String key = sampleKey(sampleKey == null ? input.sampleKey() : sampleKey);
    String prompt = required(input.prompt(), 20_000, "题目输入");
    String setupJson = jsonObject(input.setup(), "setup");
    String assertionsJson = jsonList(input.assertions(), "assertions");
    String rubricJson = jsonStringList(input.judgeRubric(), "judgeRubric");
    String now = dev.workbench.domain.WorkbenchTime.now();
    var existing = samples.findByDatasetIdAndSampleKey(dataset.getId(), key).orElse(null);
    if (existing == null) {
      var saved =
          samples.saveAndFlush(
              new EvalCustomSampleEntity(
                  dev.workbench.domain.WorkbenchIds.newId(),
                  dataset.getId(),
                  key,
                  optional(input.titleZh(), 200, "中文标题"),
                  optional(input.titleEn(), 200, "英文标题"),
                  prompt,
                  setupJson,
                  assertionsJson,
                  rubricJson,
                  now,
                  now));
      touch(dataset);
      return view(saved);
    }
    existing.update(
        optional(input.titleZh(), 200, "中文标题"),
        optional(input.titleEn(), 200, "英文标题"),
        prompt,
        setupJson,
        assertionsJson,
        rubricJson,
        now);
    touch(dataset);
    return view(samples.save(existing));
  }

  @Transactional
  public void deleteSample(String datasetId, String sampleKey) {
    var dataset = requireDataset(datasetId);
    var row =
        samples
            .findByDatasetIdAndSampleKey(dataset.getId(), sampleKey(sampleKey))
            .orElseThrow(() -> new IllegalArgumentException("样本不存在。"));
    samples.delete(row);
    touch(dataset);
  }

  @Transactional
  public ImportResult importJsonl(String datasetId, String jsonl) {
    var dataset = requireDataset(datasetId);
    if (jsonl == null || jsonl.isBlank()) throw new IllegalArgumentException("导入内容为空。");
    var category = EvalCategory.parse(dataset.getCategory());
    int imported = 0;
    int index = 0;
    for (String line : SampleJson.splitJsonl(jsonl)) {
      index++;
      if (index > 500) throw new IllegalArgumentException("单个数据集单次最多导入 500 题。");
      var sample =
          SampleJson.parseLine(line, category, "custom:" + dataset.getId(), dataset.getCode() + "#" + index);
      String key = safeKey(sample.id());
      upsertSample(
          dataset.getId(),
          key,
          new SampleInput(
              key,
              sample.titleZh(),
              sample.titleEn(),
              sample.prompt(),
              sample.setup(),
              sample.assertions(),
              sample.judgeRubric()));
      imported++;
    }
    touch(dataset);
    return new ImportResult(dataset.getId(), imported, (int) samples.countByDatasetId(dataset.getId()));
  }

  /** 导出为标准 JSONL，可直接分享或再导入。 */
  public String exportJsonl(String datasetId) {
    var dataset = requireDataset(datasetId);
    var builder = new StringBuilder();
    for (var row : samples.findByDatasetIdOrderBySampleKeyAsc(dataset.getId())) {
      var map = new LinkedHashMap<String, Object>();
      map.put("id", row.getSampleKey());
      map.put("category", dataset.getCategory());
      map.put("sourcePack", "custom:" + dataset.getId());
      map.put("titleZh", row.getTitleZh());
      map.put("titleEn", row.getTitleEn());
      map.put("prompt", row.getPrompt());
      map.put("setup", readMap(row.getSetupJson()));
      map.put("assertions", readList(row.getAssertionsJson()));
      map.put("judgeRubric", readStrings(row.getRubricJson()));
      try {
        builder.append(JSON.writeValueAsString(map)).append('\n');
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("导出样本失败。", e);
      }
    }
    return builder.toString();
  }

  public List<EvalSample> samplesForRun(String datasetId) {
    var dataset = requireDataset(datasetId);
    var category = EvalCategory.parse(dataset.getCategory());
    var result = new ArrayList<EvalSample>();
    for (var row : samples.findByDatasetIdOrderBySampleKeyAsc(dataset.getId())) {
      result.add(
          new EvalSample(
              row.getSampleKey(),
              category,
              "custom:" + dataset.getId(),
              row.getTitleZh() == null ? row.getSampleKey() : row.getTitleZh(),
              row.getTitleEn() == null ? row.getTitleZh() : row.getTitleEn(),
              row.getPrompt(),
              readMap(row.getSetupJson()),
              readList(row.getAssertionsJson()),
              readStrings(row.getRubricJson())));
    }
    if (result.isEmpty()) throw new IllegalStateException("自定义评测集没有样本，请先编辑或导入。");
    return List.copyOf(result);
  }

  public DatasetView requireView(String id) {
    return view(requireDataset(id));
  }

  private EvalCustomDatasetEntity requireDataset(String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("缺少评测集标识。");
    return datasets
        .findByIdAndOwnerScopeKey(id, owner())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private void touch(EvalCustomDatasetEntity dataset) {
    dataset.update(
        dataset.getName(),
        dataset.getCategory(),
        dataset.getDescription(),
        dev.workbench.domain.WorkbenchTime.now());
    datasets.save(dataset);
  }

  private String owner() {
    return ownership.scope().ownerScopeKey();
  }

  private DatasetView view(EvalCustomDatasetEntity row) {
    return new DatasetView(
        row.getId(),
        row.getCode(),
        row.getName(),
        row.getCategory(),
        row.getDescription(),
        (int) samples.countByDatasetId(row.getId()),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private static SampleView view(EvalCustomSampleEntity row) {
    return new SampleView(
        row.getId(),
        row.getSampleKey(),
        row.getTitleZh(),
        row.getTitleEn(),
        row.getPrompt(),
        readMap(row.getSetupJson()),
        readList(row.getAssertionsJson()),
        readStrings(row.getRubricJson()),
        row.getUpdatedAt());
  }

  private static Map<String, Object> readMap(String value) {
    try {
      return JSON.readValue(value == null ? "{}" : value, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private static List<Map<String, Object>> readList(String value) {
    try {
      return JSON.readValue(
          value == null ? "[]" : value, new TypeReference<List<Map<String, Object>>>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private static List<String> readStrings(String value) {
    try {
      return JSON.readValue(value == null ? "[]" : value, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private static String jsonObject(Object value, String field) {
    if (value == null) return "{}";
    if (!(value instanceof Map<?, ?>))
      throw new IllegalArgumentException(field + " 必须是 JSON 对象。");
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(field + " 无法序列化。", e);
    }
  }

  private static String jsonList(Object value, String field) {
    if (value == null) return "[]";
    if (!(value instanceof List<?>))
      throw new IllegalArgumentException(field + " 必须是 JSON 数组。");
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(field + " 无法序列化。", e);
    }
  }

  private static String jsonStringList(Object value, String field) {
    if (value == null) return "[]";
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException(field + " 必须是 JSON 数组。");
    var strings = new ArrayList<String>();
    for (Object item : list) if (item != null && !String.valueOf(item).isBlank()) strings.add(String.valueOf(item));
    try {
      return JSON.writeValueAsString(strings);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(field + " 无法序列化。", e);
    }
  }

  private static String code(String value) {
    if (value == null || !value.strip().toUpperCase(Locale.ROOT).matches("[A-Z0-9_-]{1,40}"))
      throw new IllegalArgumentException("评测集编码须为 1 至 40 位字母、数字、下划线或连字符。");
    return value.strip().toUpperCase(Locale.ROOT);
  }

  private static String sampleKey(String value) {
    if (value == null || !value.strip().matches("[A-Za-z0-9._-]{1,80}"))
      throw new IllegalArgumentException("样本标识须为 1 至 80 位字母、数字、点、下划线或连字符。");
    return value.strip();
  }

  private static String safeKey(String value) {
    String text = value == null ? "" : value.strip();
    String cleaned = text.replaceAll("[^A-Za-z0-9._-]", "-");
    if (cleaned.isBlank()) cleaned = "sample";
    return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
  }

  private static String required(String value, int limit, String name) {
    if (value == null || value.isBlank() || value.strip().length() > limit)
      throw new IllegalArgumentException(name + "须为 1 至 " + limit + " 字符。");
    return value.strip();
  }

  private static String optional(String value, int limit, String name) {
    if (value == null || value.isBlank()) return null;
    if (value.strip().length() > limit) throw new IllegalArgumentException(name + "超过 " + limit + " 字符。");
    return value.strip();
  }
}
