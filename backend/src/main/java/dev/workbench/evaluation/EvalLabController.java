package dev.workbench.evaluation;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 评测实验室 API：发起、列表、逐题穿透与取消。 */
@RestController
@RequestMapping("/api/eval-lab")
public class EvalLabController {
  private final EvalLabService lab;

  public EvalLabController(EvalLabService lab) {
    this.lab = lab;
  }

  public record Created(String id) {}

  @GetMapping("/meta")
  public Map<String, Object> meta() {
    return lab.meta();
  }

  @PostMapping("/runs")
  public Created start(
      @RequestBody EvalLabService.StartRequest request,
      @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
    return new Created(lab.start(request, acceptLanguage));
  }

  @GetMapping("/runs")
  public List<EvalLabService.RunView> list() {
    return lab.list();
  }

  @GetMapping("/runs/{id}")
  public EvalLabService.Detail detail(@PathVariable String id) {
    return lab.detail(id);
  }

  @PostMapping("/runs/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable String id) {
    lab.cancel(id);
    return Map.of("cancelled", true);
  }
}
