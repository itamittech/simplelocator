package com.example.simplelocator.repository;

import com.example.simplelocator.entity.DeliveryZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {

    /**
     * PostGIS ST_Within: returns the zone whose boundary contains (lng, lat).
     * CAST() used instead of :: because Spring Data JPA's named-param parser
     * breaks on the PostgreSQL :: cast operator.
     */
    @Query(value = """
            SELECT dz.* FROM delivery_zones dz
            WHERE ST_Within(
                CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geometry),
                dz.boundary
            )
            LIMIT 1
            """, nativeQuery = true)
    Optional<DeliveryZone> findZoneContaining(@Param("lat") double lat, @Param("lng") double lng);

    @Query(value = "SELECT dz.* FROM delivery_zones dz", nativeQuery = true)
    List<DeliveryZone> findAllZones();
}
