package com.marketingai.repository;

import com.marketingai.entity.RunnerKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RunnerKeyRepository extends JpaRepository<RunnerKey, UUID> {

    Optional<RunnerKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
}
