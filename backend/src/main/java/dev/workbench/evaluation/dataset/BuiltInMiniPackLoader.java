package dev.workbench.evaluation.dataset;

import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalSample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 仓库内置精选微集加载器。
 *
 * <p>只读取 classpath 下的 {@code eval-packs/mini-*.jsonl}；零网络依赖，克隆仓库即可开跑。
 * 单行解析失败直接失败，避免缺题后静默给出虚高分数。
 */
@Component
public class BuiltInMiniPackLoader {
  public static final String SOURCE_MINI = "mini";
  private static final String PATTERN = "classpath:eval-packs/mini-*.jsonl";

  private volatile List<EvalSample> cache;

  public List<EvalSample> loadAll() {
    var current = cache;
    if (current == null) {
      synchronized (this) {
        current = cache;
        if (current == null) {
          current = List.copyOf(read());
          cache = current;
        }
      }
    }
    return current;
  }

  public List<EvalSample> byCategory(EvalCategory category) {
    return loadAll().stream().filter(sample -> sample.category() == category).toList();
  }

  public Map<EvalCategory, Integer> countsByCategory() {
    var counts = new EnumMap<EvalCategory, Integer>(EvalCategory.class);
    for (EvalCategory category : EvalCategory.values()) counts.put(category, 0);
    for (var sample : loadAll()) counts.merge(sample.category(), 1, Integer::sum);
    return Map.copyOf(counts);
  }

  private List<EvalSample> read() {
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
      if (resources.length == 0) throw new IllegalStateException("内置评测微集缺失：eval-packs/mini-*.jsonl");
      var samples = new ArrayList<EvalSample>();
      var ids = new java.util.HashSet<String>();
      for (Resource resource : resources) {
        String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int index = 0;
        for (String line : SampleJson.splitJsonl(text)) {
          index++;
          var sample =
              SampleJson.parseLine(
                  line,
                  null,
                  SOURCE_MINI,
                  resource.getFilename() + "#" + index);
          if (!ids.add(sample.id()))
            throw new IllegalStateException("内置评测微集存在重复样本标识：" + sample.id());
          samples.add(sample);
        }
      }
      return samples;
    } catch (IOException e) {
      throw new IllegalStateException("读取内置评测微集失败。", e);
    }
  }
}
