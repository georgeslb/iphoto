package org.iphoto.model.request;

// password: null/blank removes password protection, non-blank sets/replaces it.
// daysAvailable: positive integer extends availableUntil starting today.
public record ShareUpdateRequest(
    String password,
    Integer daysAvailable
) {
    public ShareUpdateRequest(String password) {
        this(password, null);
    }
}
