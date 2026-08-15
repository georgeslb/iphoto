package org.iphoto.model;

public record MenuSelectionDTO(boolean canUpload, boolean canCreateAlbum, boolean canDeleteAlbum,
                               boolean canCreateSpace, boolean canDeleteSpace, boolean canPermanentlyDeleteSpace,
                               boolean canRestoreSpace, boolean canAddAAShareLinkToAlbum) {
    public MenuSelectionDTO {
    }

}
