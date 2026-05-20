package com.example.ossdoc.domain.githubstats.client;

import com.example.ossdoc.domain.githubstats.exception.GithubStatsException;
import com.example.ossdoc.domain.githubstats.exception.code.GithubStatsErrorCode;
import com.example.ossdoc.global.properties.GithubStatsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GithubStatsApiClient {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final int ERROR_BODY_PREVIEW_LEN = 300;
    private static final Pattern LAST_PAGE_PATTERN =
            Pattern.compile("[?&]page=(\\d+)>; rel=\\\"last\\\"");

    private final ObjectMapper objectMapper;
    private final GithubStatsProperties properties;
    private final WebClient webClient;

    public GithubStatsApiClient(ObjectMapper objectMapper, GithubStatsProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, "ossdoc")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultHeaders(headers -> {
                    if (properties.getToken() != null && !properties.getToken().isBlank()) {
                        headers.setBearerAuth(properties.getToken());
                    }
                })
                .build();
    }

    public JsonNode getRepository(String owner, String repo) {
        return getRequiredJson("/repos/{owner}/{repo}", owner, repo);
    }

    public JsonNode getLanguages(String owner, String repo) {
        return getRequiredJson("/repos/{owner}/{repo}/languages", owner, repo);
    }

    public Long countContributors(String owner, String repo) {
        try {
            ResponseEntity<String> entity = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/contributors")
                            .queryParam("anon", "true")
                            .queryParam("per_page", "1")
                            .build(owner, repo))
                    .exchangeToMono(response -> response.toEntity(String.class))
                    .block(timeout());

            if (entity == null) {
                throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED);
            }

            int status = entity.getStatusCode().value();

            if (status == 404) {
                throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_REPOSITORY_NOT_FOUND);
            }

            if (status < 200 || status >= 300) {
                log.warn(
                        "GitHub contributors API failed owner={}, repo={}, status={}, body={}",
                        owner,
                        repo,
                        status,
                        previewBody(entity.getBody())
                );
                throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED);
            }

            Long countFromLink = parseLastPageNumber(entity.getHeaders().get(HttpHeaders.LINK));
            if (countFromLink != null) {
                return countFromLink;
            }

            JsonNode body = readJson(entity.getBody());
            if (body == null || !body.isArray()) {
                return 0L;
            }

            return (long) body.size();
        } catch (GithubStatsException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "GitHub contributors API failed owner={}, repo={}, cause={}",
                    owner,
                    repo,
                    e.toString(),
                    e
            );
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED);
        }
    }

    public Long countRecentIssues(String owner, String repo, LocalDate sinceDate) {
        String query = "repo:" + owner + "/" + repo + " is:issue created:>=" + sinceDate;
        return countIssuesBySearchQuery(owner, repo, query, "created issues");
    }

    public Long countRecentClosedIssues(String owner, String repo, LocalDate sinceDate) {
        String query = "repo:" + owner + "/" + repo + " is:issue closed:>=" + sinceDate;
        return countIssuesBySearchQuery(owner, repo, query, "closed issues");
    }

    /**
     * 최근 28일 이슈 생성 수를 날짜별로 계산합니다.
     * 하루마다 28번 호출하지 않고 Search API를 페이지 단위로 조회합니다.
     */
    public Map<LocalDate, Integer> countRecentIssuesByDate(String owner, String repo, LocalDate sinceDate) {
        String query = "repo:" + owner + "/" + repo + " is:issue created:>=" + sinceDate;
        return countIssuesByDate(owner, repo, sinceDate, query, "created_at", "created issues by date");
    }

    /**
     * 최근 28일 해결된 이슈 수를 날짜별로 계산합니다.
     * GitHub Search API의 closed qualifier와 closed_at 값을 사용합니다.
     */
    public Map<LocalDate, Integer> countRecentClosedIssuesByDate(String owner, String repo, LocalDate sinceDate) {
        String query = "repo:" + owner + "/" + repo + " is:issue closed:>=" + sinceDate;
        return countIssuesByDate(owner, repo, sinceDate, query, "closed_at", "closed issues by date");
    }

    public JsonNode getLatestReleaseOrNull(String owner, String repo) {
        try {
            ResponseEntity<String> entity = webClient.get()
                    .uri("/repos/{owner}/{repo}/releases/latest", owner, repo)
                    .exchangeToMono(response -> response.toEntity(String.class))
                    .block(timeout());

            if (entity == null) {
                return null;
            }

            int status = entity.getStatusCode().value();

            if (status == 404) {
                return null;
            }

            if (status < 200 || status >= 300) {
                log.warn(
                        "GitHub latest release API failed owner={}, repo={}, status={}, body={}",
                        owner,
                        repo,
                        status,
                        previewBody(entity.getBody())
                );
                return null;
            }

            return readJson(entity.getBody());
        } catch (Exception e) {
            log.warn(
                    "GitHub latest release API skipped owner={}, repo={}, cause={}",
                    owner,
                    repo,
                    e.toString()
            );
            return null;
        }
    }

    private Long countIssuesBySearchQuery(String owner, String repo, String query, String apiName) {
        try {
            JsonNode node = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/issues")
                            .queryParam("q", query)
                            .queryParam("per_page", "1")
                            .build())
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();

                        if (status == 404) {
                            return Mono.<JsonNode>error(
                                    new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_REPOSITORY_NOT_FOUND)
                            );
                        }

                        if (status < 200 || status >= 300) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        log.warn(
                                                "GitHub issue search API failed apiName={}, owner={}, repo={}, status={}, body={}",
                                                apiName,
                                                owner,
                                                repo,
                                                status,
                                                previewBody(body)
                                        );
                                        return Mono.<JsonNode>error(
                                                new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED)
                                        );
                                    });
                        }

                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(this::readJson);
                    })
                    .block(timeout());

            if (node == null || node.get("total_count") == null || node.get("total_count").isNull()) {
                return 0L;
            }

            return node.get("total_count").asLong();
        } catch (GithubStatsException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "GitHub issue search API failed apiName={}, owner={}, repo={}, cause={}",
                    apiName,
                    owner,
                    repo,
                    e.toString(),
                    e
            );
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED);
        }
    }

    private Map<LocalDate, Integer> countIssuesByDate(
            String owner,
            String repo,
            LocalDate sinceDate,
            String query,
            String dateFieldName,
            String apiName
    ) {
        try {
            Map<LocalDate, Integer> result = initializeDailyCountMap(sinceDate);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            for (int page = 1; page <= 10; page++) {
                int currentPage = page;

                JsonNode node = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/search/issues")
                                .queryParam("q", query)
                                .queryParam("sort", "created_at".equals(dateFieldName) ? "created" : "updated")
                                .queryParam("order", "desc")
                                .queryParam("per_page", "100")
                                .queryParam("page", currentPage)
                                .build())
                        .exchangeToMono(response -> {
                            int status = response.statusCode().value();

                            if (status == 404) {
                                return Mono.<JsonNode>error(
                                        new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_REPOSITORY_NOT_FOUND)
                                );
                            }

                            if (status < 200 || status >= 300) {
                                return response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(body -> {
                                            log.warn(
                                                    "GitHub issue search API failed apiName={}, owner={}, repo={}, status={}, body={}",
                                                    apiName,
                                                    owner,
                                                    repo,
                                                    status,
                                                    previewBody(body)
                                            );
                                            return Mono.<JsonNode>error(
                                                    new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED)
                                            );
                                        });
                            }

                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(this::readJson);
                        })
                        .block(timeout());

                JsonNode items = node == null ? null : node.get("items");

                if (items == null || !items.isArray() || items.size() == 0) {
                    break;
                }

                for (JsonNode item : items) {
                    LocalDate date = parseGithubDate(item.path(dateFieldName).asText(null));

                    if (date == null || date.isBefore(sinceDate) || date.isAfter(today)) {
                        continue;
                    }

                    result.merge(date, 1, Integer::sum);
                }

                if (items.size() < 100) {
                    break;
                }
            }

            return result;
        } catch (GithubStatsException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "GitHub issue search API failed apiName={}, owner={}, repo={}, cause={}",
                    apiName,
                    owner,
                    repo,
                    e.toString(),
                    e
            );
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED);
        }
    }

    private JsonNode getRequiredJson(String path, String owner, String repo) {
        try {
            return webClient.get()
                    .uri(path, owner, repo)
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();

                        if (status == 404) {
                            return Mono.<JsonNode>error(
                                    new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_REPOSITORY_NOT_FOUND)
                            );
                        }

                        if (status < 200 || status >= 300) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        log.warn(
                                                "GitHub API failed path={}, owner={}, repo={}, status={}, body={}",
                                                path,
                                                owner,
                                                repo,
                                                status,
                                                previewBody(body)
                                        );
                                        return Mono.<JsonNode>error(
                                                new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED)
                                        );
                                    });
                        }

                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(this::readJson);
                    })
                    .block(timeout());
        } catch (GithubStatsException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "GitHub API failed path={}, owner={}, repo={}, cause={}",
                    path,
                    owner,
                    repo,
                    e.toString(),
                    e
            );
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_API_FAILED);
        }
    }

    private Map<LocalDate, Integer> initializeDailyCountMap(LocalDate sinceDate) {
        Map<LocalDate, Integer> result = new LinkedHashMap<>();

        for (int i = 0; i < 28; i++) {
            result.put(sinceDate.plusDays(i), 0);
        }

        return result;
    }

    private LocalDate parseGithubDate(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(dateText)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readJson(String body) {
        try {
            if (body == null || body.isBlank()) {
                return null;
            }
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_RESPONSE_PARSE_FAILED);
        }
    }

    private Long parseLastPageNumber(List<String> linkHeaders) {
        if (linkHeaders == null || linkHeaders.isEmpty()) {
            return null;
        }

        for (String linkHeader : linkHeaders) {
            Matcher matcher = LAST_PAGE_PATTERN.matcher(linkHeader);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        }

        return null;
    }

    private Duration timeout() {
        long seconds = properties.getApiTimeoutSeconds() <= 0
                ? 30
                : properties.getApiTimeoutSeconds();

        return Duration.ofSeconds(seconds);
    }

    private String previewBody(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }

        return body.length() > ERROR_BODY_PREVIEW_LEN
                ? body.substring(0, ERROR_BODY_PREVIEW_LEN) + "..."
                : body;
    }
}
