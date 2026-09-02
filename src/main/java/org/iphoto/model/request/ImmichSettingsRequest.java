package org.iphoto.model.request;

// apiKey left blank/omitted means "keep the currently stored key, just update baseUrl" from the
// controller's perspective when connected is already true - see UserService#updateImmichSettings,
// which only overwrites the stored key when apiKey is non-blank. Send an explicit blank apiKey
// together with a blank baseUrl to disconnect entirely.
public record ImmichSettingsRequest(String baseUrl, String apiKey) {
}
