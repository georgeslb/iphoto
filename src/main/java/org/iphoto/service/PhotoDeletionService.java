package org.iphoto.service;

import java.util.UUID;

public interface PhotoDeletionService {

    void permanentDeleteAssets(UUID photoId);
}
