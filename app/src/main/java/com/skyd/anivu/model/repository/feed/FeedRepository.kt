package com.skyd.anivu.model.repository.feed

import android.net.Uri
import android.webkit.URLUtil
import com.skyd.anivu.appContext
import com.skyd.anivu.base.BaseRepository
import com.skyd.anivu.config.Const
import com.skyd.anivu.ext.copyTo
import com.skyd.anivu.ext.dataStore
import com.skyd.anivu.ext.isLocal
import com.skyd.anivu.ext.isNetwork
import com.skyd.anivu.ext.put
import com.skyd.anivu.model.bean.FeedBean
import com.skyd.anivu.model.bean.GroupVo
import com.skyd.anivu.model.bean.GroupVo.Companion.isDefaultGroup
import com.skyd.anivu.model.db.dao.ArticleDao
import com.skyd.anivu.model.db.dao.FeedDao
import com.skyd.anivu.model.db.dao.GroupDao
import com.skyd.anivu.model.preference.appearance.feed.FeedDefaultGroupExpandPreference
import com.skyd.anivu.model.repository.RssHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject

class FeedRepository @Inject constructor(
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val reorderGroupRepository: ReorderGroupRepository,
    private val rssHelper: RssHelper,
) : BaseRepository() {
    suspend fun requestGroupAnyList(): Flow<List<Any>> {
        return combine(
            groupDao.getGroupWithFeeds(),
            groupDao.getGroupIds(),
        ) { groupList, groupIds ->
            groupList to feedDao.getFeedsNotIn(groupIds)
        }.map { (groupList, defaultFeeds) ->
            mutableListOf<Any>().apply {
                add(GroupVo.DefaultGroup)
                addAll(defaultFeeds)
                reorderGroupRepository.sortGroupWithFeed(groupList).forEach { group ->
                    add(group.group.toVo())
                    addAll(group.feeds)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun clearGroupArticles(groupId: String): Flow<Int> {
        return flow {
            val realGroupId = if (groupId == GroupVo.DEFAULT_GROUP_ID) null else groupId
            emit(articleDao.deleteArticlesInGroup(realGroupId))
        }
    }

    suspend fun deleteGroup(groupId: String): Flow<Int> {
        return if (groupId == GroupVo.DEFAULT_GROUP_ID) flowOf(0)
        else flowOf(groupDao.removeGroupWithFeed(groupId)).flowOn(Dispatchers.IO)
    }

    suspend fun renameGroup(groupId: String, name: String): Flow<GroupVo> {
        return if (groupId == GroupVo.DEFAULT_GROUP_ID) flow {
            emit(GroupVo.DefaultGroup)
        } else flow {
            groupDao.renameGroup(groupId, name)
            emit(groupDao.getGroupById(groupId).toVo())
        }.flowOn(Dispatchers.IO)
    }

    suspend fun moveGroupFeedsTo(fromGroupId: String, toGroupId: String): Flow<Int> {
        val realFromGroupId = if (fromGroupId == GroupVo.DEFAULT_GROUP_ID) null else fromGroupId
        val realToGroupId = if (toGroupId == GroupVo.DEFAULT_GROUP_ID) null else toGroupId
        return flowOf(groupDao.moveGroupFeedsTo(realFromGroupId, realToGroupId))
            .flowOn(Dispatchers.IO)
    }

    suspend fun setFeed(feedBean: FeedBean): Flow<Unit> {
        return flowOf(feedDao.setFeed(feedBean))
            .flowOn(Dispatchers.IO)
    }

    suspend fun setFeed(
        url: String,
        groupId: String?,
        nickname: String?,
    ): Flow<FeedBean> {
        return flow {
            val realNickname = if (nickname.isNullOrBlank()) null else nickname
            val realGroupId =
                if (groupId.isNullOrBlank() || groupId == GroupVo.DEFAULT_GROUP_ID) null else groupId
            val feedWithArticleBean = rssHelper.searchFeed(url = url).run {
                copy(
                    feed = feed.copy(
                        groupId = realGroupId,
                        nickname = realNickname,
                    )
                )
            }
            feedDao.setFeedWithArticle(feedWithArticleBean)
            emit(feedWithArticleBean.feed)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun editFeedUrl(
        oldUrl: String,
        newUrl: String,
    ): Flow<FeedBean> = flow {
        val oldFeed = feedDao.getFeed(oldUrl)
        var newFeed = oldFeed
        if (oldUrl != newUrl) {
            val feedWithArticleBean = rssHelper.searchFeed(url = newUrl).run {
                newFeed = feed.copy(
                    groupId = oldFeed.groupId,
                    nickname = oldFeed.nickname,
                    customDescription = oldFeed.customDescription,
                    customIcon = oldFeed.customIcon,
                )
                copy(feed = newFeed)
            }
            feedDao.removeFeed(oldUrl)
            feedDao.setFeedWithArticle(feedWithArticleBean)
        }
        emit(newFeed)
    }.flowOn(Dispatchers.IO)

    suspend fun editFeedNickname(
        url: String,
        nickname: String?,
    ): Flow<FeedBean> = flow {
        val realNickname = if (nickname.isNullOrBlank()) null else nickname
        feedDao.updateFeed(feedDao.getFeed(url).copy(nickname = realNickname))
        emit(feedDao.getFeed(url))
    }.flowOn(Dispatchers.IO)

    suspend fun editFeedGroup(
        url: String,
        groupId: String?,
    ): Flow<FeedBean> = flow {
        val realGroupId =
            if (groupId.isNullOrBlank() || groupId == GroupVo.DEFAULT_GROUP_ID) null
            else groupId

        feedDao.updateFeed(feedDao.getFeed(url).copy(groupId = realGroupId))
        emit(feedDao.getFeed(url))
    }.flowOn(Dispatchers.IO)

    suspend fun editFeedCustomDescription(
        url: String,
        customDescription: String?,
    ): Flow<FeedBean> = flow {
        feedDao.updateFeed(feedDao.getFeed(url).copy(customDescription = customDescription))
        emit(feedDao.getFeed(url))
    }.flowOn(Dispatchers.IO)

    suspend fun editFeedCustomIcon(
        url: String,
        customIcon: Uri?,
    ): Flow<FeedBean> = flow {
        var filePath: String? = null
        if (customIcon != null) {
            if (customIcon.isLocal()) {
                customIcon.copyTo(
                    File(Const.FEED_ICON_DIR, UUID.randomUUID().toString()).apply {
                        filePath = path
                    }
                )
            } else if (customIcon.isNetwork()) {
                filePath = customIcon.toString()
            }
        }
        val oldFeed = feedDao.getFeed(url)
        oldFeed.customIcon?.let { icon -> tryDeleteFeedIconFile(icon) }
        feedDao.updateFeed(oldFeed.copy(customIcon = filePath))
        emit(feedDao.getFeed(url))
    }.flowOn(Dispatchers.IO)

    suspend fun editFeedSortXmlArticlesOnUpdate(
        url: String,
        sort: Boolean,
    ): Flow<FeedBean> = flow {
        feedDao.updateFeedSortXmlArticlesOnUpdate(feedUrl = url, sort = sort)
        emit(feedDao.getFeed(url))
    }.flowOn(Dispatchers.IO)

    suspend fun removeFeed(url: String): Flow<Int> {
        return flow {
            feedDao.getFeed(url).customIcon?.let { icon -> tryDeleteFeedIconFile(icon) }
            emit(feedDao.removeFeed(url))
        }.flowOn(Dispatchers.IO)
    }

    suspend fun clearFeedArticles(url: String): Flow<Int> {
        return flow {
            emit(articleDao.deleteArticleInFeed(url))
        }.flowOn(Dispatchers.IO)
    }

    suspend fun createGroup(group: GroupVo): Flow<Unit> {
        return flow {
            if (groupDao.containsByName(group.name) == 0) {
                emit(groupDao.setGroup(group.toPo()))
            } else {
                emit(Unit)
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun changeGroupExpanded(group: GroupVo, expanded: Boolean): Flow<Unit> {
        return flow {
            if (group.isDefaultGroup()) {
                appContext.dataStore.put(
                    FeedDefaultGroupExpandPreference.key,
                    value = expanded,
                )
            } else {
                groupDao.changeGroupExpanded(group.groupId, expanded)
            }
            emit(Unit)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun readAllInGroup(groupId: String?): Flow<Int> {
        return flow {
            val realGroupId = if (groupId == GroupVo.DEFAULT_GROUP_ID) null else groupId
            emit(articleDao.readAllInGroup(realGroupId))
        }.flowOn(Dispatchers.IO)
    }

    suspend fun readAllInFeed(feedUrl: String): Flow<Int> {
        return flow {
            emit(articleDao.readAllInFeed(feedUrl))
        }.flowOn(Dispatchers.IO)
    }
}

fun tryDeleteFeedIconFile(path: String?) {
    if (path != null && !URLUtil.isNetworkUrl(path)) {
        try {
            File(path).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}