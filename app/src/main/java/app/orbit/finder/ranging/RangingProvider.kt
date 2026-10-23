package app.orbit.finder.ranging

import app.orbit.finder.core.Friend
import app.orbit.finder.core.Measurement
import kotlinx.coroutines.flow.Flow

interface RangingProvider {
    val id: String
    suspend fun isUsable(): Boolean
    fun measurements(friend: Friend, freshSessionData: ByteArray): Flow<Measurement>
    suspend fun stop()
}

/** Selection is capability-driven. No provider is allowed to synthesize distance or direction. */
class RangingCoordinator(private val providers: List<RangingProvider>) {
    suspend fun best(): RangingProvider? = providers.firstOrNull { it.isUsable() }
}
