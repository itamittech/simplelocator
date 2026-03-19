-- ─────────────────────────────────────────────────────────────────────────────
-- V7 — Populate JSONB menu data for all 23 restaurants
--
-- Each menu document has shape:
--   {
--     "items": [
--       { "name": "...", "description": "...", "price": 14.99,
--         "dietary": ["vegetarian"] }
--     ],
--     "dietary_options": ["vegetarian", "gluten-free"],
--     "price_range": "$$"
--   }
--
-- After populating menu JSON, the tsvector column is computed from:
--   restaurant name + cuisine + all item names + all item descriptions
-- This lets plainto_tsquery('english', 'pasta') hit the GIN FTS index.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Manhattan ────────────────────────────────────────────────────────────────

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Classic Cheese Slice","description":"NY-style thin crust pizza with tomato sauce and mozzarella","price":4.50,"dietary":["vegetarian"]},
  {"name":"Pepperoni Slice","description":"Crispy pepperoni on signature tomato sauce and mozzarella","price":5.00,"dietary":[]},
  {"name":"Veggie Slice","description":"Roasted peppers mushrooms onions and olives on tomato sauce","price":5.50,"dietary":["vegetarian","vegan"]},
  {"name":"Calzone","description":"Folded pizza stuffed with ricotta and fresh mozzarella","price":9.00,"dietary":["vegetarian"]}
],"dietary_options":["vegetarian","vegan"],"price_range":"$"}$q$::jsonb
WHERE name = 'Joe''s Pizza';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Pastrami Sandwich","description":"Hand-sliced pastrami on rye with spicy brown mustard","price":24.95,"dietary":["kosher"]},
  {"name":"Corned Beef Sandwich","description":"Lean corned beef piled high on fresh rye bread","price":22.95,"dietary":["kosher"]},
  {"name":"Matzo Ball Soup","description":"Classic chicken broth with fluffy oversized matzo balls","price":12.00,"dietary":["kosher"]},
  {"name":"Potato Knish","description":"Baked potato knish with caramelized onions","price":6.50,"dietary":["vegetarian","kosher"]}
],"dietary_options":["kosher","vegetarian"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Katz''s Delicatessen';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Tuna Tartare","description":"Barely cooked yellowfin tuna with chive oil and ossetra caviar","price":42.00,"dietary":["gluten-free"]},
  {"name":"Lobster Bisque","description":"Creamy bisque with Maine lobster medallions and fresh tarragon","price":38.00,"dietary":["gluten-free"]},
  {"name":"Pan-Roasted Cod","description":"Atlantic cod with Spanish chorizo broth and littleneck clams","price":52.00,"dietary":["gluten-free"]},
  {"name":"Poached Halibut","description":"Delicate halibut in warm herb vinaigrette with micro greens","price":55.00,"dietary":["gluten-free"]}
],"dietary_options":["gluten-free"],"price_range":"$$$$"}$q$::jsonb
WHERE name = 'Le Bernardin';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Plant-Based Tasting Menu","description":"Fully vegan seasonal tasting celebrating local produce and grains","price":335.00,"dietary":["vegetarian","vegan","gluten-free"]},
  {"name":"Duck Breast","description":"Roasted Hudson Valley duck with lavender honey and root vegetables","price":68.00,"dietary":["gluten-free"]},
  {"name":"Beet Tartare","description":"Chilled beet with sunflower seeds horseradish and herb oil","price":32.00,"dietary":["vegetarian","vegan","gluten-free"]},
  {"name":"Artisan Cheese Selection","description":"Curated seasonal American artisan cheeses with fruit compote","price":42.00,"dietary":["vegetarian","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$$$"}$q$::jsonb
WHERE name = 'Eleven Madison Park';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Hand-Ripped Noodles","description":"Thick liang pi noodles with chili oil black vinegar and cucumber","price":12.95,"dietary":["vegan"]},
  {"name":"Spicy Cumin Lamb Noodles","description":"Wide hand-pulled noodles tossed with cumin-spiced lamb and peppers","price":14.95,"dietary":[]},
  {"name":"Lamb Burger","description":"Slow-braised lamb in toasted sesame bun with Sichuan peppers","price":9.95,"dietary":["halal"]},
  {"name":"Pork Liang Pi","description":"Cold skin noodles with shredded pork cucumber and chili sauce","price":11.95,"dietary":[]}
],"dietary_options":["vegan","halal"],"price_range":"$"}$q$::jsonb
WHERE name = 'Xi''an Famous Foods';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Porterhouse Steak","description":"USDA prime dry-aged porterhouse for two with signature steak sauce","price":119.00,"dietary":["gluten-free"]},
  {"name":"Thick-Cut Bacon","description":"Signature slab bacon appetizer crispy smoked and caramelized","price":19.95,"dietary":["gluten-free"]},
  {"name":"Creamed Spinach","description":"Rich creamed spinach with nutmeg a Peter Luger classic side","price":16.00,"dietary":["vegetarian","gluten-free"]},
  {"name":"German Fried Potatoes","description":"Crispy fried potatoes with onions and fresh herbs","price":14.00,"dietary":["vegetarian","vegan","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$$$"}$q$::jsonb
WHERE name = 'Peter Luger Steak House';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Ricotta Gnocchi","description":"House-made gnocchi with spring peas pesto and fresh mint","price":29.00,"dietary":["vegetarian"]},
  {"name":"Grilled Atlantic Salmon","description":"Scottish salmon with lemon beurre blanc and asparagus","price":42.00,"dietary":["gluten-free"]},
  {"name":"Roasted Beet Salad","description":"Golden beets with aged goat cheese toasted hazelnuts and arugula","price":22.00,"dietary":["vegetarian","gluten-free"]},
  {"name":"Duck Confit","description":"Slow-cooked duck leg with French lentils and seasonal root vegetables","price":44.00,"dietary":["gluten-free"]}
],"dietary_options":["vegetarian","gluten-free"],"price_range":"$$$"}$q$::jsonb
WHERE name = 'Gramercy Tavern';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Black Cod Miso","description":"Signature miso-marinated black cod flaky sweet and caramelized","price":46.00,"dietary":["gluten-free"]},
  {"name":"Yellowtail Sashimi","description":"Fresh yellowtail with jalapeño ponzu and micro cilantro","price":32.00,"dietary":["gluten-free"]},
  {"name":"Rock Shrimp Tempura","description":"Lightly battered rock shrimp with creamy spicy sauce","price":28.00,"dietary":[]},
  {"name":"Edamame","description":"Steamed edamame pods with coarse sea salt","price":9.00,"dietary":["vegetarian","vegan","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$$$"}$q$::jsonb
WHERE name = 'Nobu';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"ShackBurger","description":"Beef patty with crisp lettuce tomato and signature ShackSauce","price":9.99,"dietary":[]},
  {"name":"SmokeShack","description":"Cheeseburger with crispy applewood smoked bacon and cherry peppers","price":11.49,"dietary":[]},
  {"name":"Shroom Burger","description":"Crispy portobello mushroom stuffed with muenster and cheddar cheese","price":10.99,"dietary":["vegetarian"]},
  {"name":"Crinkle-Cut Fries","description":"Crispy crinkle-cut fries with optional ShackSauce dip","price":4.99,"dietary":["vegetarian","vegan","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$"}$q$::jsonb
WHERE name = 'Shake Shack Madison Sq';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Pork Ramen","description":"Rich tonkotsu broth with chashu pork belly soft egg and nori","price":21.00,"dietary":[]},
  {"name":"Veggie Ramen","description":"Shiitake dashi broth with roasted vegetables crispy tofu and miso","price":19.00,"dietary":["vegetarian","vegan"]},
  {"name":"Steamed Pork Buns","description":"Fluffy steamed buns with slow-roasted pork belly hoisin and pickles","price":14.00,"dietary":[]},
  {"name":"Spicy Miso Ramen","description":"Red miso broth with chicken corn butter and sesame seeds","price":22.00,"dietary":[]}
],"dietary_options":["vegetarian","vegan"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Momofuku Noodle Bar';

-- ── Brooklyn ─────────────────────────────────────────────────────────────────

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Bee Sting Pizza","description":"Tomato mozzarella soppressata chili flakes and local honey","price":24.00,"dietary":[]},
  {"name":"Margherita Pizza","description":"San Marzano tomatoes fresh mozzarella and torn basil leaves","price":20.00,"dietary":["vegetarian"]},
  {"name":"White Pizza","description":"Ricotta fresh mozzarella roasted garlic olive oil no tomato sauce","price":21.00,"dietary":["vegetarian"]},
  {"name":"Vegan Pizza","description":"Tomato sauce seasonal roasted vegetables fresh herbs no dairy","price":22.00,"dietary":["vegetarian","vegan"]}
],"dietary_options":["vegetarian","vegan"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Roberta''s';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Coal-Fired Cheese Pizza","description":"Classic coal-fired pie with fresh mozzarella and crushed tomatoes","price":22.00,"dietary":["vegetarian"]},
  {"name":"Pepperoni Pizza","description":"Coal-fired pie with house-made pepperoni and fresh mozzarella","price":25.00,"dietary":[]},
  {"name":"BBQ Chicken Pizza","description":"Grilled chicken caramelized onions and tangy BBQ sauce","price":26.00,"dietary":[]},
  {"name":"Ricotta Calzone","description":"Stuffed with fresh ricotta mozzarella and your choice of toppings","price":19.00,"dietary":["vegetarian"]}
],"dietary_options":["vegetarian"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Grimaldi''s Pizzeria';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Original New York Cheesecake","description":"Famous ultra-creamy New York-style plain cheesecake","price":10.95,"dietary":["vegetarian"]},
  {"name":"Junior Burger","description":"8oz beef patty with lettuce tomato onion and house sauce on brioche","price":18.95,"dietary":[]},
  {"name":"Chicken Matzo Ball Soup","description":"Rich chicken broth with two oversized fluffy matzo balls","price":9.95,"dietary":["kosher"]},
  {"name":"Buttermilk Pancakes","description":"Fluffy buttermilk pancakes with maple syrup and whipped butter","price":14.95,"dietary":["vegetarian"]}
],"dietary_options":["vegetarian","kosher"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Junior''s Restaurant';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Whole Steamed Maine Lobster","description":"Live Maine lobster with drawn butter lemon and fresh herbs","price":78.00,"dietary":["gluten-free"]},
  {"name":"Vegetarian Tasting Menu","description":"Seasonal plant-based tasting with local produce and edible flowers","price":135.00,"dietary":["vegetarian","gluten-free"]},
  {"name":"Pan-Seared Diver Scallops","description":"Diver scallops with cauliflower puree black truffle and microgreens","price":56.00,"dietary":["gluten-free"]},
  {"name":"Dry-Aged Prime Ribeye","description":"40-day dry-aged prime beef ribeye with bone marrow and horseradish","price":72.00,"dietary":["gluten-free"]}
],"dietary_options":["vegetarian","gluten-free"],"price_range":"$$$$"}$q$::jsonb
WHERE name = 'The River Café';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Smash Burger","description":"Double beef smash patty with American cheese pickles and special sauce","price":12.00,"dietary":[]},
  {"name":"Vegan Jackfruit Tacos","description":"Braised jackfruit carnitas with salsa verde and pickled red onions","price":11.00,"dietary":["vegetarian","vegan","gluten-free"]},
  {"name":"Smoked Beef Brisket","description":"Slow-smoked Texas-style brisket on brioche with creamy coleslaw","price":16.00,"dietary":[]},
  {"name":"Ramen Burger","description":"Grass-fed beef patty in crispy ramen noodle bun with soy glaze","price":14.00,"dietary":[]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$"}$q$::jsonb
WHERE name = 'Smorgasburg';

-- ── Queens ───────────────────────────────────────────────────────────────────

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Pad Thai","description":"Classic stir-fried rice noodles with shrimp egg bean sprouts and peanuts","price":15.95,"dietary":["gluten-free"]},
  {"name":"Vegetarian Green Curry","description":"Coconut milk green curry with Thai eggplant bamboo shoots and tofu","price":16.95,"dietary":["vegetarian","vegan","gluten-free"]},
  {"name":"Tom Yum Soup","description":"Spicy lemongrass and galangal broth with mushrooms and lime","price":12.95,"dietary":["vegetarian","vegan","gluten-free"]},
  {"name":"Mango Sticky Rice","description":"Sweet glutinous rice with fresh ripe mango and coconut cream","price":8.95,"dietary":["vegetarian","vegan","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Sripraphai';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Pork Soup Dumplings","description":"Delicate pork xiao long bao with hot broth inside thin wrappers","price":12.95,"dietary":[]},
  {"name":"Crab Roe Soup Dumplings","description":"Premium crab roe and pork dumplings in gossamer-thin wrappers","price":16.95,"dietary":[]},
  {"name":"Pan-Fried Vegetable Dumplings","description":"Crispy-bottomed dumplings with mushroom cabbage and tofu filling","price":10.95,"dietary":["vegetarian","vegan"]},
  {"name":"Shrimp Fried Rice","description":"Wok-tossed jasmine rice with fresh shrimp eggs and scallions","price":14.95,"dietary":["gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Nan Xiang Xiao Long Bao';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Chicken Tikka Masala","description":"Tender chicken in creamy tomato-based curry with fenugreek and cream","price":18.95,"dietary":["halal","gluten-free"]},
  {"name":"Vegetarian Thali","description":"Complete meal with dal makhani sabzi basmati rice and fresh roti","price":16.95,"dietary":["vegetarian","vegan","halal"]},
  {"name":"Lamb Biryani","description":"Fragrant basmati rice with spiced halal lamb caramelized onions and saffron","price":21.95,"dietary":["halal","gluten-free"]},
  {"name":"Samosa Chaat","description":"Crispy samosas topped with tamarind chutney yogurt and chaat masala","price":9.95,"dietary":["vegetarian","halal"]}
],"dietary_options":["vegetarian","vegan","gluten-free","halal"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Jackson Diner';

-- ── New Jersey ───────────────────────────────────────────────────────────────

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Tagliatelle Bolognese","description":"Slow-cooked beef and pork ragu with egg pasta and aged Parmigiano","price":32.00,"dietary":[]},
  {"name":"Wild Mushroom Risotto","description":"Arborio rice with porcini and chanterelle mushrooms white wine and truffle","price":28.00,"dietary":["vegetarian","gluten-free"]},
  {"name":"Neapolitan Margherita Pizza","description":"San Marzano tomatoes buffalo mozzarella fresh basil and extra virgin olive oil","price":26.00,"dietary":["vegetarian"]},
  {"name":"Branzino al Forno","description":"Whole roasted Mediterranean sea bass with lemon capers and fresh herbs","price":44.00,"dietary":["gluten-free"]}
],"dietary_options":["vegetarian","gluten-free"],"price_range":"$$$"}$q$::jsonb
WHERE name = 'Battello';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Oysters on the Half Shell","description":"Selection of East and West Coast oysters with mignonette and cocktail sauce","price":36.00,"dietary":["gluten-free"]},
  {"name":"Maine Lobster Roll","description":"Cold Maine lobster salad with celery mayo on a toasted New England bun","price":42.00,"dietary":[]},
  {"name":"New England Clam Chowder","description":"Creamy chowder with clams potatoes and house-made oyster crackers","price":16.00,"dietary":[]},
  {"name":"Grilled Seasonal Vegetables","description":"Charred seasonal vegetables with romesco sauce and fresh herb salad","price":24.00,"dietary":["vegetarian","vegan","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$$"}$q$::jsonb
WHERE name = 'Maritime Parc';

-- ── Far away ─────────────────────────────────────────────────────────────────

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Farm-Roasted Chicken","description":"Whole free-range chicken with seasonal roasted vegetables and pan jus","price":28.00,"dietary":["gluten-free"]},
  {"name":"Wild Mushroom Toast","description":"Toasted sourdough with forest mushrooms fresh ricotta and thyme","price":16.00,"dietary":["vegetarian"]},
  {"name":"Grass-Fed Burger","description":"Local grass-fed beef with aged cheddar aioli and house-made pickles","price":22.00,"dietary":[]},
  {"name":"Seasonal Vegetable Plate","description":"Rotating local vegetables with seasonal vinaigrette and seeds","price":19.00,"dietary":["vegetarian","vegan","gluten-free"]}
],"dietary_options":["vegetarian","vegan","gluten-free"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Juniper';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Dungeness Crab","description":"Whole steamed Dungeness crab cracked and served with drawn butter","price":48.00,"dietary":["gluten-free"]},
  {"name":"New England Clam Chowder","description":"Creamy clam chowder in a sourdough bread bowl with oyster crackers","price":14.00,"dietary":[]},
  {"name":"Grilled Atlantic Swordfish","description":"Fresh swordfish steak with tropical mango salsa and rice pilaf","price":36.00,"dietary":["gluten-free"]},
  {"name":"Fish and Chips","description":"Beer-battered Atlantic cod with hand-cut fries and house tartar sauce","price":22.00,"dietary":[]}
],"dietary_options":["gluten-free"],"price_range":"$$"}$q$::jsonb
WHERE name = 'The Original Crab Shanty';

