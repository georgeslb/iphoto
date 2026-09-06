package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.iphoto.model.MenuSelectionDTO;
import org.iphoto.model.request.MenuSelectionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
@Slf4j
public class MenuServiceImpl implements MenuService {

    @Override
    public MenuSelectionDTO getSelection(MenuSelectionRequest request) { // Added UserEntity parameter
        boolean canUpload = false, canCreateAlbum  = false, canDeleteAlbum = false,
                canCreateSpace = false,  canDeleteSpace = false,  canPermanentlyDeleteSpace = false,
                canRestoreSpace = false,  canAddAAShareLinkToAlbum = false;

        if (request.menu().equals("album") || request.menu().equals("space") || request.menu().equals("library")) {
            canUpload = true;
            if (request.menu().equals("album")) {
                canAddAAShareLinkToAlbum = true;
            }
        }
        if (request.menu().equals("albums")) {
            canCreateAlbum = true;
            if (!CollectionUtils.isNotEmpty(request.albumIds())) {
                canDeleteAlbum = true;
            }
        }
        if (request.menu().equals("spaces")) {
            canCreateSpace = true;
            if (!CollectionUtils.isNotEmpty(request.spaceIds())) {
                canDeleteSpace = true;
            }
        }
        if (request.menu().equals("garbage")) {
            if (!CollectionUtils.isNotEmpty(request.spaceIds())) {
                canRestoreSpace = true;
                canPermanentlyDeleteSpace = true;
            }
        }
        return new MenuSelectionDTO(canUpload, canCreateAlbum, canDeleteAlbum, canCreateSpace, canDeleteSpace, canPermanentlyDeleteSpace, canRestoreSpace, canAddAAShareLinkToAlbum);

    }
}
