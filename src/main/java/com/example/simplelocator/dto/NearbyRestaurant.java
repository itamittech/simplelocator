package com.example.simplelocator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NearbyRestaurant {
    private Long   id;
    private String name;
    private String cuisine;
    private double latitude;
    private double longitude;
    private double distanceMiles;
}
