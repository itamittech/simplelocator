package com.example.simplelocator.controller;

import com.example.simplelocator.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final RestaurantRepository restaurantRepository;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("totalRestaurants", restaurantRepository.count());
        return "index";
    }
}
