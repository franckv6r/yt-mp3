package com.fp.ytmp3

import android.net.Uri

data class TrackItem(
    val title: String,
    val webUrl: String,
    var present: Boolean,
    var checked: Boolean,
    var localUri: Uri? = null
)
