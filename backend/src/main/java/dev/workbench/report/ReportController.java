package dev.workbench.report;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 下载静态运行报告，不执行报告中包含的仓库文本。 */
@RestController
@RequestMapping("/api/runs")
public class ReportController {
  private final HtmlReportService service;
  private final dev.workbench.ownership.OwnershipService ownership;

  public ReportController(
      HtmlReportService service, dev.workbench.ownership.OwnershipService ownership) {
    this.service = service;
    this.ownership = ownership;
  }

  @GetMapping(value = "/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<byte[]> report(
      @PathVariable String id, @RequestParam(defaultValue = "FULL") ReportView view) {
    ownership.requireRun(id);
    String html =
        switch (view) {
          case SUMMARY -> service.renderSummary(id);
          case REDACTED -> service.renderRedacted(id);
          default -> service.render(id);
        };
    String filename =
        switch (view) {
          case SUMMARY -> "agent-summary.html";
          case REDACTED -> "agent-report-redacted.html";
          default -> "agent-report.html";
        };
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
        .body(html.getBytes(StandardCharsets.UTF_8));
  }
}
