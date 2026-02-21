package com.example.ossdoc.domain.module.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.*;
        import lombok.*;

@Entity
@Table(
        name = "file_index",
        uniqueConstraints = @UniqueConstraint(name = "ux_file_run_path", columnNames = {"run_id", "path"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FileIndex extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_id")
    private Long fileId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private ModuleEntity module;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(name = "sha256")
    private String sha256;

    @Column(name = "size_bytes")
    private Long sizeBytes;
}
