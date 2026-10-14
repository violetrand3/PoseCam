package com.posecam.core.sync

/**
 * What a recording is filed under. It used to be one of LabelNow's pipes (white / black / black-white); it is now a
 * marketplace campaign: [wire] is the campaign id (a lowercase UUID, still what is stored in the `cloud_sessions.pipe`
 * column and sent to the backend, as `campaignId`) and [label] is the campaign's title. The collector's accepted campaigns
 * come from the backend (`GET /v1/me/campaigns`, kept by [CampaignCatalog]); [DEFAULTS] is empty on purpose: with no
 * campaign known there is nothing to upload a recording for. Equal when the [wire] ids are: the label is display only.
 */
class Pipe(val wire: String, val label: String, val color: Int? = null) {
    override fun equals(other: Any?): Boolean = other is Pipe && other.wire == wire
    override fun hashCode(): Int = wire.hashCode()
    override fun toString(): String = "Pipe($wire)"

    companion object {
        val WHITE = Pipe("white", "White pipe", 0xFFF8FAFC.toInt())
        val BLACK = Pipe("black", "Black pipe", 0xFF111827.toInt())
        val BLACK_WHITE = Pipe("black-white", "Black/White pipe", 0xFF9CA3AF.toInt())

        /** No campaign known means no cloud upload; recording to the phone itself does not depend on it. */
        val DEFAULTS: List<Pipe> = emptyList()

        /** The values older app versions stored in the same column. They name no campaign: the recording must be filed again. */
        private val LEGACY_WIRES = setOf(WHITE.wire, BLACK.wire, BLACK_WHITE.wire)

        fun isLegacyPipe(value: String?): Boolean = value in LEGACY_WIRES

        /** A campaign id is a lowercase UUID, which is also a lowercase word joined by single hyphens. */
        fun isValidWire(value: String): Boolean = WIRE_RE.matches(value)

        /** "White pipes" -> "White pipe": the server names the category, the phone names one pipe. */
        fun short(label: String): String = label.replace(Regex("(?i)pipes$"), "pipe")

        fun fromWire(value: String?, among: List<Pipe> = DEFAULTS): Pipe? = among.firstOrNull { it.wire == value }

        private val WIRE_RE = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}
