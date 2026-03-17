package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SearchResult {

    /**
     * Which index method was used for this search
     */
    private String indexType;

    /**
     * Short description of how the index works
     */
    private String description;

    /**
     * How many restaurants were found within 5 miles
     */
    private int count;

    /**
     * Query execution time in milliseconds
     */
    private long executionTimeMs;

    /**
     * The actual restaurants found
     */
    private List<RestaurantResponse> restaurants;
}
