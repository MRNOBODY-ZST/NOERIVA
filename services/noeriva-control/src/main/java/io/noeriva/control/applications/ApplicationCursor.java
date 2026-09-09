package io.noeriva.control.applications;
import io.noeriva.control.ApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
final class ApplicationCursor {
    static String encode(String scope,String value){return Base64.getUrlEncoder().withoutPadding().encodeToString((ApplicationRates.hash(scope)+":"+value).getBytes(StandardCharsets.UTF_8));}
    static String decode(String scope,String cursor){if(cursor==null||cursor.isBlank())return "";try{if(cursor.length()>1024)throw new IllegalArgumentException();String value=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);String prefix=ApplicationRates.hash(scope)+":";if(!value.startsWith(prefix))throw new IllegalArgumentException();return value.substring(prefix.length());}catch(Exception e){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","The cursor does not match this query");}}
}
