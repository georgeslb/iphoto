import React from 'react';
import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet';
import L from 'leaflet';
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

// leaflet's default marker icon references marker-icon.png/marker-icon-2x.png/marker-shadow.png
// via relative URLs baked into its own bundled CSS/JS, which break once bundled through Vite (a
// well-documented react-leaflet + Vite issue) - markers render as broken-image icons instead.
// Re-pointing L.Icon.Default at the bundler-resolved URLs fixes it for every <Marker> app-wide.
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});

function MapView({ mapAssets, handlePhotoClick }) {
  return (
    <div className="map-view-container">
      <MapContainer center={[20, 0]} zoom={2} zoomControl={false}>
        <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
        {mapAssets.map(asset => (
            <Marker key={asset.id} position={[asset.latitude, asset.longitude]}>
              <Popup><div className="map-popup-asset" onClick={() => handlePhotoClick(asset)}><img src={`/api/photos/${asset.id}/thumbnail`} alt="Asset" style={{ width: '120px', borderRadius: '4px' }} /></div></Popup>
            </Marker>
        ))}
      </MapContainer>
    </div>
  );
}

export default MapView;