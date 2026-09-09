package io.noeriva.control.devices;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.noeriva.control.devices.DeviceProtocol.*;

/** Fixed-address HTTPS/1.1 transport. No redirects, proxy, cookies, or device mutation. */
final class RedfishHttps implements AutoCloseable {
    static final int MAX_BODY=2*1024*1024;
    private final Target target;
    private final SSLContext context;
    private final InetAddress address;
    private final String authorization;
    private final long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
    private final AtomicReference<Socket> active=new AtomicReference<>();
    private volatile boolean closed;
    record Response(int status,byte[] body) {}

    RedfishHttps(Target target,Secrets secrets,SSLContext systemContext) {
        this.target=target;
        if(target==null || target.host()==null || target.host().length()>253 || target.host().matches(".*[\\s/\\\\@?#].*")) throw failure("INVALID_TARGET");
        if(target.address()==null || target.port()<1 || target.port()>65535) throw failure("INVALID_TARGET");
        try { address=InetAddress.ofLiteral(target.address()); } catch(IllegalArgumentException e) { throw failure("INVALID_TARGET"); }
        if(target.username()==null || target.username().isBlank() || target.username().contains(":") || secrets==null || secrets.password()==null || secrets.password().isEmpty()) throw failure("CREDENTIALS_REQUIRED");
        authorization="Basic "+Base64.getEncoder().encodeToString((target.username()+":"+secrets.password()).getBytes(StandardCharsets.UTF_8));
        try {
            context=switch(Objects.toString(target.tlsMode(),"")) {
                case "SYSTEM" -> systemContext;
                case "PINNED" -> pinned(target.certificateSha256());
                default -> throw failure("INVALID_TLS_MODE");
            };
        } catch(GeneralSecurityException e) { throw failure("TLS_VALIDATION_FAILED"); }
    }
    Response get(String path) {
        try(Socket raw=new Socket()) {
            if(closed) throw failure("COLLECTION_CANCELLED");
            active.set(raw);
            raw.connect(new InetSocketAddress(address,target.port()),remaining());
            raw.setSoTimeout(remaining());
            // The TCP endpoint is pinned above; the original host is the JSSE peer identity and SNI name.
            try(SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(raw,target.host(),target.port(),true)) {
                active.set(tls);
                if(closed) throw failure("COLLECTION_CANCELLED");
                SSLParameters params=tls.getSSLParameters();
                params.setProtocols(Arrays.stream(tls.getSupportedProtocols()).filter(p -> p.equals("TLSv1.2")||p.equals("TLSv1.3")).toArray(String[]::new));
                if("SYSTEM".equals(target.tlsMode())) params.setEndpointIdentificationAlgorithm("HTTPS");
                else params.setEndpointIdentificationAlgorithm(null);
                if(!target.host().contains(":") && !target.host().matches("[0-9.]+")) params.setServerNames(List.of(new SNIHostName(target.host())));
                tls.setSSLParameters(params); tls.setSoTimeout(remaining()); tls.startHandshake();
                String host=target.host().contains(":") ? "["+target.host()+"]" : target.host();
                String request="GET "+path+" HTTP/1.1\r\nHost: "+host+":"+target.port()+"\r\nAuthorization: "+authorization+"\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\nUser-Agent: NOERIVA-Redfish/1\r\n\r\n";
                tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); tls.getOutputStream().flush();
                InputStream in=new BufferedInputStream(tls.getInputStream());
                String status=line(in,tls,8192); String[] parts=status.split(" ",3);
                if(parts.length<2 || !parts[0].matches("HTTP/1\\.[01]") || !parts[1].matches("[0-9]{3}")) throw failure("INVALID_HTTP_RESPONSE");
                int code=Integer.parseInt(parts[1]); Map<String,String> headers=new HashMap<>(); int bytes=status.length();
                for(int i=0;;i++) {
                    String h=line(in,tls,8192); bytes+=h.length(); if(bytes>32768 || i>100) throw failure("INVALID_HTTP_RESPONSE"); if(h.isEmpty()) break;
                    int colon=h.indexOf(':'); if(colon<=0 || Character.isWhitespace(h.charAt(0))) throw failure("INVALID_HTTP_RESPONSE");
                    String key=h.substring(0,colon).toLowerCase(Locale.ROOT),value=h.substring(colon+1).trim();
                    if(headers.putIfAbsent(key,value)!=null && (key.equals("content-length")||key.equals("transfer-encoding"))) throw failure("INVALID_HTTP_RESPONSE");
                }
                if(code<200 || code>=300) return new Response(code,new byte[0]);
                String type=headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT);
                if(!type.isEmpty() && !type.contains("application/json")) throw failure("INVALID_JSON_RESPONSE");
                if(!headers.getOrDefault("content-encoding","identity").equalsIgnoreCase("identity")) throw failure("UNSUPPORTED_CONTENT_ENCODING");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                String transfer=headers.get("transfer-encoding");
                if(transfer!=null) {
                    if(!transfer.equalsIgnoreCase("chunked") || headers.containsKey("content-length")) throw failure("INVALID_HTTP_RESPONSE");
                    for(int chunks=0;;chunks++) {
                        if(chunks>16384) throw failure("RESPONSE_TOO_LARGE");
                        String size=line(in,tls,128).split(";",2)[0].trim();
                        if(!size.matches("[0-9a-fA-F]{1,8}")) throw failure("INVALID_HTTP_RESPONSE");
                        long count=Long.parseLong(size,16); if(count==0) break;
                        if(count>MAX_BODY-out.size()) throw failure("RESPONSE_TOO_LARGE");
                        copy(in,out,tls,count); if(!line(in,tls,2).isEmpty()) throw failure("INVALID_HTTP_RESPONSE");
                    }
                } else if(headers.containsKey("content-length")) {
                    String count=headers.get("content-length"); if(!count.matches("[0-9]{1,10}")) throw failure("INVALID_HTTP_RESPONSE");
                    long length=Long.parseLong(count); if(length>MAX_BODY) throw failure("RESPONSE_TOO_LARGE"); copy(in,out,tls,length);
                } else {
                    byte[] buffer=new byte[8192]; int count;
                    while(true) { tls.setSoTimeout(remaining()); count=in.read(buffer); if(count<0) break; if(out.size()+count>MAX_BODY) throw failure("RESPONSE_TOO_LARGE"); out.write(buffer,0,count); }
                }
                return new Response(code,out.toByteArray());
            }
        } catch(Failure e) { throw e;
        } catch(SSLException e) { throw failure("TLS_VALIDATION_FAILED");
        } catch(SocketTimeoutException e) { throw failure("TIMEOUT");
        } catch(IOException|IllegalArgumentException e) { throw failure(closed ? "COLLECTION_CANCELLED" : "CONNECTION_FAILED");
        } finally { active.set(null); }
    }
    private int remaining() {
        long ms=(deadline-System.nanoTime())/1_000_000;
        if(ms<=0) throw failure("TIMEOUT");
        return (int)Math.min(ms,Math.max(250,Math.min(10000,target.timeoutMillis())));
    }
    private String line(InputStream in,SSLSocket socket,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(true) { socket.setSoTimeout(remaining()); int c=in.read(); if(c<0) throw failure("TRUNCATED_RESPONSE");
            if(c=='\n') { byte[] data=out.toByteArray(); if(data.length==0 || data[data.length-1]!='\r') throw failure("INVALID_HTTP_RESPONSE"); return new String(data,0,data.length-1,StandardCharsets.US_ASCII); }
            out.write(c); if(out.size()>limit) throw failure("INVALID_HTTP_RESPONSE");
        }
    }
    private void copy(InputStream in,ByteArrayOutputStream out,SSLSocket socket,long count) throws IOException {
        byte[] buffer=new byte[8192];
        while(count>0) { socket.setSoTimeout(remaining()); int n=in.read(buffer,0,(int)Math.min(buffer.length,count)); if(n<0) throw failure("TRUNCATED_RESPONSE"); out.write(buffer,0,n); count-=n; }
    }
    private static SSLContext pinned(String hex) throws GeneralSecurityException {
        if(hex==null || !hex.matches("[a-fA-F0-9]{64}")) throw failure("INVALID_CERTIFICATE_PIN");
        byte[] expected=HexFormat.of().parseHex(hex);
        TrustManager trust=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain,String authType) throws CertificateException { throw new CertificateException("Client certificates are unsupported"); }
            public void checkServerTrusted(X509Certificate[] chain,String authType) throws CertificateException {
                if(chain==null || chain.length==0) throw new CertificateException("Certificate missing");
                chain[0].checkValidity();
                try { if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))) throw new CertificateException("Certificate fingerprint mismatch"); }
                catch(NoSuchAlgorithmException e) { throw new CertificateException("SHA-256 unavailable"); }
            }
        };
        SSLContext context=SSLContext.getInstance("TLS"); context.init(null,new TrustManager[]{trust},null); return context;
    }
    static Failure failure(String code) {
        String message=switch(code) {
            case "TLS_VALIDATION_FAILED" -> "设备 TLS 证书校验失败，请核对信任链、主机名、有效期或 SHA-256 指纹";
            case "AUTHENTICATION_FAILED" -> "Redfish 身份认证失败，请核对独立账号和接口权限";
            case "AUTHORIZATION_FAILED" -> "Redfish 账号没有读取权限";
            case "PROTOCOL_UNSUPPORTED" -> "目标没有提供可用的 Redfish 服务根";
            case "TIMEOUT" -> "Redfish 读取超过时间预算";
            case "UNSAFE_RESOURCE_LINK" -> "设备返回的资源链接不在允许的同源 Redfish 路径内";
            default -> "Redfish 读取失败（"+code+"）";
        };
        return new Failure(code,message);
    }
    @Override public void close() { closed=true; Socket s=active.getAndSet(null); if(s!=null) try { s.close(); } catch(IOException ignored) {} }
}
