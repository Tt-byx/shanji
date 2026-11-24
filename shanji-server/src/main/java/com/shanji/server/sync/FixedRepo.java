package com.shanji.server.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FixedRepo extends JpaRepository<SyncEntities.FixedRecord, String> {

    @Query("select f from FixedRecord f where f.userId = :uid and f.updatedAt > :since")
    List<SyncEntities.FixedRecord> findSince(@Param("uid") Long uid, @Param("since") long since);
}
