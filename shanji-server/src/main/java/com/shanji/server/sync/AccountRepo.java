package com.shanji.server.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AccountRepo extends JpaRepository<SyncEntities.AccountRecord, String> {

    @Query("select a from AccountRecord a where a.userId = :uid and a.updatedAt > :since")
    List<SyncEntities.AccountRecord> findSince(@Param("uid") Long uid, @Param("since") long since);
}
