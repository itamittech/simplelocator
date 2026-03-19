package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MenuItemDto {
    private String name;
    private String description;
    private double price;
    private List<String> dietary;
}
