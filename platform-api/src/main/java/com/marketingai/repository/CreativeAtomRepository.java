package com.marketingai.repository;

import com.marketingai.entity.CreativeAtom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreativeAtomRepository extends JpaRepository<CreativeAtom, UUID> {

    List<CreativeAtom> findByCreativeIdOrderByAtomTypeAscCreatedAtAsc(UUID creativeId);

    Optional<CreativeAtom> findByIdAndTenantId(UUID id, UUID tenantId);

    void deleteByCreativeId(UUID creativeId);
}
