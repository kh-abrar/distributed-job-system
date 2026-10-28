package com.system.api.repository;

import com.system.api.entity.JobEntity;
import com.system.common.enums.JobStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Listing queries for jobs (FR-9, FR-10).
 */
@Repository
public class JobQueryRepository {

    private final EntityManager entityManager;

    public JobQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Returns up to maxRows jobs with id greater than the cursor, in id order.
     *
     * The JPQL is built from only the conditions in use, rather than
     * "(:status IS NULL OR status = :status)", so PostgreSQL can use the
     * (status, id) index for filtered pages and the primary key otherwise.
     *
     * @param status null for every status
     * @param cursor null to start from the beginning
     */
    public List<JobEntity> findPage(JobStatus status, UUID cursor, int maxRows) {
        StringBuilder jpql = new StringBuilder("SELECT j FROM JobEntity j WHERE 1 = 1");
        if (status != null) {
            jpql.append(" AND j.status = :status");
        }
        if (cursor != null) {
            jpql.append(" AND j.id > :cursor");
        }
        jpql.append(" ORDER BY j.id");

        TypedQuery<JobEntity> query = entityManager.createQuery(jpql.toString(), JobEntity.class);
        if (status != null) {
            query.setParameter("status", status);
        }
        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }
        return query.setMaxResults(maxRows).getResultList();
    }
}
