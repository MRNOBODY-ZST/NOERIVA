package io.noeriva.control.devices;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import io.noeriva.control.ApiException;

@Component
public class CredentialVault {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    private final JsonMapper json=JsonMapper.builder().build();
    public CredentialVault(@Value("${NOERIVA_CREDENTIAL_KEY:}") String encoded) {
        if(encoded==null||encoded.isBlank()){key=null;return;}
        try{key=Base64.getDecoder().decode(encoded);if(key.length!=32)throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("NOERIVA_CREDENTIAL_KEY must be a Base64 encoded 32-byte key");}
    }
    public boolean ready(){return key!=null;}
    private void required(){if(!ready())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CREDENTIAL_VAULT_UNAVAILABLE","Configure the credential encryption key before saving device access");}
    public String encrypt(String scope,DeviceProtocol.Secrets secrets){
        required();byte[] plain=json.writeValueAsBytes(secrets);
        try{
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(plain);
            byte[] envelope=new byte[1+nonce.length+encrypted.length];envelope[0]=1;System.arraycopy(nonce,0,envelope,1,12);System.arraycopy(encrypted,0,envelope,13,encrypted.length);
            return Base64.getEncoder().encodeToString(envelope);
        }catch(Exception e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CREDENTIAL_ENCRYPTION_FAILED","Device credentials could not be encrypted");}
        finally{Arrays.fill(plain,(byte)0);}
    }
    public DeviceProtocol.Secrets decrypt(String scope,String encoded){
        required();byte[] plain=null;
        try{
            byte[] envelope=Base64.getDecoder().decode(encoded);if(envelope.length<30||envelope[0]!=1)throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(envelope,1,13)));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));plain=cipher.doFinal(envelope,13,envelope.length-13);
            return json.readValue(plain,DeviceProtocol.Secrets.class);
        }catch(Exception e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CREDENTIAL_DECRYPTION_FAILED","Device credentials cannot be opened with this key and scope");}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
}
