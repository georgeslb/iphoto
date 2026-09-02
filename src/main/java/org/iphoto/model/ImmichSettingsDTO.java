package org.iphoto.model;

// Never carries the decrypted API key back to the client - `connected` is a plain boolean so the
// frontend can show "connected"/"not connected" without ever seeing the key again once it's saved.
public record ImmichSettingsDTO(String baseUrl, boolean connected) {
}
