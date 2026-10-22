package com.fp.ytmp3

import android.net.Uri

data class FolderItem(
    val name: String,
    val uri: Uri,
    var checked: Boolean
)
