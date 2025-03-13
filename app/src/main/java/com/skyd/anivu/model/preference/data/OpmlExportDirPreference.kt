package com.skyd.anivu.model.preference.data

import androidx.datastore.preferences.core.stringPreferencesKey
import com.skyd.anivu.base.BasePreference

object OpmlExportDirPreference : BasePreference<String> {
    private const val OPML_EXPORT_DIR = "opmlExportDir"

    override val default = ""
    override val key = stringPreferencesKey(OPML_EXPORT_DIR)
}
