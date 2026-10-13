
package com.system.api.repository;

import com.system.api.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository
        extends JpaRepository<JobEntity, UUID> {

    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);


    @Modifying
    @Query(value = """
            INSERT INTO jobs (
                idempotency_key,
                type,
                payload,
                status,
                attempts,
                max_attempts,
                run_at,
                created_at,
                updated_at
            )
            VALUES (
                :key,
                :type,
                CAST(:payload AS jsonb),
                'PENDING',
                0,
                5,
                now(),
                now(),
                now()
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") String key,
            @Param("type") String type,
            @Param("payload") String payload);
}
