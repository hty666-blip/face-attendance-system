package com.example.project.controller;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.Collections;
@RestController
public class HealthController {
    @Resource private JdbcTemplate jdbc;
    @GetMapping("/health") public ResponseEntity<?> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Collections.singletonMap("status","UP"));
        } catch(Exception e) {
            return ResponseEntity.status(503).body(Collections.singletonMap("status","DOWN"));
        }
    }
}
