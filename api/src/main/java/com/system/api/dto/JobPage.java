package com.system.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One page of GET /api/jobs.
 *
 * @param nextCursor pass as ?cursor= to get the next page; null on the last page
 */
public record JobPage(
        List<JobResponse> items,
        UUID nextCursor) {
}
