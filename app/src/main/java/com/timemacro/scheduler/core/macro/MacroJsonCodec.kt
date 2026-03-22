package com.timemacro.scheduler.core.macro

import kotlinx.serialization.json.Json

object MacroJsonCodec {
    val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
}

