package com.lightterm.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lightterm.data.model.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {
    @Query("SELECT * FROM server_configs ORDER BY id ASC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs ORDER BY id ASC")
    suspend fun listAll(): List<ServerConfigEntity>

    @Query("SELECT * FROM server_configs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ServerConfigEntity?

    @Query("DELETE FROM server_configs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE server_configs SET lastUsedAtEpochMillis = :lastUsedAtEpochMillis WHERE id = :id")
    suspend fun updateLastUsedAt(id: Long, lastUsedAtEpochMillis: Long)

    @Query(
        """
        SELECT COUNT(*) FROM server_configs
        WHERE credentialRef = :credentialRef OR jumpCredentialRef = :credentialRef
        """,
    )
    suspend fun countByCredentialRef(credentialRef: String): Int

    @Query(
        """
        SELECT * FROM server_configs
        WHERE alias = :alias AND host = :host AND port = :port AND username = :username
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun findBySignature(
        alias: String,
        host: String,
        port: Int,
        username: String,
    ): ServerConfigEntity?

    @Query("SELECT COUNT(*) FROM server_configs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ServerConfigEntity>)

    @Upsert
    suspend fun upsert(item: ServerConfigEntity)
}
