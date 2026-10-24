package io.craftrealm.core.db;

/** Wraps a {@link java.sql.SQLException} so it can travel through futures. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
