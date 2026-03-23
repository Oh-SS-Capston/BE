package com.example.ossdoc.domain.build.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class MavenJavaVersionResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("^\\$\\{([^}]+)}$");

    public Optional<Integer> resolveRequiredJavaMajor(Path repoRoot) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            return Optional.empty();
        }

        List<PomModel> lineage = collectPomLineage(rootPom);
        if (lineage.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> mergedProperties = mergeProperties(lineage);

        for (int i = lineage.size() - 1; i >= 0; i--) {
            PomModel model = lineage.get(i);

            Optional<Integer> pluginVersion = parseMajor(model.compilerRelease(), mergedProperties)
                    .or(() -> parseMajor(model.compilerSource(), mergedProperties))
                    .or(() -> parseMajor(model.compilerTarget(), mergedProperties));
            if (pluginVersion.isPresent()) {
                return pluginVersion;
            }

            Optional<Integer> propertyVersion = parseMajor(model.mavenCompilerRelease(), mergedProperties)
                    .or(() -> parseMajor(model.mavenCompilerSource(), mergedProperties))
                    .or(() -> parseMajor(model.mavenCompilerTarget(), mergedProperties))
                    .or(() -> parseMajor(model.javaVersion(), mergedProperties))
                    .or(() -> parseMajor(model.jdkVersion(), mergedProperties));
            if (propertyVersion.isPresent()) {
                return propertyVersion;
            }
        }

        return Optional.empty();
    }

    private List<PomModel> collectPomLineage(Path rootPom) {
        List<PomModel> chain = new ArrayList<>();
        Set<Path> visited = new HashSet<>();

        Path current = rootPom.toAbsolutePath().normalize();
        while (Files.exists(current) && visited.add(current)) {
            Optional<PomModel> modelOptional = readPomModel(current);
            if (modelOptional.isEmpty()) {
                break;
            }

            PomModel model = modelOptional.get();
            chain.add(model);

            Optional<Path> parent = resolveParentPomPath(current, model);
            if (parent.isEmpty()) {
                break;
            }
            current = parent.get();
        }

        Collections.reverse(chain);
        return chain;
    }

    private Optional<Path> resolveParentPomPath(Path currentPom, PomModel model) {
        if (!model.hasParent()) {
            return Optional.empty();
        }

        String relativePath = model.parentRelativePath();
        if (relativePath == null) {
            relativePath = "../pom.xml";
        }
        if (relativePath.isBlank()) {
            return Optional.empty();
        }

        Path resolved = currentPom.getParent().resolve(relativePath).normalize();
        if (Files.exists(resolved)) {
            return Optional.of(resolved);
        }
        return Optional.empty();
    }

    private Map<String, String> mergeProperties(List<PomModel> lineage) {
        Map<String, String> merged = new HashMap<>();
        for (PomModel model : lineage) {
            for (Map.Entry<String, String> entry : model.properties().entrySet()) {
                String value = resolvePlaceholders(entry.getValue(), merged);
                merged.put(entry.getKey(), value);
            }
        }
        return merged;
    }

    private Optional<PomModel> readPomModel(Path pomPath) {
        try (InputStream in = Files.newInputStream(pomPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            safeSet(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            safeSet(factory, "http://xml.org/sax/features/external-general-entities", false);
            safeSet(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            safeSet(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);

            Document document = factory.newDocumentBuilder().parse(in);
            Element project = document.getDocumentElement();
            if (project == null) {
                return Optional.empty();
            }

            Map<String, String> properties = readProperties(project);
            String compilerRelease = findCompilerPluginConfig(project, "release").orElse(null);
            String compilerSource = findCompilerPluginConfig(project, "source").orElse(null);
            String compilerTarget = findCompilerPluginConfig(project, "target").orElse(null);

            String mavenCompilerRelease = properties.get("maven.compiler.release");
            String mavenCompilerSource = properties.get("maven.compiler.source");
            String mavenCompilerTarget = properties.get("maven.compiler.target");
            String javaVersion = properties.get("java.version");
            String jdkVersion = properties.get("jdk.version");

            Element parentElement = directChild(project, "parent");
            boolean hasParent = parentElement != null;
            String parentRelativePath = null;
            if (hasParent) {
                Element relativePathElement = directChild(parentElement, "relativePath");
                parentRelativePath = relativePathElement == null ? null : text(relativePathElement);
            }

            return Optional.of(new PomModel(
                    pomPath,
                    properties,
                    compilerRelease,
                    compilerSource,
                    compilerTarget,
                    mavenCompilerRelease,
                    mavenCompilerSource,
                    mavenCompilerTarget,
                    javaVersion,
                    jdkVersion,
                    hasParent,
                    parentRelativePath
            ));
        } catch (Exception e) {
            log.debug("[BUILD] Failed to parse pom for Java version detection. pom={}", pomPath, e);
            return Optional.empty();
        }
    }

    private void safeSet(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Keep best-effort hardening without failing build analysis.
        }
    }

    private Map<String, String> readProperties(Element project) {
        Map<String, String> properties = new HashMap<>();
        Element propertiesElement = directChild(project, "properties");
        if (propertiesElement == null) {
            return properties;
        }

        NodeList children = propertiesElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String key = node.getNodeName();
            String value = text((Element) node);
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                properties.put(key.trim(), value.trim());
            }
        }
        return properties;
    }

    private Optional<String> findCompilerPluginConfig(Element project, String key) {
        Element build = directChild(project, "build");
        if (build == null) {
            return Optional.empty();
        }

        Optional<String> fromPlugins = findCompilerPluginConfigInContainer(directChild(build, "plugins"), key);
        if (fromPlugins.isPresent()) {
            return fromPlugins;
        }

        Element pluginManagement = directChild(build, "pluginManagement");
        if (pluginManagement == null) {
            return Optional.empty();
        }
        return findCompilerPluginConfigInContainer(directChild(pluginManagement, "plugins"), key);
    }

    private Optional<String> findCompilerPluginConfigInContainer(Element pluginsContainer, String key) {
        if (pluginsContainer == null) {
            return Optional.empty();
        }

        for (Element plugin : directChildren(pluginsContainer, "plugin")) {
            String artifactId = text(directChild(plugin, "artifactId"));
            if (!"maven-compiler-plugin".equals(artifactId)) {
                continue;
            }
            Element configuration = directChild(plugin, "configuration");
            if (configuration == null) {
                return Optional.empty();
            }
            Element target = directChild(configuration, key);
            return Optional.ofNullable(text(target));
        }
        return Optional.empty();
    }

    private Optional<Integer> parseMajor(String expression, Map<String, String> properties) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }

        String resolved = resolvePlaceholders(expression, properties);
        if (resolved == null || resolved.isBlank()) {
            return Optional.empty();
        }

        Integer major = parseMajorVersion(resolved);
        return Optional.ofNullable(major);
    }

    private String resolvePlaceholders(String raw, Map<String, String> properties) {
        String current = raw == null ? "" : raw.trim();
        for (int i = 0; i < 8; i++) {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(current);
            if (!matcher.matches()) {
                return current;
            }

            String key = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (key.isBlank()) {
                return current;
            }

            String replacement = properties.get(key);
            if (replacement == null || replacement.isBlank()) {
                return current;
            }
            current = replacement.trim();
        }
        return current;
    }

    private Integer parseMajorVersion(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return null;
        }

        if (trimmed.startsWith("1.")) {
            String[] parts = trimmed.split("\\.");
            if (parts.length > 1) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        int dot = trimmed.indexOf('.');
        String majorPart = dot >= 0 ? trimmed.substring(0, dot) : trimmed;
        try {
            return Integer.parseInt(majorPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Element directChild(Element parent, String childName) {
        if (parent == null || childName == null) {
            return null;
        }

        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (childName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private List<Element> directChildren(Element parent, String childName) {
        List<Element> elements = new ArrayList<>();
        if (parent == null || childName == null) {
            return elements;
        }

        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (childName.equals(node.getNodeName())) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null ? null : value.trim();
    }

    private record PomModel(Path path,
                            Map<String, String> properties,
                            String compilerRelease,
                            String compilerSource,
                            String compilerTarget,
                            String mavenCompilerRelease,
                            String mavenCompilerSource,
                            String mavenCompilerTarget,
                            String javaVersion,
                            String jdkVersion,
                            boolean hasParent,
                            String parentRelativePath) {
    }
}
