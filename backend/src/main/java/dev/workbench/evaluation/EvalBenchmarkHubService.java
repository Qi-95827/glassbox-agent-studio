package dev.workbench.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workbench.evaluation.dataset.BenchmarkPackAdapter;
import dev.workbench.evaluation.dataset.SampleJson;
import dev.workbench.framework.evaluation.EvalCategory;
import dev.workbench.framework.evaluation.EvalSample;
import dev.workbench.persistence.entity.EvalBenchmarkPackEntity;
import dev.workbench.persistence.repository.EvalBenchmarkPackRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Benchmark Hub 按需下载中心。
 *
 * <p>在线下载只接受 Catalog 中登记的 HTTPS 地址（同时校验协议、主机白名单与完整 URL 等值）， 并限制文件体积、计算 SHA-256。网络受限时，离线 JSONL
 * 导入与在线下载共享同一个 {@link BenchmarkPackAdapter} 标准化入口。
 */
@Service
public class EvalBenchmarkHubService {
  private static final long MAX_BYTES = 50L * 1024 * 1024;
  private static final Set<String> ALLOWED_HOSTS =
      Set.of(
          "raw.githubusercontent.com",
          "huggingface.co",
          "datasets-server.huggingface.co",
          "github.com");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final EvalBenchmarkPackRepository repository;
  private final BenchmarkPackAdapter adapter;
  private final Path datasetsRoot;
  private final List<CatalogPack> catalog;

  public EvalBenchmarkHubService(
      EvalBenchmarkPackRepository repository,
      BenchmarkPackAdapter adapter,
      @Value("${workbench.eval.datasets-root:data/eval-datasets}") String datasetsRoot) {
    this.repository = repository;
    this.adapter = adapter;
    this.datasetsRoot = Path.of(datasetsRoot).toAbsolutePath().normalize();
    this.catalog = loadCatalog();
  }

  public record CatalogPack(
      String packId,
      String category,
      String displayName,
      String sourceUrl,
      String downloadUrl,
      String license,
      int expectedSamples,
      int defaultSampleLimit,
      String descriptionZh,
      String descriptionEn,
      boolean previewOnly) {
    public EvalCategory evalCategory() {
      return EvalCategory.parse(category);
    }
  }

  public record PackView(
      String packId,
      String category,
      String displayName,
      String sourceUrl,
      String license,
      int expectedSamples,
      int defaultSampleLimit,
      String descriptionZh,
      String descriptionEn,
      String status,
      boolean previewOnly,
      int sampleCount,
      long sizeBytes,
      String sha256,
      String downloadedAt,
      String errorMessage) {}

  public record ImportRequest(String jsonl) {}

  public record ImportResult(
      String packId, String status, int sampleCount, String sha256, String path) {}

  public List<PackView> list() {
    var statuses = new java.util.HashMap<String, EvalBenchmarkPackEntity>();
    for (var row : repository.findAllByOrderByPackIdAsc()) statuses.put(row.getPackId(), row);
    var result = new ArrayList<PackView>();
    for (var pack : catalog) {
      var row = statuses.get(pack.packId());
      boolean ready = row != null && Files.isRegularFile(samplesFile(pack));
      result.add(
          new PackView(
              pack.packId(),
              pack.category(),
              pack.displayName(),
              pack.sourceUrl(),
              pack.license(),
              pack.expectedSamples(),
              pack.defaultSampleLimit(),
              pack.descriptionZh(),
              pack.descriptionEn(),
              ready
                  ? row.getStatus()
                  : row != null && row.getLocalPath() != null
                      ? "REIMPORT_REQUIRED"
                      : row == null ? "NOT_DOWNLOADED" : row.getStatus(),
              pack.previewOnly(),
              ready ? row.getSampleCount() : 0,
              ready ? row.getSizeBytes() : 0,
              ready ? row.getSha256() : null,
              row == null ? null : row.getDownloadedAt(),
              row == null ? null : row.getErrorMessage()));
    }
    return List.copyOf(result);
  }

