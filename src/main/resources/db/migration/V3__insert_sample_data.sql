-- ─────────────────────────────────────────────────────────────────────────────
-- Sample restaurant data — New York City area
--
-- Coordinates are real NYC locations spread across Manhattan, Brooklyn,
-- Queens, and NJ to give meaningful "within 5 miles" results.
--
-- ST_SetSRID(ST_MakePoint(lng, lat), 4326) — note: MakePoint takes (X=lng, Y=lat)
-- ST_GeoHash(geometry, 9)                  — precision-9 geohash (~4m accuracy)
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO restaurants (name, address, cuisine, latitude, longitude, location_gist, location_spgist, geohash) VALUES
-- Manhattan
('Joe''s Pizza',            '7 Carmine St, New York, NY',          'Pizza',      40.7301, -74.0022, ST_SetSRID(ST_MakePoint(-74.0022, 40.7301), 4326), ST_SetSRID(ST_MakePoint(-74.0022, 40.7301), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-74.0022, 40.7301), 4326), 9)),
('Katz''s Delicatessen',    '205 E Houston St, New York, NY',       'Deli',       40.7223, -73.9873, ST_SetSRID(ST_MakePoint(-73.9873, 40.7223), 4326), ST_SetSRID(ST_MakePoint(-73.9873, 40.7223), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9873, 40.7223), 4326), 9)),
('Le Bernardin',            '155 W 51st St, New York, NY',          'French',     40.7614, -73.9816, ST_SetSRID(ST_MakePoint(-73.9816, 40.7614), 4326), ST_SetSRID(ST_MakePoint(-73.9816, 40.7614), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9816, 40.7614), 4326), 9)),
('Eleven Madison Park',     '11 Madison Ave, New York, NY',         'American',   40.7416, -73.9872, ST_SetSRID(ST_MakePoint(-73.9872, 40.7416), 4326), ST_SetSRID(ST_MakePoint(-73.9872, 40.7416), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9872, 40.7416), 4326), 9)),
('Xi''an Famous Foods',     '81 St Marks Pl, New York, NY',         'Chinese',    40.7277, -73.9863, ST_SetSRID(ST_MakePoint(-73.9863, 40.7277), 4326), ST_SetSRID(ST_MakePoint(-73.9863, 40.7277), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9863, 40.7277), 4326), 9)),
('Peter Luger Steak House', '178 Broadway, Brooklyn, NY',           'Steakhouse', 40.7099, -73.9625, ST_SetSRID(ST_MakePoint(-73.9625, 40.7099), 4326), ST_SetSRID(ST_MakePoint(-73.9625, 40.7099), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9625, 40.7099), 4326), 9)),
('Gramercy Tavern',         '42 E 20th St, New York, NY',           'American',   40.7386, -73.9883, ST_SetSRID(ST_MakePoint(-73.9883, 40.7386), 4326), ST_SetSRID(ST_MakePoint(-73.9883, 40.7386), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9883, 40.7386), 4326), 9)),
('Nobu',                    '105 Hudson St, New York, NY',          'Japanese',   40.7201, -74.0087, ST_SetSRID(ST_MakePoint(-74.0087, 40.7201), 4326), ST_SetSRID(ST_MakePoint(-74.0087, 40.7201), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-74.0087, 40.7201), 4326), 9)),
('Shake Shack Madison Sq',  'Madison Square Park, New York, NY',    'Burgers',    40.7408, -73.9882, ST_SetSRID(ST_MakePoint(-73.9882, 40.7408), 4326), ST_SetSRID(ST_MakePoint(-73.9882, 40.7408), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9882, 40.7408), 4326), 9)),
('Momofuku Noodle Bar',     '171 First Ave, New York, NY',          'Ramen',      40.7286, -73.9816, ST_SetSRID(ST_MakePoint(-73.9816, 40.7286), 4326), ST_SetSRID(ST_MakePoint(-73.9816, 40.7286), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9816, 40.7286), 4326), 9)),

