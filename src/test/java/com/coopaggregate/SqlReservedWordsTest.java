package com.coopaggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * The SQL here is only run by Postgres on Neon, so a reserved word used as a name (for example a CTE called
 * "overlaps") would only show up there. This checks every Flyway migration and every native query for names that
 * Postgres reserves: CTE and temp table names, subquery and table aliases, and column aliases after AS.
 * Keywords are written in upper case and names in lower case in this project, which is what makes this possible.
 */
class SqlReservedWordsTest {

    // Postgres "reserved" and "reserved (can be function or type)" keywords: they cannot name a table, CTE or alias.
    private static final Set<String> RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both", "case", "cast",
            "check", "collate", "column", "constraint", "create", "current_catalog", "current_date", "current_role",
            "current_time", "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct", "do",
            "else", "end", "except", "false", "fetch", "for", "foreign", "from", "grant", "group", "having", "in",
            "initially", "intersect", "into", "lateral", "leading", "limit", "localtime", "localtimestamp", "not",
            "null", "offset", "on", "only", "or", "order", "placing", "primary", "references", "returning", "select",
            "session_user", "some", "symmetric", "system_user", "table", "then", "to", "trailing", "true", "union",
            "unique", "user", "using", "variadic", "when", "where", "window", "with",
            "authorization", "binary", "collation", "concurrently", "cross", "current_schema", "freeze", "full",
            "ilike", "inner", "is", "isnull", "join", "left", "like", "natural", "notnull", "outer", "overlaps",
            "right", "similar", "tablesample", "verbose");

    private static final String NAME = "([a-z_][a-z0-9_]*)";
    private static final List<Pattern> NAME_POSITIONS = List.of(
            // WITH name AS (   or   , name AS (
            Pattern.compile("(?:\\bWITH|,)\\s*" + NAME + "\\s+AS\\s*\\("),
            // CREATE TEMP TABLE name / CREATE TABLE name
            Pattern.compile("\\bCREATE\\s+(?:TEMP\\s+|TEMPORARY\\s+)?TABLE\\s+" + NAME + "\\b"),
            // FROM table alias / JOIN table alias
            Pattern.compile("\\b(?:FROM|JOIN)\\s+[a-z_][a-z0-9_]*\\s+" + NAME + "\\b"),
            // (subquery) alias
            Pattern.compile("\\)\\s+(?:AS\\s+)?" + NAME + "\\b"),
            // expression AS alias
            Pattern.compile("\\bAS\\s+" + NAME + "\\b"));

    @Test
    void migrationsUseNoReservedWordAsAName() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Resource migration : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql")) {
            sources.put(migration.getFilename(), migration.getContentAsString(StandardCharsets.UTF_8));
        }

        assertTrue(sources.containsKey("V31__delivery_batches.sql"), "the migrations were not found");
        assertNoReservedNames(sources);
    }

    @Test
    void nativeQueriesUseNoReservedWordAsAName() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        Map<String, String> sources = new LinkedHashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.coopaggregate")) {
            for (Method method : Class.forName(definition.getBeanClassName()).getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null && query.nativeQuery()) {
                    sources.put(method.getDeclaringClass().getSimpleName() + "." + method.getName(), query.value());
                }
            }
        }

        assertTrue(sources.containsKey("ReportRepository.findWeeksWithActivity"), "native queries were not found");
        assertNoReservedNames(sources);
    }

    @Test
    void theCheckCatchesTheReservedNameThatFailedOnNeon() {
        String failing = """
                WITH batches AS (SELECT d.id FROM delivery d),
                overlaps AS (SELECT e.kind FROM events e JOIN batches b ON b.lot_id = e.lot_id)
                SELECT * FROM overlaps
                """;

        assertEquals(Set.of("overlaps"), reservedNames(failing));
        assertEquals(Set.of("user", "order"), reservedNames("SELECT x AS user FROM lot l JOIN sale order ON 1 = 1"));
        assertFalse(reservedNames("SELECT CAST(s.sale_date AS DATE) AS week_start FROM sale s").contains("date"));
    }

    private static void assertNoReservedNames(Map<String, String> sources) {
        List<String> problems = new ArrayList<>();
        sources.forEach((source, sql) -> {
            Set<String> names = reservedNames(sql);
            if (!names.isEmpty()) {
                problems.add(source + " uses reserved word(s) as names: " + names);
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static Set<String> reservedNames(String sql) {
        String code = withoutCommentsAndStrings(sql);
        Set<String> found = new TreeSet<>();
        for (Pattern position : NAME_POSITIONS) {
            Matcher matcher = position.matcher(code);
            while (matcher.find()) {
                if (RESERVED.contains(matcher.group(1))) {
                    found.add(matcher.group(1));
                }
            }
        }
        return found;
    }

    // Comments and string literals may contain any word, so they are left out of the check.
    private static String withoutCommentsAndStrings(String sql) {
        return sql.replaceAll("--[^\\n]*", " ").replaceAll("'(?:[^']|'')*'", "''");
    }
}