  public ImportResult download(String packId) {
    var pack = requirePack(packId);
    validateSourceUrl(pack, pack.downloadUrl());
    try {
      var client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(15))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      var request =
          HttpRequest.newBuilder(URI.create(pack.downloadUrl()))
              .timeout(Duration.ofSeconds(120))
              .header("Accept", "application/json, application/x-ndjson, */*")
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() / 100 != 2) {
        markFailed(pack, "官方源返回 HTTP " + response.statusCode());
        throw new IllegalStateException("官方源返回 HTTP " + response.statusCode() + "，请改用离线导入。");
      }
      long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      if (declared > MAX_BYTES) {
        markFailed(pack, "文件超过 50MB 上限");
        throw new IllegalStateException("外部评测集超过 50MB 上限，已拒绝下载。");
      }
      String text;
      try (InputStream input = response.body()) {
        text = readBounded(input);
      }
      return importPayload(pack, text, "DOWNLOADED");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      markFailed(pack, "下载被中断");
      throw new IllegalStateException("下载被中断。", e);
    } catch (IOException e) {
      markFailed(pack, "下载失败：" + safe(e.getMessage()));
      throw new IllegalStateException("下载失败，请检查网络或改用离线导入。", e);
    }
  }

  public ImportResult importJsonl(String packId, String jsonl) {
    var pack = requirePack(packId);
    if (jsonl == null || jsonl.isBlank()) throw new IllegalArgumentException("离线导入内容为空。");
    if (jsonl.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("离线导入内容超过 50MB 上限。");
    return importPayload(pack, jsonl, "IMPORTED");
  }

  public List<EvalSample> samplesForRun(List<String> packIds) {
    return samplesForRun(packIds, Integer.MAX_VALUE);
  }

  public List<EvalSample> samplesForRun(List<String> packIds, int limit) {
    if (packIds == null || packIds.isEmpty())
      throw new IllegalArgumentException("请选择至少一个已下载的 Hub 评测集。");
    var samples = new ArrayList<EvalSample>();
    for (String packId : packIds) {
      var pack = requirePack(packId);
      Path file = samplesFile(pack);
      if (!Files.exists(file))
        throw new IllegalStateException("Hub 评测集尚未下载或导入：" + pack.packId() + "，请先在下载中心处理。");
      try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        int index = 0;
        for (String line; samples.size() < limit && (line = reader.readLine()) != null; ) {
          index++;
          if (line.isBlank()) continue;
          var sample =
              SampleJson.parseLine(
                  line, pack.evalCategory(), "hub:" + pack.packId(), pack.packId() + "#" + index);
          if (!BenchmarkPackAdapter.AGENT_PACK_MODE.equals(sample.setup().get("hubPackMode")))
            throw new IllegalArgumentException("公开题集格式已更新，请重新下载或导入。");
          samples.add(sample);
        }
      } catch (IOException e) {
        throw new IllegalStateException("读取 Hub 评测集失败：" + pack.packId(), e);
      }
    }
    if (samples.isEmpty()) throw new IllegalStateException("Hub 评测集没有可执行样本。");
    return List.copyOf(samples);
  }

  public record SamplePreview(
      String id, String title, String prompt, String repository, String baseCommit) {}

  public List<SamplePreview> preview(String packId) {
    return samplesForRun(List.of(packId), 5).stream()
        .map(
            sample ->
                new SamplePreview(
                    sample.id(),
                    sample.titleEn(),
                    sample.prompt(),
                    (String) sample.setup().get("repository"),
                    (String) sample.setup().get("baseCommit")))
        .toList();
  }

  public CatalogPack requirePack(String packId) {
    if (packId == null || !packId.matches("[a-z0-9-]{1,60}"))
      throw new IllegalArgumentException("评测集标识无效。");
    return catalog.stream()
        .filter(item -> item.packId().equals(packId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("该评测集不在 Catalog 白名单中：" + packId));
  }

  /** 只允许 Catalog 登记的完整 HTTPS URL，且主机必须命中受信任白名单。 */
  void validateSourceUrl(CatalogPack pack, String url) {
    if (url == null || url.isBlank()) throw new IllegalArgumentException("下载地址为空。");
    if (!url.equals(pack.downloadUrl()))
      throw new IllegalArgumentException("下载地址与 Catalog 登记不一致，已拒绝。");
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("下载地址不是有效 URL。", e);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()))
      throw new IllegalArgumentException("只允许 HTTPS 下载地址。");
    if (uri.getUserInfo() != null || uri.getPort() != -1)
      throw new IllegalArgumentException("下载地址不允许携带凭据或自定义端口。");
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!ALLOWED_HOSTS.contains(host)) throw new IllegalArgumentException("下载主机不在受信任白名单中：" + host);
  }

  private synchronized ImportResult importPayload(
      CatalogPack pack, String payload, String sourceStatus) {
    var samples = adapter.adapt(pack.packId(), pack.evalCategory(), payload, pack.license());
    try {
      Path dir = datasetsRoot.resolve(pack.packId()).normalize();
      if (!dir.startsWith(datasetsRoot)) throw new IllegalStateException("数据集目录越界。");
      Files.createDirectories(dir);
      var builder = new StringBuilder();
      for (var sample : samples)
        builder.append(JSON.writeValueAsString(toMap(sample))).append('\n');
      byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
      Path target = samplesFile(pack);
      Path temporary = Files.createTempFile(dir, "samples-", ".tmp");
      try {
        Files.write(temporary, bytes);
        Files.move(
            temporary,
            target,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
      String sha = sha256(bytes);
      String now = dev.workbench.domain.WorkbenchTime.now();
      var row =
          repository
              .findByPackId(pack.packId())
              .orElseGet(
                  () ->
                      new EvalBenchmarkPackEntity(
                          dev.workbench.domain.WorkbenchIds.newId(),
                          pack.packId(),
                          pack.category(),
                          pack.displayName(),
                          pack.sourceUrl(),
                          pack.license(),
                          sourceStatus,
                          now));
      row.imported(
          datasetsRoot.relativize(target).toString().replace('\\', '/'),
          sha,
          samples.size(),
          bytes.length,
          now);
      repository.save(row);
      return new ImportResult(
          pack.packId(), row.getStatus(), samples.size(), sha, row.getLocalPath());
    } catch (IOException e) {
      markFailed(pack, "写入数据集文件失败：" + safe(e.getMessage()));
      throw new IllegalStateException("写入数据集文件失败。", e);
    }
  }

  private Path samplesFile(CatalogPack pack) {
    Path dir = datasetsRoot.resolve(pack.packId()).normalize();
    if (!dir.startsWith(datasetsRoot)) throw new IllegalStateException("数据集目录越界。");
    return dir.resolve("samples-agent-v2.jsonl");
  }

  private void markFailed(CatalogPack pack, String message) {
    String now = dev.workbench.domain.WorkbenchTime.now();
    var row =
        repository
            .findByPackId(pack.packId())
            .orElseGet(
                () ->
                    new EvalBenchmarkPackEntity(
                        dev.workbench.domain.WorkbenchIds.newId(),
                        pack.packId(),
                        pack.category(),
                        pack.displayName(),
                        pack.sourceUrl(),
                        pack.license(),
                        "FAILED",
                        now));
    row.failed(message, now);
    repository.save(row);
  }

  private static String readBounded(InputStream input) throws IOException {
    var output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > MAX_BYTES) throw new IOException("文件超过 50MB 上限");
      output.write(buffer, 0, read);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Object> toMap(EvalSample sample) {
    var map = new java.util.LinkedHashMap<String, Object>();
    map.put("id", sample.id());
    map.put("category", sample.category().name());
    map.put("sourcePack", sample.sourcePack());
    map.put("titleZh", sample.titleZh());
    map.put("titleEn", sample.titleEn());
    map.put("prompt", sample.prompt());
    map.put("setup", sample.setup());
    map.put("assertions", sample.assertions());
    map.put("judgeRubric", sample.judgeRubric());
    return map;
  }

  private static List<CatalogPack> loadCatalog() {
    try (InputStream input =
        new ClassPathResource("eval-packs/benchmark-hub-catalog.json").getInputStream()) {
      var root = JSON.readTree(input.readAllBytes());
      var packs = root.path("packs");
      var result = new ArrayList<CatalogPack>();
      for (var node : packs) {
        result.add(
            new CatalogPack(
                node.path("packId").asText(),
                node.path("category").asText(),
                node.path("displayName").asText(),
                node.path("sourceUrl").asText(),
                node.path("downloadUrl").asText(),
                node.path("license").asText(""),
                node.path("expectedSamples").asInt(0),
                node.path("defaultSampleLimit").asInt(10),
                node.path("descriptionZh").asText(""),
                node.path("descriptionEn").asText(""),
                node.path("previewOnly").asBoolean(false)));
      }
      if (result.isEmpty()) throw new IllegalStateException("Benchmark Hub Catalog 为空。");
      return List.copyOf(result);
    } catch (IOException e) {
      throw new IllegalStateException("读取 Benchmark Hub Catalog 失败。", e);
    }
  }

  private static String safe(String value) {
    if (value == null || value.isBlank()) return "未知错误";
    return value.length() <= 300 ? value : value.substring(0, 299) + "…";
  }
}
