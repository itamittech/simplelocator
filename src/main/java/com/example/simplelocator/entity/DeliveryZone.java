package com.example.simplelocator.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "delivery_zones")
@Getter
@NoArgsConstructor
public class DeliveryZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String color;

    /**
     * Polygon stored as GEOMETRY(Polygon, 4326) in PostGIS.
     * Hibernate Spatial maps this to JTS Polygon.
     */
    @Column(nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon boundary;
}
