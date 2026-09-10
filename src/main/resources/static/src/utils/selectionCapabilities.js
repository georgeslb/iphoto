// Pure, client-side port of what used to be two backend round-trips (POST /api/photos/selected
// + POST /api/menu/selected). These flags ONLY control which Header/Lightbox action buttons
// render - every mutating endpoint enforces its own authorization server-side, so button
// visibility never needs a network call. See specification.md's Header section.
//
// The photo-level rules mirror the old PhotoServiceImpl.getSelection. The menu-level "delete/
// restore selected X" flags DON'T mirror the old MenuServiceImpl.getSelection: that had them
// inverted (true only when the id list was EMPTY), which - since Header renders those buttons
// inside its `selectionMode` block and passes the selected ids straight through - meant they
// never actually appeared for a real selection. They're true here exactly when there IS a
// selection the corresponding bulk action can act on.

const EMPTY_MENU_SELECTION = {
  canUpload: false,
  canCreateAlbum: false,
  canDeleteAlbum: false,
  canCreateSpace: false,
  canDeleteSpace: false,
  canPermanentlyDeleteSpace: false,
  canRestoreSpace: false,
  canAddAAShareLinkToAlbum: false,
};

const EMPTY_SELECTION = {
  menuSelection: EMPTY_MENU_SELECTION,
  canBeDeleted: false,
  canBePermanentlyDeleted: false,
  canBeMovedToMain: false,
  canBeMovedToAlbum: false,
  canBeRemovedFromAlbum: false,
  canBeModified: false,
  canBeSetAsCover: false,
  canBeRestored: false,
  canEditDate: false,
  canDownload: false,
};

// menu: 'library' | 'album' | 'albums' | 'space' | 'spaces' | 'garbage' | 'lightbox'
// selection:
//   hasAlbumSelected        - >=1 album row selected on the /albums list
//   hasNonMainSpaceSelected - >=1 selected space on the /spaces list that is NOT the MAIN space
//                             (the MAIN space can never be removed - the backend also refuses it)
//   hasGarbageSpaceSelected - >=1 trashed space selected on /garbage
function computeMenuSelection(menu, { hasAlbumSelected, hasNonMainSpaceSelected, hasGarbageSpaceSelected }) {
  const m = { ...EMPTY_MENU_SELECTION };
  if (menu === 'album' || menu === 'space' || menu === 'library') {
    m.canUpload = true;
    if (menu === 'album') m.canAddAAShareLinkToAlbum = true;
  }
  if (menu === 'albums') {
    m.canCreateAlbum = true;
    m.canDeleteAlbum = hasAlbumSelected;
  }
  if (menu === 'spaces') {
    m.canCreateSpace = true;
    m.canDeleteSpace = hasNonMainSpaceSelected;
  }
  if (menu === 'garbage' && hasGarbageSpaceSelected) {
    m.canRestoreSpace = true;
    m.canPermanentlyDeleteSpace = true;
  }
  return m;
}

// context:
//   menu                       - see above
//   currentSpaceIsMain         - boolean | null: whether the single space this view is scoped to
//                                is the MAIN space. Only meaningful for menu === 'space'
//                                (elsewhere the old backend never resolved a single space).
//   albumTargeted              - boolean: exactly one existing album is the action target
//                                (an /albums/:id route, or exactly one album row selected).
//   selectedCount              - number of selected photos (1 when the Lightbox is open).
//   singleSelectedInMainSpace  - boolean | null: for the Lightbox, whether the one open photo is
//                                in the MAIN space (drives "Move to Main"). null when unknown.
//   hasAlbumSelected / hasNonMainSpaceSelected / hasGarbageSpaceSelected - as in computeMenuSelection.
export function computeSelectionCapabilities({
  menu,
  currentSpaceIsMain = null,
  albumTargeted = false,
  selectedCount = 0,
  singleSelectedInMainSpace = null,
  hasAlbumSelected = false,
  hasNonMainSpaceSelected = false,
  hasGarbageSpaceSelected = false,
}) {
  const menuSelection = computeMenuSelection(menu, { hasAlbumSelected, hasNonMainSpaceSelected, hasGarbageSpaceSelected });

  if (!selectedCount) {
    return { ...EMPTY_SELECTION, menuSelection };
  }

  const isGarbageMenu = menu.includes('garbage');
  const isAlbumMenu = menu.includes('album');

  let canBeDeleted = false;
  let canBePermanentlyDeleted = false;
  let canBeMovedToMain = false;
  let canBeMovedToAlbum = false;
  let canBeRemovedFromAlbum = false;
  let canBeModified = false;
  let canBeSetAsCover = false;
  let canBeRestored = false;
  // Bulk "Edit Date Taken" / "Download" - true for any non-empty selection (a garbage photo
  // still has a real dateTaken and a real file).
  const canEditDate = true;
  const canDownload = true;

  if (!isGarbageMenu) canBeDeleted = true;

  // The old backend resolved a single space only for menu === 'space'; keep that.
  const oneMainSpace = menu === 'space' && currentSpaceIsMain === true;
  const oneNonMainSpace = menu === 'space' && currentSpaceIsMain === false;

  // Mirrors the Java `!isGarbageMenu && (oneMainSpace) || menu === 'library'` precedence exactly.
  if ((!isGarbageMenu && oneMainSpace) || menu === 'library') {
    canBeMovedToAlbum = true;
    if (isAlbumMenu && albumTargeted) canBeRemovedFromAlbum = true;
  }

  if (menu === 'lightbox') {
    canBeModified = true;
    canBeMovedToAlbum = true;
    canBeRemovedFromAlbum = true;
    if (selectedCount === 1 && singleSelectedInMainSpace === false) {
      canBeMovedToMain = true;
    }
  }

  if (oneNonMainSpace && !isGarbageMenu) canBeMovedToMain = true;

  if (isGarbageMenu) {
    canBePermanentlyDeleted = true;
    canBeRestored = true;
  }

  if (isAlbumMenu && albumTargeted) canBeSetAsCover = true;

  return {
    menuSelection,
    canBeDeleted,
    canBePermanentlyDeleted,
    canBeMovedToMain,
    canBeMovedToAlbum,
    canBeRemovedFromAlbum,
    canBeModified,
    canBeSetAsCover,
    canBeRestored,
    canEditDate,
    canDownload,
  };
}
