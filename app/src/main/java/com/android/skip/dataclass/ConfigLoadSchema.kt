package com.android.skip.dataclass

import android.graphics.Rect

data class LoadSkipText(
    val text: String,
    val activityName: String?,
    val length: Int?,
    val click: Rect?
)

data class LoadSkipId(
    val id: String,
    val activityName: String?,
    val click: Rect?
)

data class LoadSkipBound(
    val bound: Rect,
    val activityName: String?,
    val click: Rect?
)

data class ConfigLoadSchema(
    val packageName: String,
    val skipTexts: List<LoadSkipText>?,
    val skipIds: List<LoadSkipId>?,
    val skipBounds: List<LoadSkipBound>?
)
