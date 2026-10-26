package com.meowo.mine

/**
 * Tunables for `/meowo mine` and the rare-material report. Read once in `start()`, so a reload
 * restarts the context and re-reads the registry from disk.
 */
data class MineSettings(
    /** Broadcast every find. Turning this off leaves `/meowo mine` working as a pure registry. */
    val announce: Boolean = true,
    /** Play a sound to the whole server with the announcement. */
    val sound: Boolean = true,
    /**
     * Give the very first find of each material a louder line. The report happens either way; this
     * only decides whether the first one is singled out, so the "first find" moment is not lost
     * once every find is announced.
     */
    val highlightFirst: Boolean = true,
    /**
     * Most ore blocks a single find may count, i.e. the vein size. Also a safety bound: the scan is
     * breadth-first, so a 0 disables it and a small cap keeps a huge artificial vein from walking
     * the whole chunk.
     */
    val maxVeinSize: Int = 32,
)
