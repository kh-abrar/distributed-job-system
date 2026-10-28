package com.system.api.repository;

import com.system.api.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * JPA reads of jobs. Writes go through native SQL (JobSubmissionRepository and,
 * later, fenced UPDATEs that check the affected row count).
 */
public interface JobRepository
        extends JpaRepository<JobEntity, UUID> {
}
