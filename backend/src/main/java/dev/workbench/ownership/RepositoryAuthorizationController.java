package dev.workbench.ownership;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仓库导入授权：普通用户查看自己的授权，管理员授权或撤销。 */
@RestController
@RequestMapping("/api")
public class RepositoryAuthorizationController {
  private final RepositoryAuthorizationService authorizations;

  public RepositoryAuthorizationController(RepositoryAuthorizationService authorizations) {
    this.authorizations = authorizations;
  }

  public record GrantRequest(String username, String repositoryPath) {}

  @GetMapping("/repositories")
  List<RepositoryAuthorizationService.GrantView> mine() {
    return authorizations.listCurrent();
  }

  @GetMapping("/admin/repositories")
  List<RepositoryAuthorizationService.GrantView> all() {
    return authorizations.listAll();
  }

  @PostMapping("/admin/repositories")
  RepositoryAuthorizationService.GrantView grant(@RequestBody GrantRequest request) {
    return authorizations.grant(request.username(), request.repositoryPath());
  }

  @DeleteMapping("/admin/repositories/{id}")
  Object revoke(@PathVariable String id) {
    authorizations.revoke(id);
    return java.util.Map.of("revoked", true);
  }
}
