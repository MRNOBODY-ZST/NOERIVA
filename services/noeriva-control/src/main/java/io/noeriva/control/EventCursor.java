package io.noeriva.control;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
record EventCursor(Instant at,String id) {
    static EventCursor decode(String cursor){
        if(cursor.isEmpty())return null;
        try {var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",2);if(parts.length!=2||parts[1].length()>128)throw new IllegalArgumentException();return new EventCursor(Instant.parse(parts[0]),parts[1]);}
        catch(RuntimeException e){throw new IllegalArgumentException("Invalid event cursor");}
    }
    static String encode(Models.Event event){return Base64.getUrlEncoder().withoutPadding().encodeToString((event.observedAt()+"|"+event.id()).getBytes(StandardCharsets.UTF_8));}
}
