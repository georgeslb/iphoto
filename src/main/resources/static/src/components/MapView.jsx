import React from 'react';
import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet';

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