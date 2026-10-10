package com.tokenmon.mobile.core

import kotlinx.serialization.json.Json

/**
 * The single JSON reader used everywhere in the app.
 *
 * `ignoreUnknownKeys` is set for the benefit of the few reflective paths, but the
 * real tolerance comes from parsing into a [kotlinx.serialization.json.JsonElement]
 * tree and reading named keys — the app never binds a response to a fixed class,
 * so an added Hub field is a non-event.
 */
val LENIENT_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
