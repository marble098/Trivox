package com.trivox.client.config

/**
 * NordWhisper is a proprietary NordVPN transport. NordVPN has not published a
 * redistributable protocol core or an Android embedding API that a third-party
 * client can safely bundle. Detect it explicitly so Trivox never silently treats
 * a Whisper profile as WireGuard or reports a fake successful import.
 */
object NordWhisperCompatibility {
    fun reject(raw: String): Nothing {
        val scheme = raw.substringBefore("://", "nordwhisper")
        throw ConfigParseException(
            "$scheme profiles require NordVPN's official NordWhisper runtime; " +
                "no public embeddable core is available. Use the NordVPN " +
                "subscription option with NordLynx/WireGuard instead."
        )
    }
}
