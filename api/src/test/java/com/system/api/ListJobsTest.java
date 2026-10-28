package com.system.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-9, FR-10: list jobs, filtered by status, with cursor pagination on id.
 */
class ListJobsTest extends AbstractIntegrationTest {

    /**
     * PostgreSQL orders uuid byte by byte, which matches sorting the lowercase text
     * form. UUID.compareTo compares signed longs and disagrees for half of all ids.
     */
    private static final Comparator<UUID> POSTGRES_ORDER = Comparator.comparing(UUID::toString);

    private UUID createJob() {
        HttpResponse<String> created = postJob("{\"type\":\"IO\",\"payload\":{}}");
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asText());
    }

    private List<UUID> createJobs(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createJob());
        }
        return ids;
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("id").asText())));
        return ids;
    }

    /** Follows nextCursor until it runs out and returns every id seen, in order. */
    private List<UUID> readAllPages(String filter, int limit) {
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            String query = filter + "limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
            HttpResponse<String> response = listJobs(query);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode page = json(response);
            assertThat(page.path("items").size()).isLessThanOrEqualTo(limit);
            seen.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        return seen;
    }

    @Test
    void emptyTableReturnsNoItemsAndNoCursor() {
        HttpResponse<String> response = listJobs("");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("items").size()).isZero();
        assertThat(json(response).path("nextCursor").isNull()).isTrue();
    }

    @Test
    void pagesThroughEveryJobExactlyOnceInIdOrder() {
        List<UUID> created = createJobs(7);

        List<UUID> seen = readAllPages("", 3);

        assertThat(seen).containsExactlyElementsOf(created.stream().sorted(POSTGRES_ORDER).toList());
    }

    @Test
    void lastFullPageHasNoCursorWhenNothingFollows() {
        createJobs(3);

        JsonNode page = json(listJobs("limit=3"));

        assertThat(page.path("items").size()).isEqualTo(3);
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void filtersByStatus() {
        List<UUID> created = createJobs(4);
        UUID cancelled = created.get(1);
        assertThat(cancelJob(cancelled.toString()).statusCode()).isEqualTo(200);

        assertThat(readAllPages("status=CANCELLED&", 10)).containsExactly(cancelled);
        assertThat(readAllPages("status=PENDING&", 10))
                .containsExactlyInAnyOrderElementsOf(created.stream().filter(id -> !id.equals(cancelled)).toList());
    }

    /**
     * The reason for cursor pagination (FR-10): a job leaving the filter between two
     * page requests must not make the next page skip another job. With OFFSET 2,
     * removing a row from page 1 would shift an unseen row back into the gap.
     */
    @Test
    void jobLeavingTheFilterBetweenPagesDoesNotSkipOtherJobs() {
        List<UUID> sorted = createJobs(6).stream().sorted(POSTGRES_ORDER).toList();

        JsonNode first = json(listJobs("status=PENDING&limit=2"));
        assertThat(ids(first)).containsExactly(sorted.get(0), sorted.get(1));

        // A job already shown on page 1 stops being PENDING
        assertThat(cancelJob(sorted.get(0).toString()).statusCode()).isEqualTo(200);

        JsonNode second = json(listJobs("status=PENDING&limit=2&cursor=" + first.path("nextCursor").asText()));
        assertThat(ids(second)).containsExactly(sorted.get(2), sorted.get(3));
    }

    @Test
    void defaultLimitIs50() {
        createJobs(51);

        JsonNode page = json(listJobs(""));

        assertThat(page.path("items").size()).isEqualTo(50);
        assertThat(page.path("nextCursor").isNull()).isFalse();
    }

    @Test
    void itemsDoNotExposeLeaseDetails() {
        createJob();

        JsonNode item = json(listJobs("")).path("items").get(0);

        // FR-8: lease details are for an admin endpoint, not normal clients
        assertThat(item.has("lockedBy")).isFalse();
        assertThat(item.has("leaseExpiresAt")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"status=BOGUS", "status=pending", "cursor=not-a-uuid", "limit=0", "limit=201", "limit=abc"})
    void rejectsInvalidParametersWith400(String query) {
        assertThat(listJobs(query).statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 200})
    void acceptsLimitAtTheRangeBoundaries(int limit) {
        assertThat(listJobs("limit=" + limit).statusCode()).isEqualTo(200);
    }
}
