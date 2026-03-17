package com.example.simplelocator.dto;

import lombok.Data;

@Data
public class LocationRequest {
    /**
     * User's current latitude (e.g. 40.7128 for New York)
     */
    private double latitude;

    /**
     * User's current longitude (e.g. -74.0060 for New York)
     */
    private double longitude;
}
