package com.example.simplelocator.entity;

import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.Point;

/**
 * Restaurant entity with three spatial representations to demonstrate different index types:
 *
 * 1. locationGist  → geometry(Point,4326) with GiST index  → R-tree based spatial index
 * 2. locationSpgist→ geometry(Point,4326) with SP-GiST index→ Quadtree based spatial index
 * 3. geohash       → VARCHAR with B-tree index             → Geohash based string prefix search
 *
 * We keep two separate geometry columns so both GiST and SP-GiST indexes can be
 * demonstrated independently via EXPLAIN ANALYZE.
 */
@Entity
@Table(name = "restaurants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    private String cuisine;

    private double latitude;

    private double longitude;

    /**
     * Used by GiST (R-tree) index queries.
     * ST_DWithin on geometry + GiST is the classic PostGIS spatial index approach.
     */
    @Column(name = "location_gist", columnDefinition = "geometry(Point, 4326)")
    private Point locationGist;

    /**
     * Used by SP-GiST (Quadtree) index queries.
     * The && (bounding-box overlap) operator leverages the SP-GiST index.
     */
    @Column(name = "location_spgist", columnDefinition = "geometry(Point, 4326)")
    private Point locationSpgist;

    /**
     * Geohash string at precision 9 (~4m x 5m cell).
     * Indexed with a standard B-tree index.
     * Prefix-based queries narrow candidates before exact distance filtering.
     */
    @Column(name = "geohash", length = 12)
    private String geohash;
}
