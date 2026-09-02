package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Minimal asset reference as found in album membership / shared-link asset lists - only the id
// is needed for import purposes (the full detail is fetched separately per asset via
// ImmichClient.getAssetDetail when actually processing it).
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichAssetRefDTO(String id) {
}
