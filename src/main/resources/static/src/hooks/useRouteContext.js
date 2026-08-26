export function useRouteContext(location) {
  const backgroundLocation = location.state?.backgroundLocation;
  const effectivePath = backgroundLocation?.pathname || location.pathname;
  const isLightboxOpen = location.pathname.startsWith('/asset/')
    || /^\/albums\/[^/]+\/asset\//.test(location.pathname)
    || /^\/spaces\/[^/]+\/asset\//.test(location.pathname);

  const currentAlbumId = effectivePath.startsWith('/albums/') ? effectivePath.split('/')[2] : null;
  const currentSpaceId = effectivePath.startsWith('/spaces/') ? effectivePath.split('/')[2] : null;
  const currentPersonId = effectivePath.startsWith('/persons/') ? effectivePath.split('/')[2] : null;
  const fromGarbage = location.state?.fromGarbage || backgroundLocation?.state?.fromGarbage || false;
  const selectedMenu = effectivePath === '/garbage'
    ? 'garbage'
    : (effectivePath.startsWith('/albums/') ? 'album' : (effectivePath.startsWith('/spaces/') ? 'space' : (effectivePath.startsWith('/persons/') ? 'person' : 'library')));

  return {
    backgroundLocation,
    effectivePath,
    isLightboxOpen,
    currentAlbumId,
    currentSpaceId,
    currentPersonId,
    fromGarbage,
    selectedMenu,
  };
}
