package org.iphoto.model.request;

import java.util.List;

// Hex-digest MD5 hashes, in the same format TaskWorkerManager's upload-hashing already produces -
// callers (mobile camera-sync) must hash the same way for this to match.
public record CheckHashesRequest(List<String> hashes) {
}
