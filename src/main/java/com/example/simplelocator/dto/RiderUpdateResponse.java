package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RiderUpdateResponse {
    private String  riderId;
    private double  latitude;
    private double  longitude;
    private String  currentZone;            // null when rider is outside all zones
    private Long    assignedRestaurantId;   // null when none within 0.5 mi
    private String  assignedRestaurantName;
    private List<NearbyRestaurant> nearbyRestaurants;
    private long    executionTimeMs;
}
