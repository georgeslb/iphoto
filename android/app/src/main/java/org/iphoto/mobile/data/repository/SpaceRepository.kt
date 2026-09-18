package org.iphoto.mobile.data.repository

import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.model.SpaceDTO

class SpaceRepository(private val spaceApi: SpaceApi) {
    suspend fun getSpaces(): List<SpaceDTO> {
        return spaceApi.list()
    }
}