UPDATE restaurants SET menu = $q${"items":[
  {"name":"Pollo a la Brasa","description":"Peruvian-style rotisserie chicken marinated in aji panca with aji verde sauce","price":18.95,"dietary":["gluten-free"]},
  {"name":"Lomo Saltado","description":"Stir-fried beef tenderloin with tomatoes onions and crispy fries","price":22.95,"dietary":["gluten-free"]},
  {"name":"Quinoa Bowl","description":"Andean quinoa with roasted vegetables and creamy huancaina cheese sauce","price":16.95,"dietary":["vegetarian","gluten-free"]},
  {"name":"Ceviche Mixto","description":"Fresh fish and seafood marinated in tiger milk with Peruvian corn and sweet potato","price":19.95,"dietary":["gluten-free"]}
],"dietary_options":["vegetarian","gluten-free"],"price_range":"$$"}$q$::jsonb
WHERE name = 'Pio Pio';

-- ─────────────────────────────────────────────────────────────────────────────
-- Compute tsvector from: restaurant name + cuisine + all menu item names + descriptions
-- This single UPDATE populates the GIN FTS index for all 23 restaurants.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE restaurants
SET menu_search_vector = to_tsvector('english',
    name || ' ' || COALESCE(cuisine, '') || ' ' ||
    (SELECT string_agg(
         item->>'name' || ' ' || COALESCE(item->>'description', ''),
         ' '
     )
     FROM jsonb_array_elements(menu->'items') AS item
    )
)
WHERE menu IS NOT NULL;
