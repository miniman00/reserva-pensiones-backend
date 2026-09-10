package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyCenterCatalogServiceSqlTest {

    @Test
    void publishedLinkedCountSqlKeepsWhitespaceAfterWhere() {
        String sql = StudyCenterCatalogService.publishedLinkedCountSql(false);

        assertFalse(sql.contains("WHERELOWER"));
        assertTrue(sql.contains("WHERE LOWER("));
    }
}
