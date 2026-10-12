
package com.system.api.repository;

import com.system.api.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository
        extends JpaRepository<JobEntity, UUID> {

    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);
}
