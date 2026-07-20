package de.lostesburger.mySqlPlayerBridge.Exceptions;

public class CrossVersionItemSyncException extends RuntimeException {
    public CrossVersionItemSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
