package Overpass;

/**
 * Created by nick on 11/3/15.
 */
public class Exceptions {
    public static class OverpassError extends Exception {
        public OverpassError(String s) {
            super(s == null ? "An error during your request occurred. Super class for all Overpass api errors." : s);
        }
    }

    public static class OverpassSyntaxError extends OverpassError {
        public String request;
        public OverpassSyntaxError(String request) {
            super("The request contains a syntax error.");
            this.request = request;
        }
    }

    /**
     * A request timeout occurred.
     */
    public static class TimeoutError extends OverpassError {
        public long timeout;
        public TimeoutError(long timeout) {
            super("A request timeout occurred.");
            this.timeout = timeout;
        }
    }
    public static class MultipleRequestsError extends OverpassError {
        public MultipleRequestsError() {
            super("You are trying to run multiple requests at the same time.");
        }
    }

    public static class ServerLoadError extends OverpassError {
        public long timeout;
        public ServerLoadError(long timeout) {
            super("The Overpass server is currently under load and declined the request. Try again later or retry with reduced timeout value.");
            this.timeout = timeout;
        }
    }

    /**
     * An unknown kind of error happened during the request.
     */
    public static class UnknownOverpassError extends OverpassError {
        public String message;
        public UnknownOverpassError(String message) {
            super("An unknown kind of error happened during the request.");
            this.message = message;
        }
    }
}
