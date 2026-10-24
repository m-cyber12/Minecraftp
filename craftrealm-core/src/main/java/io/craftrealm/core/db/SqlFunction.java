package io.craftrealm.core.db;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of work that runs with a pooled connection and produces a value. */
@FunctionalInterface
public interface SqlFunction<T> {

    T apply(Connection connection) throws SQLException;
}
