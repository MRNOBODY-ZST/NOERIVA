package io.noeriva.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.WorkspaceModels.WorkspaceInterface;

/** Alphabetical directory continuation; names are values, never SQL fragments. */
record InterfaceCursor(String scope,String name,String id) {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static String scope(String org,String q,String site,String device){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(List.of(org,q,site,device))));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static InterfaceCursor decode(String value,String scope){
        if(value==null||value.isEmpty())return new InterfaceCursor(scope,"","");
        try{
            if(value.length()>4096||!value.startsWith("i1:"))throw new IllegalArgumentException();
            var cursor=JSON.readValue(Base64.getUrlDecoder().decode(value.substring(3)),InterfaceCursor.class);
            if(!scope.equals(cursor.scope())||cursor.name()==null||cursor.name().codePointCount(0,cursor.name().length())>512||cursor.id()==null||cursor.id().isEmpty()||cursor.id().length()>64)throw new IllegalArgumentException();
            return cursor;
        }catch(Exception error){throw new IllegalArgumentException("Invalid interface cursor or changed query scope");}
    }
    static String encode(WorkspaceInterface item,String scope){
        return "i1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(new InterfaceCursor(scope,prefix(item.name()),item.id())));
    }
    static String prefix(String name){return name.substring(0,name.offsetByCodePoints(0,Math.min(512,name.codePointCount(0,name.length()))));}
    static String alphabet(String name){return prefix(name).toLowerCase(Locale.ROOT);}
    static Comparator<WorkspaceInterface> order(){return Comparator.comparing((WorkspaceInterface i)->alphabet(i.name())).thenComparing(WorkspaceInterface::id);}
    boolean before(WorkspaceInterface item){int names=alphabet(item.name()).compareTo(alphabet(name));return id.isEmpty()||names>0||names==0&&item.id().compareTo(id)>0;}
}