-- Brooklyn
('Roberta''s',              '261 Moore St, Brooklyn, NY',           'Pizza',      40.7052, -73.9334, ST_SetSRID(ST_MakePoint(-73.9334, 40.7052), 4326), ST_SetSRID(ST_MakePoint(-73.9334, 40.7052), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9334, 40.7052), 4326), 9)),
('Grimaldi''s Pizzeria',    '1 Front St, Brooklyn, NY',             'Pizza',      40.7027, -73.9934, ST_SetSRID(ST_MakePoint(-73.9934, 40.7027), 4326), ST_SetSRID(ST_MakePoint(-73.9934, 40.7027), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9934, 40.7027), 4326), 9)),
('Junior''s Restaurant',    '386 Flatbush Ave Ext, Brooklyn, NY',   'Diner',      40.6912, -73.9857, ST_SetSRID(ST_MakePoint(-73.9857, 40.6912), 4326), ST_SetSRID(ST_MakePoint(-73.9857, 40.6912), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9857, 40.6912), 4326), 9)),
('The River Café',          '1 Water St, Brooklyn, NY',             'American',   40.7008, -73.9948, ST_SetSRID(ST_MakePoint(-73.9948, 40.7008), 4326), ST_SetSRID(ST_MakePoint(-73.9948, 40.7008), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9948, 40.7008), 4326), 9)),
('Smorgasburg',             '90 Kent Ave, Brooklyn, NY',            'Food Market',40.7224, -73.9578, ST_SetSRID(ST_MakePoint(-73.9578, 40.7224), 4326), ST_SetSRID(ST_MakePoint(-73.9578, 40.7224), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9578, 40.7224), 4326), 9)),

-- Queens
('Sripraphai',              '64-13 39th Ave, Woodside, NY',         'Thai',       40.7459, -73.9041, ST_SetSRID(ST_MakePoint(-73.9041, 40.7459), 4326), ST_SetSRID(ST_MakePoint(-73.9041, 40.7459), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9041, 40.7459), 4326), 9)),
('Nan Xiang Xiao Long Bao', '38-12 Prince St, Flushing, NY',        'Chinese',    40.7581, -73.8305, ST_SetSRID(ST_MakePoint(-73.8305, 40.7581), 4326), ST_SetSRID(ST_MakePoint(-73.8305, 40.7581), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.8305, 40.7581), 4326), 9)),
('Jackson Diner',           '37-47 74th St, Jackson Heights, NY',   'Indian',     40.7469, -73.8915, ST_SetSRID(ST_MakePoint(-73.8915, 40.7469), 4326), ST_SetSRID(ST_MakePoint(-73.8915, 40.7469), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.8915, 40.7469), 4326), 9)),

-- New Jersey (within 5 miles of lower Manhattan)
('Battello',                '502 Washington Blvd, Jersey City, NJ', 'Italian',    40.7178, -74.0431, ST_SetSRID(ST_MakePoint(-74.0431, 40.7178), 4326), ST_SetSRID(ST_MakePoint(-74.0431, 40.7178), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-74.0431, 40.7178), 4326), 9)),
('Maritime Parc',           '84 Audrey Zapp Dr, Jersey City, NJ',   'American',   40.7133, -74.0563, ST_SetSRID(ST_MakePoint(-74.0563, 40.7133), 4326), ST_SetSRID(ST_MakePoint(-74.0563, 40.7133), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-74.0563, 40.7133), 4326), 9)),

-- Far away (should NOT appear in a 5-mile search from Manhattan)
('Juniper',                 '1207 Cortelyou Rd, Brooklyn, NY',      'American',   40.6397, -73.9631, ST_SetSRID(ST_MakePoint(-73.9631, 40.6397), 4326), ST_SetSRID(ST_MakePoint(-73.9631, 40.6397), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.9631, 40.6397), 4326), 9)),
('The Original Crab Shanty','361 City Island Ave, Bronx, NY',        'Seafood',    40.8499, -73.7877, ST_SetSRID(ST_MakePoint(-73.7877, 40.8499), 4326), ST_SetSRID(ST_MakePoint(-73.7877, 40.8499), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.7877, 40.8499), 4326), 9)),
('Pio Pio',                 '84-02 Northern Blvd, Jackson Heights', 'Peruvian',   40.7542, -73.8836, ST_SetSRID(ST_MakePoint(-73.8836, 40.7542), 4326), ST_SetSRID(ST_MakePoint(-73.8836, 40.7542), 4326), ST_GeoHash(ST_SetSRID(ST_MakePoint(-73.8836, 40.7542), 4326), 9));
