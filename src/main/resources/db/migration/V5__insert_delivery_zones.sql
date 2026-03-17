INSERT INTO delivery_zones (name, color, boundary) VALUES
    ('Lower Manhattan',  '#2563eb', ST_MakeEnvelope(-74.025, 40.700, -73.965, 40.740, 4326));

INSERT INTO delivery_zones (name, color, boundary) VALUES
    ('Midtown Manhattan', '#16a34a', ST_MakeEnvelope(-74.015, 40.740, -73.965, 40.785, 4326));

INSERT INTO delivery_zones (name, color, boundary) VALUES
    ('Brooklyn Heights',  '#ea580c', ST_MakeEnvelope(-74.010, 40.675, -73.975, 40.700, 4326));
