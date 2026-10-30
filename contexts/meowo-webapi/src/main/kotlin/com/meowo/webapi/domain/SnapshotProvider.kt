package com.meowo.webapi.domain

/** Where the HTTP layer gets its data from. Implemented by the infrastructure layer. */
fun interface SnapshotProvider {
    fun current(): WorldSnapshot
}
