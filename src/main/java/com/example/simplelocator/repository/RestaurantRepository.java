package com.example.simplelocator.repository;

import com.example.simplelocator.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * NOTE: Spring Data JPA parses ":name" as a named parameter inside native queries.
 * The PostgreSQL cast operator "::" would be mis-parsed as ":"+":geography" and
 * break the query. All casts therefore use ANSI SQL  CAST(expr AS type)  instead.
 */
@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    // -------------------------------------------------------------------------
    // 1. GiST (R-tree) Index Query
    //    ST_DWithin on a geography column uses the GiST index automatically.
    //    Geography type enables meter-based distance on a spherical earth.
    //    Index used: USING GIST (location_gist)
    // -------------------------------------------------------------------------
    @Query(value = """
            SELECT r.*,
                   ST_Distance(
                       CAST(r.location_gist AS geography),
                       CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography)
                   ) AS dist_meters
            FROM restaurants r
            WHERE ST_DWithin(
                CAST(r.location_gist AS geography),
                CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography),
                :radiusMeters
            )
            ORDER BY dist_meters
            """, nativeQuery = true)
    List<Object[]> findWithinRadiusUsingGist(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters
    );

    // -------------------------------------------------------------------------
    // 2. SP-GiST (Quadtree) Index Query
    //    The && (bounding-box overlap) operator is supported by SP-GiST.
    //    ST_Expand builds a rectangular envelope (degrees) around the search point.
    //    SP-GiST prunes the quadrant tree using that bounding box, then
    //    ST_DWithin does the exact circular filter.
    //    Index used: USING SPGIST (location_spgist)
    // -------------------------------------------------------------------------
    @Query(value = """
            SELECT r.*,
                   ST_Distance(
                       CAST(r.location_spgist AS geography),
                       CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography)
                   ) AS dist_meters
            FROM restaurants r
            WHERE r.location_spgist && ST_Expand(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :bufferDegrees)
              AND ST_DWithin(
                CAST(r.location_spgist AS geography),
                CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography),
                :radiusMeters
            )
            ORDER BY dist_meters
            """, nativeQuery = true)
    List<Object[]> findWithinRadiusUsingSpgist(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("bufferDegrees") double bufferDegrees
    );

    // -------------------------------------------------------------------------
    // 3. Geohash (B-tree) Index Query
    //    9 LIKE prefix predicates — one per geohash cell (center + 8 neighbors).
    //    The B-tree on geohash handles the prefix narrowing; ST_DWithin removes
    //    false positives from rectangular cell corners.
    //    Index used: B-tree on geohash column
    // -------------------------------------------------------------------------
    @Query(value = """
            SELECT r.*,
                   ST_Distance(
                       CAST(r.location_gist AS geography),
                       CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography)
                   ) AS dist_meters
            FROM restaurants r
            WHERE (
                geohash LIKE CONCAT(:p0, '%') OR
                geohash LIKE CONCAT(:p1, '%') OR
                geohash LIKE CONCAT(:p2, '%') OR
                geohash LIKE CONCAT(:p3, '%') OR
                geohash LIKE CONCAT(:p4, '%') OR
                geohash LIKE CONCAT(:p5, '%') OR
                geohash LIKE CONCAT(:p6, '%') OR
                geohash LIKE CONCAT(:p7, '%') OR
                geohash LIKE CONCAT(:p8, '%')
            )
              AND ST_DWithin(
                CAST(r.location_gist AS geography),
                CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography),
                :radiusMeters
            )
            ORDER BY dist_meters
            """, nativeQuery = true)
    List<Object[]> findWithinRadiusUsingGeohash(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("p0") String p0,
            @Param("p1") String p1,
            @Param("p2") String p2,
            @Param("p3") String p3,
            @Param("p4") String p4,
            @Param("p5") String p5,
            @Param("p6") String p6,
            @Param("p7") String p7,
            @Param("p8") String p8
    );
}
