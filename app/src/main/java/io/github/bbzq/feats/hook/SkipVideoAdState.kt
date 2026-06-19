package io.github.bbzq.feats.hook

import io.github.bbzq.feats.BilibiliSponsorBlock

object SkipVideoAdState {
    @Volatile var durationMs: Long = 0L
    @Volatile var segments: List<BilibiliSponsorBlock.Segment> = emptyList()
}

