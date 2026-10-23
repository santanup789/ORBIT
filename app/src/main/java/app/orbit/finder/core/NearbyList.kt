package app.orbit.finder.core

import app.orbit.finder.radio.NearbyPeer

/** Keep radio discovery intact; hide trusted identities only from the connection list. */
fun unpairedNearby(peers: List<NearbyPeer>, friends: List<Friend>): List<NearbyPeer> =
    peers.filter { peer -> friends.none { it.trust == TrustState.TRUSTED && it.id.startsWith(peer.shortId) } }
