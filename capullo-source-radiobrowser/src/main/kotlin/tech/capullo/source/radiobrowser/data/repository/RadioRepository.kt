package tech.capullo.source.radiobrowser.data.repository

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.capullo.source.radiobrowser.data.api.RadioBrowserApi
import tech.capullo.source.radiobrowser.data.db.AppDatabase
import tech.capullo.source.radiobrowser.data.db.FavoriteEntity
import tech.capullo.source.radiobrowser.data.db.FavoriteGroupEntity
import tech.capullo.source.radiobrowser.data.model.Country
import tech.capullo.source.radiobrowser.data.model.Station
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class FavoritesBackup(
    val version: Int = 1,
    val groups: List<FavoriteGroupEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
)

class RadioRepository(private val db: AppDatabase, serverUrl: String = "https://de1.api.radio-browser.info/") {

    /**
     * App-DI convenience: build the favorites DB from a [Context] so consumers need not depend on Room
     * or reference [AppDatabase] - keeping Room an implementation detail of this library (parity with the
     * ktor client staying internal to capullo-audio's SnapcastControlClient).
     */
    constructor(context: Context, serverUrl: String = "https://de1.api.radio-browser.info/") :
        this(AppDatabase.getInstance(context), serverUrl)

    private var currentServerUrl = serverUrl
    private var api = RadioBrowserApi.create(serverUrl)

    fun setServerUrl(url: String) {
        if (url != currentServerUrl && url.isNotBlank()) {
            currentServerUrl = url
            api = RadioBrowserApi.create(url)
        }
    }

    suspend fun search(query: String, limit: Int = 40): List<Station> = api.searchStations(name = query, limit = limit)

    suspend fun getTopStations(limit: Int = 40): List<Station> = api.getTopStations(limit = limit)

    suspend fun getRandomStations(limit: Int = 10): List<Station> {
        // order=random on the server has a fixed seed - same result every call.
        // A client-picked random offset is the only way to get different stations each run.
        val maxOffset = (55000 - limit).coerceAtLeast(0)
        val offset = (0..maxOffset).random()
        return api.getRandomStations(limit = limit, offset = offset).shuffled()
    }

    suspend fun getByTag(tag: String): List<Station> = api.getStationsByTag(tag)

    suspend fun getCountries(): List<Country> = api.getCountries()

    suspend fun getStationsByCountry(country: String, limit: Int = 40): List<Station> = api.getStationsByCountry(country, limit)

    fun getFavorites(): Flow<List<FavoriteEntity>> = db.favoriteDao().getAll()

    fun getFavoriteUuids(): Flow<Set<String>> = db.favoriteDao().getAllUuids().map { it.toSet() }

    suspend fun toggleFavorite(station: Station) {
        val dao = db.favoriteDao()
        if (dao.exists(station.uuid) > 0) {
            dao.delete(station.uuid)
        } else {
            dao.insert(
                FavoriteEntity(
                    uuid = station.uuid,
                    name = station.name,
                    url = station.streamUrl,
                    favicon = station.favicon,
                    country = station.country,
                    tags = station.tags,
                    codec = station.codec,
                    bitrate = station.bitrate,
                ),
            )
        }
    }

    // --- Groups ---

    fun getGroups(): Flow<List<FavoriteGroupEntity>> = db.favoriteGroupDao().getAll()

    suspend fun createGroup(name: String, uuids: Set<String>): String {
        val id = UUID.randomUUID().toString()
        val groupDao = db.favoriteGroupDao()
        groupDao.insert(FavoriteGroupEntity(id = id, name = name, createdAt = System.currentTimeMillis()))
        uuids.forEachIndexed { i, uuid ->
            db.favoriteDao().updateGroupId(uuid, id)
            db.favoriteDao().updateSortOrder(uuid, i)
        }
        return id
    }

    suspend fun renameGroup(id: String, name: String) = db.favoriteGroupDao().rename(id, name)

    suspend fun deleteGroup(id: String) {
        db.favoriteDao().ungroupAll(id)
        db.favoriteGroupDao().delete(id)
    }

    suspend fun assignToGroup(uuids: Set<String>, groupId: String, startOrder: Int = 0) {
        uuids.forEachIndexed { i, uuid ->
            db.favoriteDao().updateGroupId(uuid, groupId)
            db.favoriteDao().updateSortOrder(uuid, startOrder + i)
        }
    }

