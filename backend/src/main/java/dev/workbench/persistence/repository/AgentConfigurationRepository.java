package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;

import dev.workbench.domain.ToolKind;
import dev.workbench.framework.skill.SkillAssignment;
import dev.workbench.framework.skill.SkillId;
import dev.workbench.framework.skill.SkillRef;
import dev.workbench.framework.tool.AgentToolGrant;
import dev.workbench.persistence.entity.AgentAssemblyEntity;
import dev.workbench.persistence.entity.AgentSkillEntity;
import dev.workbench.persistence.entity.AgentToolGrantEntity;
import dev.workbench.persistence.entity.AgentToolLoadEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Agent 装配快照、技能和工具授权记录。*/
@Repository
@Transactional(readOnly = true)
public class AgentConfigurationRepository {
  private final EntityManager sessions;

  public AgentConfigurationRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAssembly(String agentId, String time, String payload) {
    sessions.persist(new AgentAssemblyEntity(agentId, time, payload));
  }

  public List<AssemblyRow> findAssemblies(String runId) {
    return sessions
        .createQuery(
            """
            select s.agentId, s.recordedAt, s.payload from AgentAssemblyEntity s
            join AgentInvocationEntity a on a.id = s.agentId
            where a.runId = :id order by a.createdAt, a.id
            """,
            AssemblyRow.class)
        .setParameter("id", runId)
        .getResultList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertSkill(String agentId, SkillRef skill, String time) {
    sessions.persist(
        new AgentSkillEntity(
            agentId, skill.id().name(), skill.version(), skill.contentSha256(), time));
  }

  public List<SkillAssignment> findSkills(String runId) {
    return sessions
        .createQuery(
            """
            select s.id.agentId, s.id.skillId, s.skillVersion, s.contentSha256, s.createdAt
            from AgentSkillEntity s join AgentInvocationEntity a on a.id = s.id.agentId
            where a.runId = :id order by a.createdAt, s.id.skillId
            """,
            SkillRow.class)
        .setParameter("id", runId)
        .getResultList()
        .stream()
        .map(
            row ->
                new SkillAssignment(
                    UUID.fromString(row.agentId()),
                    SkillId.valueOf(row.skillId()),
                    row.version(),
                    row.sha256(),
                    Instant.parse(row.time())))
        .toList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insertToolGrants(String agentId, Map<ToolKind, Integer> tools, String time) {
    sessions.persist(new AgentToolLoadEntity(agentId, time));
    tools.forEach(
        (tool, version) ->
            sessions.persist(new AgentToolGrantEntity(agentId, tool.name(), version)));
  }

  public List<AgentToolGrant> findToolGrants(String runId) {
    var rows =
        sessions
            .createQuery(
                """
                select l.agentId, l.loadedAt, g.id.toolKind, g.toolVersion
                from AgentToolLoadEntity l join AgentInvocationEntity a on a.id = l.agentId
                left join AgentToolGrantEntity g on g.id.agentId = l.agentId
                where a.runId = :id order by a.createdAt, a.id, g.id.toolKind
                """,
                ToolRow.class)
            .setParameter("id", runId)
            .getResultList();
    Map<String, List<AgentToolGrant.ToolVersion>> versions = new LinkedHashMap<>();
    Map<String, String> loaded = new LinkedHashMap<>();
    for (var row : rows) {
      loaded.putIfAbsent(row.agentId(), row.time());
      var tools = versions.computeIfAbsent(row.agentId(), ignored -> new ArrayList<>());
      if (row.kind() != null)
        tools.add(new AgentToolGrant.ToolVersion(ToolKind.valueOf(row.kind()), row.version()));
    }
    return loaded.entrySet().stream()
        .map(
            entry -> {
              var tools = versions.get(entry.getKey());
              return new AgentToolGrant(
                  UUID.fromString(entry.getKey()),
                  Instant.parse(entry.getValue()),
                  tools.stream().map(AgentToolGrant.ToolVersion::tool).toList(),
                  List.copyOf(tools));
            })
        .toList();
  }

  public record AssemblyRow(String agentId, String recordedAt, String payload) {}

  public record SkillRow(
      String agentId, String skillId, Integer version, String sha256, String time) {}

  public record ToolRow(String agentId, String time, String kind, Integer version) {}
}