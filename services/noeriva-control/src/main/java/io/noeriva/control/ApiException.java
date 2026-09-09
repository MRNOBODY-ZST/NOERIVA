package io.noeriva.control;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiException(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","The requested resource was not found in your scope"); }
    public static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT,"REVISION_CONFLICT","The resource changed; refresh before trying again"); }
}
