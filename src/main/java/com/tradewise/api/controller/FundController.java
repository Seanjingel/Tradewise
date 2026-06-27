package com.tradewise.api.controller;

import com.tradewise.model.dto.FundLimitResponse;
import com.tradewise.service.DhanFundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API controller for Dhan fund management.
 */
@RestController
@RequestMapping("/api/dhan")
public class FundController {

    private final DhanFundService dhanFundService;

    public FundController(DhanFundService dhanFundService) {
        this.dhanFundService = dhanFundService;
    }

    @GetMapping("/fundlimit")
    public FundLimitResponse getFundLimit() {
        return dhanFundService.getFundLimit();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}

