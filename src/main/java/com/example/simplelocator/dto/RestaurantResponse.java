package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RestaurantResponse {
    private Long id;
    private String name;
    private String address;
    private String cuisine;
    private double latitude;
    private double longitude;
    private double distanceMiles;
    private String geohash;
}
