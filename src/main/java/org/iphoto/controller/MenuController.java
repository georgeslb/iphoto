package org.iphoto.controller;

import lombok.RequiredArgsConstructor;
import org.iphoto.model.MenuSelectionDTO;
import org.iphoto.model.request.MenuSelectionRequest;
import org.iphoto.service.MenuService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
public class MenuController {

    final MenuService menuService;

    @PostMapping("/selected")
    public ResponseEntity<MenuSelectionDTO> getSelection(@RequestBody MenuSelectionRequest request) {
        MenuSelectionDTO menuSelectionDTO = menuService.getSelection(request);
        return new ResponseEntity<>(menuSelectionDTO, HttpStatus.OK);
    }
}