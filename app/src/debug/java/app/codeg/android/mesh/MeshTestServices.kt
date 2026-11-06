package app.codeg.android.mesh

import app.codeg.android.core.data.ServerRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the production graph for the opt-in local mesh fixture test. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MeshTestServices {
    fun serverRepository(): ServerRepository
}
