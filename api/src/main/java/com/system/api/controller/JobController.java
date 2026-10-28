package com.system.api.controller;

import com.system.api.dto.CreateJobRequest;
import com.system.api.service.JobCreationResult;
import com.system.api.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.system.api.dto.JobPage;
import com.system.api.dto.JobResponse;
import com.system.common.enums.JobStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateJobRequest request) {

        JobCreationResult result = jobService.createJob(request, idempotencyKey);

        HttpStatus status = result.created()
                ? HttpStatus.CREATED
                : HttpStatus.OK;

        return ResponseEntity
                .status(status)
                .body(result.job());
    }

    @GetMapping
    public ResponseEntity<JobPage> listJobs(
            @RequestParam(value = "status", required = false) JobStatus status,
            @RequestParam(value = "cursor", required = false) UUID cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(jobService.listJobs(status, cursor, limit));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobResponse> cancelJob(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(jobService.cancelJob(id));
    }

    /** Admin operation (FR-27). There is no authentication in this project (spec 1.5). */
    @PostMapping("/{id}/requeue")
    public ResponseEntity<JobResponse> requeueJob(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(jobService.requeueJob(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable("id") UUID id) {
        JobResponse job = jobService.getJob(id);

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(job);
    }
}