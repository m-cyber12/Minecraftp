package io.craftrealm.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps one result-set row to a domain object. */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet resultSet) throws SQLException;
}
