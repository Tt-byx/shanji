package com.shanji.server.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BudgetRepo extends JpaRepository<SyncEntities.BudgetRecord, String> {

    @Query("select b from BudgetRecord b where b.userId = :uid and b.updatedAt > :since")
    List<SyncEntities.BudgetRecord> findSince(@Param("uid") Long uid, @Param("since") long since);
}
