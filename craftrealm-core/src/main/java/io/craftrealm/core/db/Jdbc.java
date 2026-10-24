package io.craftrealm.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin JDBC helpers: parameter binding, single-value queries and generated keys.
 *
 * <p>No ORM, no reflection — a repository shows its SQL in plain sight, which is
 * what you want when an economy bug needs to be diagnosed at 3am.</p>
 */
public final class Jdbc {

    private Jdbc() {
    }

    /** Binds {@code params} to {@code statement}, translating common Java types. */
    public static void bind(PreparedStatement statement, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value == null) {
                statement.setObject(index, null);
            } else if (value instanceof UUID uuid) {
                statement.setString(index, uuid.toString());
            } else if (value instanceof Boolean bool) {
                statement.setInt(index, bool ? 1 : 0);
            } else if (value instanceof Enum<?> enumeration) {
                statement.setString(index, enumeration.name());
            } else if (value instanceof byte[] bytes) {
                statement.setBytes(index, bytes);
            } else {
                statement.setObject(index, value);
            }
        }
    }

    /** Executes an INSERT/UPDATE/DELETE and returns the affected row count. */
    public static int update(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    /** Executes an INSERT and returns the generated id, or {@code -1}. */
    public static long insert(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /** Runs a SELECT and maps every row. */
    public static <T> List<T> list(Connection connection, String sql, RowMapper<T> mapper, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(mapper.map(resultSet));
                }
                return results;
            }
        }
    }

    /** Runs a SELECT and maps at most one row. */
    public static <T> Optional<T> one(Connection connection, String sql, RowMapper<T> mapper, Object... params)
            throws SQLException {
        List<T> results = list(connection, sql, mapper, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@code SELECT COUNT(*)} style scalar read. */
    public static long scalar(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    /** Double scalar read; returns {@code 0} for SQL NULL. */
    public static double scalarDouble(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getDouble(1) : 0.0;
            }
        }
    }

    /** Reads a nullable UUID column. */
    public static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        String raw = resultSet.getString(column);
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    /** Reads a nullable long-ish column as {@code 0}. */
    public static long longOrZero(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? 0L : value;
    }
}
