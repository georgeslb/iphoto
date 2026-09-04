package org.iphoto.model.request;

public record DeviceLoginRequest(String username, String password, String deviceName) {
}
