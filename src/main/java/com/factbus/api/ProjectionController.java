package com.factbus.api;

import com.factbus.projection.ProjectionService;
import com.factbus.projection.SubjectProjection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for reading per-subject state projections (DESIGN.md §10).
 *
 * GET /v1/projections/{subjectType}/{subjectId}
 */
@RestController
@RequestMapping("/v1/projections")
public class ProjectionController {

    private final ProjectionService projectionService;

    public ProjectionController(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @GetMapping("/{subjectType}/{subjectId}")
    public ResponseEntity<SubjectProjection> getProjection(
            @PathVariable String subjectType,
            @PathVariable String subjectId) {
        return projectionService.getProjection(subjectType, subjectId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
