package io.noeriva.query;
public final class ProviderUnavailableException extends RuntimeException {
    private final String provider;
    public ProviderUnavailableException(String provider,String message) {super(message);this.provider=provider;}
    public ProviderUnavailableException(String provider,String message,Throwable cause) {super(message,cause);this.provider=provider;}
    public String provider() {return provider;}
}
