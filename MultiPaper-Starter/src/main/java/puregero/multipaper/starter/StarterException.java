package puregero.multipaper.starter;

/**
 * Thrown for expected, user-facing starter failures (bad input, missing
 * repository, failed build, ...). The message is printed as-is.
 */
final class StarterException extends RuntimeException {
    StarterException(String message) {
        super(message);
    }
}
