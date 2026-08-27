package org.iphoto.model;

public record PhotoSelectionDTO(MenuSelectionDTO menuSelection, boolean canBeDeleted, boolean canBePermanentlyDeleted, boolean canBeMovedToMain,
                                boolean canBeMovedToAlbum, boolean canBeRemovedFromAlbum, boolean canBeModified,
                                boolean canBeSetAsCover, boolean canBeRestored) {


}
