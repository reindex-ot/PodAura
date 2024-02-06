package com.skyd.anivu.model.bean

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A [feed] contains many [articles].
 */
data class FeedWithArticleBean(
    @Embedded
    var feed: FeedBean,
    @Relation(
        parentColumn = FeedBean.URL_COLUMN,
        entityColumn = ArticleBean.FEED_URL_COLUMN,
    )
    var articles: List<ArticleWithEnclosureBean>,
)
