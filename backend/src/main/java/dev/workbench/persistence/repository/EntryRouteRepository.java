package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EntryRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntryRouteRepository extends JpaRepository<EntryRouteEntity, String> {}
