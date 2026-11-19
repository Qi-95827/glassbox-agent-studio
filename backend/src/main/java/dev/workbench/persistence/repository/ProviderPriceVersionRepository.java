package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ProviderPriceVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderPriceVersionRepository
    extends JpaRepository<ProviderPriceVersionEntity, String> {
  List<ProviderPriceVersionEntity> findAllByOrderByProviderAscModelAscVersionDesc();

  Optional<ProviderPriceVersionEntity> findFirstByProviderAndModelAndActiveTrueOrderByVersionDesc(
      String provider, String model);

  java.util.List<ProviderPriceVersionEntity> findByProviderAndModelAndActiveTrue(
      String provider, String model);
}
