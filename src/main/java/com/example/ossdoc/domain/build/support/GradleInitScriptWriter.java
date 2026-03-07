package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.exception.BuildException;
import com.example.ossdoc.domain.build.exception.code.BuildErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class GradleInitScriptWriter {

    /**
     * repo를 수정하지 않고 -I init.gradle로 "모듈/소스루트/classesDirs/classpath"를 덤프하기 위한 스크립트 생성
     */
    public Path write(Path tmpDir) {
        try {
            Files.createDirectories(tmpDir);
            Path init = tmpDir.resolve("ossdoc-init.gradle");

            String script = """
                import groovy.json.JsonOutput
                allprojects { prj ->
                  tasks.register("ossdocDump") {
                    doLast {
                      def result = [:]
                      result.projectPath = prj.path
                      result.name = prj.name
                      def hasJava = prj.plugins.hasPlugin('java') || prj.plugins.hasPlugin('java-library')
                      result.hasJava = hasJava

                      if (hasJava && prj.hasProperty("sourceSets")) {
                        def main = prj.sourceSets.main
                        def test = prj.sourceSets.test
                        result.sourceRoots = main.java.srcDirs.collect { prj.rootProject.relativePath(it) }
                        result.testRoots = test.java.srcDirs.collect { prj.rootProject.relativePath(it) }
                        result.resourceRoots = main.resources.srcDirs.collect { prj.rootProject.relativePath(it) }
                        result.classesDirs = main.output.classesDirs.files.collect { prj.rootProject.relativePath(it) }

                        try { result.compileClasspath = main.compileClasspath.files.collect { it.absolutePath } } catch (ignored) { result.compileClasspath = [] }
                        try { result.runtimeClasspath = main.runtimeClasspath.files.collect { it.absolutePath } } catch (ignored) { result.runtimeClasspath = [] }
                      } else {
                        result.sourceRoots = []; result.testRoots=[]; result.resourceRoots=[]
                        result.classesDirs = []; result.compileClasspath=[]; result.runtimeClasspath=[]
                      }

                      println("OSS_DOC_DUMP=" + JsonOutput.toJson(result))
                    }
                  }
                }
                """;

            Files.writeString(init, script);
            return init;
        } catch (Exception e) {
            log.debug("GradleInitScriptWriter error={}", e);
            throw new BuildException(BuildErrorCode.GRADLE_INIT_SCRIPT_FAILED);
        }
    }
}
