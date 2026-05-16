package org.iphoto.model;

import java.time.LocalDateTime;

public record UpdatePhotoDateRequest(
    LocalDateTime newDate
) {}
