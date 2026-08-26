// domain/run/support/ZipUtils.java
package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.exception.RunException;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class ZipUtils {

    // ZIP 파일을 지정된 디렉토리에 안전하게 압축 해제함. (보안 고려가 들어간 unzip)
    // zipPath -> GitHub에서 받은 ZIP 파일 경로
    // destDir -> 압축을 풀 대상 디렉토리
    public static void unzip(Path zipPath, Path destDir) {
        try {
            Files.createDirectories(destDir);

            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path outPath = destDir.resolve(entry.getName()).normalize();

                    // zip slip 방지 (이거 없으면 보안 사고 남.)
                    if (!outPath.startsWith(destDir)) {
                        throw new RunException(RunErrorCode.UNZIP_FAILED);
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(outPath);
                    } else {
                        Files.createDirectories(outPath.getParent());
                        Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("unzip fail error={}", e.getMessage());
            throw new RunException(RunErrorCode.UNZIP_FAILED);
        }
    }
}