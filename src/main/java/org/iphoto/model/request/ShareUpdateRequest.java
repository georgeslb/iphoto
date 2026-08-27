package org.iphoto.model.request;

// password: null/blank removes password protection, non-blank sets/replaces it.
public record ShareUpdateRequest(String password) {}
