package com.example.downloader.repo;

import com.example.downloader.entity.DownloadRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadRepository extends JpaRepository<DownloadRecord, String> {
    // 按创建时间倒序查询
    Page<DownloadRecord> findAllByOrderByCreatedTimeDesc(Pageable pageable);
}