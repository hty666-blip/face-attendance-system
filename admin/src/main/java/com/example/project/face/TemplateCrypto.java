package com.example.project.face;
import com.example.project.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
@Component
public class TemplateCrypto {
    private final byte[] key;
    public TemplateCrypto(@Value("${face.template-key:}") String encoded) {
        byte[] decoded;
        try {decoded=Base64.getDecoder().decode(encoded);} catch(IllegalArgumentException e) {decoded=new byte[0];}
        key=decoded;
    }
    public boolean configured(){return key.length==32;}
    private String transform(String value,String context,boolean encrypt) {
        if(!configured()) throw new ServiceException("503","人脸模板加密密钥未配置，请运行启动脚本");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce, input;
            if(encrypt) {nonce=new byte[12];new SecureRandom().nextBytes(nonce);input=value.getBytes(StandardCharsets.UTF_8);}
            else {
                byte[] bytes=Base64.getDecoder().decode(value);
                if(bytes.length<28) throw new IllegalArgumentException();
                nonce=Arrays.copyOfRange(bytes,0,12);input=Arrays.copyOfRange(bytes,12,bytes.length);
            }
            cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));byte[] output=cipher.doFinal(input);
            if(!encrypt) return new String(output,StandardCharsets.UTF_8);
            byte[] combined=new byte[nonce.length+output.length];System.arraycopy(nonce,0,combined,0,12);System.arraycopy(output,0,combined,12,output.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch(Exception error) {throw new ServiceException("409","模板加密校验失败，请检查密钥或重新录入");}
    }
    public String encrypt(String value,String context){return transform(value,context,true);}
    public String decrypt(String value,String context){return transform(value,context,false);}
}
