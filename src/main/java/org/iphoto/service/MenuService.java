package org.iphoto.service;

import org.iphoto.model.MenuSelectionDTO;
import org.iphoto.model.request.MenuSelectionRequest;

public interface MenuService {

    MenuSelectionDTO getSelection(MenuSelectionRequest request);
}
