package com.shanji.server.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TxRepo extends JpaRepository<SyncEntities.TxRecord, String> {

    @Query("select t from TxRecord t where t.userId = :uid and t.updatedAt > :since")
    List<SyncEntities.TxRecord> findSince(@Param("uid") Long uid, @Param("since") long since);
}
