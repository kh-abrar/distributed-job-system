package com.system.api.controller;

import com.system.api.dto.CreateJobRequest;
import com.system.api.entity.JobEntity;
import com.system.api.service.JobCreationResult;
import com.system.api.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobEntity> createJob(
            @RequestBody CreateJobRequest request) {

        JobCreationResult result = jobService.createJob(request);

        HttpStatus status = result.created()
                ? HttpStatus.CREATED
                : HttpStatus.OK;

        return ResponseEntity
                .status(status)
                .body(result.job());
    }
}