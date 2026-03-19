package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MenuSearchResult {
    private Long id;
    private String name;
    private String address;
    private String cuisine;
    private double latitude;
    private double longitude;
    private double distanceMiles;
    /** ts_rank score (0.0–1.0) — higher means stronger text relevance. */
    private double relevanceScore;
    private String priceRange;
    private List<String> dietaryOptions;
    private List<MenuItemDto> items;
}