    suspend fun unassignFromGroup(uuids: Set<String>) {
        uuids.forEach { db.favoriteDao().updateGroupId(it, "") }
    }

    suspend fun updateFavoriteSortOrder(uuid: String, sortOrder: Int) = db.favoriteDao().updateSortOrder(uuid, sortOrder)

    suspend fun updateGroupSortOrder(id: String, sortOrder: Int) = db.favoriteGroupDao().updateSortOrder(id, sortOrder)

    /** Writes all favorites and groups as JSON. Returns the number of favorites written. */
    suspend fun exportFavorites(outputStream: OutputStream): Int {
        val favorites = db.favoriteDao().getAllOnce()
        val backup = FavoritesBackup(groups = db.favoriteGroupDao().getAllOnce(), favorites = favorites)
        outputStream.bufferedWriter().use { Gson().toJson(backup, it) }
        return favorites.size
    }

    /**
     * Parses a backup without touching the database (for a preview before importing).
     * Null when the stream isn't a favorites backup or holds nothing.
     */
    fun readFavoritesBackup(inputStream: InputStream): FavoritesBackup? {
        val backup = inputStream.bufferedReader().use {
            runCatching { Gson().fromJson(it, FavoritesBackup::class.java) }.getOrNull()
        } ?: return null

        // Gson leaves Kotlin non-null fields null when the JSON lacks them.
        @Suppress("SENSELESS_COMPARISON")
        val favorites = (if (backup.favorites == null) emptyList() else backup.favorites).filter { it.uuid != null && it.url != null }

        @Suppress("SENSELESS_COMPARISON")
        val groups = (if (backup.groups == null) emptyList() else backup.groups).filter { it.id != null && it.name != null }
        if (favorites.isEmpty() && groups.isEmpty()) return null
        return backup.copy(favorites = favorites, groups = groups)
    }

    data class ImportResult(val added: Int, val alreadyThere: Int, val groupsCreated: Int)

    /**
     * Imports [backup].
     *
     * Merge (default): keeps everything already here and adds only stations not yet favorited.
     * A backup group whose name matches an existing group (case-insensitive) is combined with it;
     * other groups are created after the existing ones. Imported stations go to the end of their
     * group, keeping the backup's relative order.
     *
     * Replace: the previous behaviour - the database becomes exactly the backup.
     */
    suspend fun importFavorites(backup: FavoritesBackup, replace: Boolean = false): ImportResult {
        val favDao = db.favoriteDao()
        val groupDao = db.favoriteGroupDao()
        if (replace) {
            favDao.deleteAll()
            groupDao.deleteAll()
            backup.groups.forEach { groupDao.insert(it) }
            backup.favorites.forEach { favDao.insert(it) }
            return ImportResult(backup.favorites.size, 0, backup.groups.size)
        }

        val existingGroups = groupDao.getAllOnce()
        val existingFavs = favDao.getAllOnce()
        val haveUuids = existingFavs.map { it.uuid }.toHashSet()

        // Backup group id → local group id (matched by name, or newly created).
        var nextGroupOrder = (existingGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1
        var groupsCreated = 0
        val groupMap = HashMap<String, String>()
        for (g in backup.groups.sortedBy { it.sortOrder }) {
            val match = existingGroups.firstOrNull { it.name.equals(g.name, ignoreCase = true) }
            if (match != null) {
                groupMap[g.id] = match.id
            } else {
                val id = UUID.randomUUID().toString()
                groupDao.insert(FavoriteGroupEntity(id = id, name = g.name, sortOrder = nextGroupOrder++))
                groupMap[g.id] = id
                groupsCreated++
            }
        }

        // Next free sort slot per local group ("" = ungrouped).
        val nextOrder = existingFavs.groupBy { it.groupId }
            .mapValues { (_, l) -> (l.maxOfOrNull { it.sortOrder } ?: -1) + 1 }.toMutableMap()
        var added = 0
        var already = 0
        for (f in backup.favorites.sortedWith(compareBy({ it.groupId }, { it.sortOrder }))) {
            if (!haveUuids.add(f.uuid)) {
                already++
                continue
            }
            val gid = groupMap[f.groupId] ?: ""
            val order = nextOrder.getOrDefault(gid, 0)
            nextOrder[gid] = order + 1
            favDao.insert(f.copy(groupId = gid, sortOrder = order))
            added++
        }
        return ImportResult(added, already, groupsCreated)
    }
}
