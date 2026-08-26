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
                import org.gradle.api.DefaultTask
                import org.gradle.api.provider.Property
                import org.gradle.api.tasks.Input
                import org.gradle.api.tasks.Optional
                import org.gradle.api.tasks.TaskAction

                /*
                 * configuration cache 호환 규칙:
                 * 태스크 액션 안에서 Project를 참조하면 CC가 Project를 직렬화하려다 실패한다.
                 *   cannot serialize object of type ...Project, as these are not supported
                 *   with the configuration cache
                 * 그래서 설정 시점(projectsEvaluated)에 JSON을 미리 만들어 두고,
                 * 태스크는 문자열 프로퍼티 하나만 들고 실행 시점에 출력만 한다.
                 */
                abstract class OssdocDumpTask extends DefaultTask {

                  @Input
                  @Optional
                  abstract Property<String> getPayload()

                  @TaskAction
                  void dump() {
                    def json = payload.getOrElse("")
                    if (json) {
                      println("OSS_DOC_DUMP=" + json)
                    }
                  }
                }

                allprojects { prj ->
                  prj.tasks.register("ossdocDump", OssdocDumpTask)
                }

                /*
                 * projectsEvaluated는 모든 프로젝트의 afterEvaluate까지 끝난 뒤 실행되므로,
                 * 빌드 스크립트가 나중에 추가한 소스셋/의존성까지 반영된다.
                 */
                gradle.projectsEvaluated { g ->
                  /*
                   * composite build(includeBuild)의 포함된 빌드는 덤프 대상에서 제외한다.
                   *
                   * [왜 필요한가]
                   * -I init script는 composite의 "모든" 빌드에 적용된다. 즉 루트 빌드뿐 아니라
                   * includeBuild로 끌어온 build-logic 빌드에서도 이 블록이 실행된다.
                   * 그런데 build-logic 빌드는 자기들끼리 includeBuild로 물려 있고,
                   * 그 의존성을 버전 없이 선언한 뒤 project substitution으로 해소하는 경우가 많다.
                   *   예) JUnit gradle/plugins/publishing/build.gradle.kts
                   *       implementation("junitbuild.base:dsl-extensions")   // 버전 없음
                   *       -> gradle/plugins/settings.gradle.kts의 includeBuild("../base")로 치환
                   * 이 상태에서 우리가 projectsEvaluated 시점에 compileClasspath를 강제로 해소하면
                   * substitution이 아직 성립하기 전이라 Gradle이 원격 저장소에서 찾으려 하고,
                   * 버전이 비어 있으니 다음처럼 실패한다.
                   *   Could not find junitbuild.base:dsl-extensions:
                   * 이 실패는 태스크 그래프 계산 단계에서 터지므로 아래 try/catch로도 못 막고
                   * dump 전체가 exitCode=1로 죽는다. (실제 JUnit 분석에서 재현/확인함)
                   *
                   * [제외해도 되는 이유]
                   * build-logic 빌드는 대상 라이브러리의 소스가 아니라 그 저장소를 빌드하기 위한
                   * 도구다. Semantic Graph 분석 대상이 아니므로 덤프할 이유가 없다.
                   *
                   * gradle.parent == null 이면 composite의 루트 빌드다.
                   */
                  if (g.parent != null) {
                    return
                  }
                  g.rootProject.allprojects { prj ->
                    try {
                      def result = [:]
                      result.projectPath = prj.path
                      result.name = prj.name
                      def grpStr = prj.group ? prj.group.toString() : ''
                      result.group = grpStr ? grpStr : null
                      def verStr = prj.version ? prj.version.toString() : ''
                      result.version = (verStr && verStr != 'unspecified') ? verStr : null
                      def hasJava = prj.plugins.hasPlugin('java') || prj.plugins.hasPlugin('java-library')
                      result.hasJava = hasJava

                      if (hasJava && prj.hasProperty("sourceSets")) {
                        def main = prj.sourceSets.findByName('main')
                        def test = prj.sourceSets.findByName('test')
                        result.sourceRoots = main ? main.java.srcDirs.collect { it.absolutePath } : []
                        result.testRoots = test ? test.java.srcDirs.collect { it.absolutePath } : []
                        result.resourceRoots = main ? main.resources.srcDirs.collect { it.absolutePath } : []
                        result.classesDirs = main ? main.output.classesDirs.files.collect { it.absolutePath } : []

                        // 어댑터·멀티플랫폼 모듈의 transitive dependency 해소를 위해
                        // compileClasspath 실패 시 runtimeClasspath로 폴백한다.
                        def cpFiles = []
                        if (main) {
                          try { cpFiles = main.compileClasspath.files.collect { it.absolutePath } } catch (ignored) {}
                          if (!cpFiles) {
                            try { cpFiles = main.runtimeClasspath.files.collect { it.absolutePath } } catch (ignored) {}
                          }
                        }
                        result.compileClasspath = cpFiles
                        def rtFiles = []
                        if (main) {
                          try { rtFiles = main.runtimeClasspath.files.collect { it.absolutePath } } catch (ignored) {}
                        }
                        result.runtimeClasspath = rtFiles
                      } else {
                        result.sourceRoots = []; result.testRoots=[]; result.resourceRoots=[]
                        result.classesDirs = []; result.compileClasspath=[]; result.runtimeClasspath=[]
                      }

                      prj.tasks.named("ossdocDump", OssdocDumpTask).configure { t ->
                        t.payload.set(JsonOutput.toJson(result))
                      }
                    } catch (Throwable ignored) {
                      // 특정 모듈에서 실패해도 payload를 비워 두어 해당 모듈만 건너뛴다.
                      // 설정 시점 예외가 빌드 전체를 죽이지 않도록 막는다.
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
