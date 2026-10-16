package com.system.api.service;

import com.system.api.dto.JobResponse;

public record JobCreationResult(
        JobResponse job,
        boolean created) {
}