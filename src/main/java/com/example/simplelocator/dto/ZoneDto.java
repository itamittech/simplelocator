package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ZoneDto {
    private Long   id;
    private String name;
    private String color;
    private double minLat;
    private double maxLat;
    private double minLng;
    private double maxLng;
}
