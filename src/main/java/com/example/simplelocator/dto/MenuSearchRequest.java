package com.example.simplelocator.dto;

import lombok.Data;

@Data
public class MenuSearchRequest {
    private double latitude;
    private double longitude;
    /** Free-text search term — matched against menu item names and descriptions via GIN/tsvector. */
    private String query;
    /** Optional dietary filter — matched against menu.dietary_options JSONB array via GIN/JSONB. */
    private String dietary;
}
