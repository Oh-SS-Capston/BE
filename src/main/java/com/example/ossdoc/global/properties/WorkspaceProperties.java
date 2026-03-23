package com.example.ossdoc.global.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.workspace")
public class WorkspaceProperties {

    /**
     * Base directory where per-run workspaces are created.
     * Example: C:/data/ossdoc
     */
    private String baseDir = "C:/data/ossdoc";
}
