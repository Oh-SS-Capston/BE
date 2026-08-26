package com.example.ossdoc.domain.build.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class PomModuleScanner {

    /**
     * Maven 루트 pom.xml의 <modules>를 읽어서 모듈 디렉토리 목록을 찾아준다.
     * - 멀티모듈이면 하위 모듈 루트들을 반환
     * - 단일 모듈이면 repoRoot 하나만 반환
     */
    public List<Path> scanModuleRoots(Path repoRoot) {
        List<Path> moduleRoots = new ArrayList<>();

        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            return moduleRoots;
        }

        List<String> moduleNames = readModuleNames(rootPom);

        if (moduleNames.isEmpty()) {
            // 단일 모듈
            moduleRoots.add(repoRoot);
            return moduleRoots;
        }

        for (String moduleName : moduleNames) {
            Path moduleRoot = repoRoot.resolve(moduleName);
            if (Files.exists(moduleRoot) && Files.isDirectory(moduleRoot)) {
                moduleRoots.add(moduleRoot);
            }
        }

        // 혹시 modules를 못 읽었더라도 최소 루트는 넣어줌
        if (moduleRoots.isEmpty()) {
            moduleRoots.add(repoRoot);
        }

        return moduleRoots;
    }

    private List<String> readModuleNames(Path pomPath) {
        List<String> moduleNames = new ArrayList<>();

        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(pomPath.toFile());

            NodeList modulesList = doc.getElementsByTagName("modules");
            if (modulesList.getLength() == 0) {
                return moduleNames;
            }

            Node modulesNode = modulesList.item(0);
            NodeList childNodes = modulesNode.getChildNodes();

            for (int i = 0; i < childNodes.getLength(); i++) {
                Node node = childNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "module".equals(node.getNodeName())) {
                    String text = node.getTextContent();
                    if (text != null && !text.isBlank()) {
                        moduleNames.add(text.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BUILD] Failed to parse pom modules. pom={}", pomPath, e);
        }

        return moduleNames;
    }
}