package com.meowo.webapi.infrastructure

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * The wire format.
 *
 * Gson belongs to the codec and not to the domain: the domain models stay plain data classes
 * with no annotation on them, which is what keeps `domain/` free of anything the server owns.
 */
class JsonCodec {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(value: Any): String = gson.toJson(value)
}
