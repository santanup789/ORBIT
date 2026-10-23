package app.orbit.finder

import app.orbit.finder.core.*
import app.orbit.finder.radio.NearbyPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyListTest {
    private val peer = NearbyPeer("0123456789abcdef", -60, 100L)
    private val other = NearbyPeer("fedcba9876543210", -70, 100L)
    private val friend = Friend(peer.shortId + "0123456789abcdef", "key", "Friend", DeviceIcon.PHONE, TrustState.TRUSTED)

    @Test fun trustedFriendExcludedWhileUnknownRemains() {
        assertEquals(listOf(other), unpairedNearby(listOf(peer, other), listOf(friend)))
    }
    @Test fun pendingRequestRemainsUntilAccepted() {
        assertEquals(listOf(peer), unpairedNearby(listOf(peer), listOf(friend.copy(trust = TrustState.PENDING))))
        assertEquals(emptyList<NearbyPeer>(), unpairedNearby(listOf(peer), listOf(friend)))
    }
    @Test fun forgottenFriendBecomesAvailableAgain() {
        assertEquals(listOf(peer), unpairedNearby(listOf(peer), emptyList()))
    }
}
