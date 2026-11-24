package dev.workbench.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.workbench.auth.AuthService;
import dev.workbench.persistence.entity.AuditEventEntity;
import dev.workbench.persistence.entity.UserEntity;
import dev.workbench.persistence.repository.AuditRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 审计操作人必须是真实身份。
 *
 * <p>反例对应 S-14：写死 {@code local-user} 会让多用户下的所有操作无法追责。审计实体刻意只提供写入 能力、读取走专用投影，因此这里用反射读取刚保存的记录字段。
 */
@ExtendWith(MockitoExtension.class)
class AuditActorTest {
  @Mock AuditRepository records;
  @Mock AuthService auth;

  private AuditService audit;

  @BeforeEach
  void setUp() {
    audit = new AuditService(records, auth);
    when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private AuditEventEntity saved() {
    ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
    verify(records).save(captor.capture());
    return captor.getValue();
  }

  private static String field(AuditEventEntity entity, String name) {
    Object value = ReflectionTestUtils.getField(entity, name);
    return value == null ? null : value.toString();
  }

  @Test
  void recordsAuthenticatedUsername() {
    UserEntity user = mock(UserEntity.class);
    lenient().when(user.getUsername()).thenReturn("alice");
    when(auth.current()).thenReturn(Optional.of(user));

    audit.record("ACTION", "TARGET", "id-1", "RECORDED", "说明");

    assertEquals("alice", field(saved(), "actor"));
  }

  @Test
  void recordsSystemActorForBackgroundActionsAfterInitialization() {
    when(auth.initialized()).thenReturn(true);
    when(auth.current()).thenReturn(Optional.empty());

    audit.record("ACTION", "TARGET", "id-1", "RECORDED", "说明");

    assertEquals(AuditService.SYSTEM_ACTOR, field(saved(), "actor"));
  }

  @Test
  void recordsLocalActorBeforeAnyAccountIsInitialized() {
    when(auth.initialized()).thenReturn(false);
    when(auth.current()).thenReturn(Optional.empty());

    audit.record("ACTION", "TARGET", "id-1", "RECORDED", "说明");

    assertEquals(AuditService.LOCAL_ACTOR, field(saved(), "actor"));
  }

  @Test
  void detailIsTruncatedAndOutcomeDefaultsToRecorded() {
    StringBuilder detail = new StringBuilder();
    for (int index = 0; index < 1200; index++) detail.append('x');
    when(auth.initialized()).thenReturn(false);
    when(auth.current()).thenReturn(Optional.empty());

    audit.record("ACTION", "TARGET", "id-1", null, detail.toString());

    var entity = saved();
    assertEquals(1000, field(entity, "detail").length());
    assertEquals("RECORDED", field(entity, "outcome"));
  }
}
