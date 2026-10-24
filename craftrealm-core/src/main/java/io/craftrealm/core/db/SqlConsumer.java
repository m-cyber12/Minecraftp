package io.craftrealm.core.db;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of work that runs with a pooled connection and returns nothing. */
@FunctionalInterface
public interface SqlConsumer {

    void accept(Connection connection) throws SQLException;
}
