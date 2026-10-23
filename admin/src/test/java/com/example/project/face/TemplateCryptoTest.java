package com.example.project.face;
import org.junit.jupiter.api.Test;
import com.example.project.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
class TemplateCryptoTest {
    TemplateCrypto crypto=new TemplateCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    @Test void ciphertextIsRandomizedAndBoundToUserAndVersion() {
        String context="4:local:model:1",plain="private-feature";
        String a=crypto.encrypt(plain,context),b=crypto.encrypt(plain,context);
        assertNotEquals(a,b);assertFalse(a.contains(plain));assertEquals(plain,crypto.decrypt(a,context));
        assertThrows(ServiceException.class,()->crypto.decrypt(a,"5:local:model:1"));
        assertThrows(ServiceException.class,()->crypto.decrypt(a,"4:local:model:2"));
        assertThrows(ServiceException.class,()->crypto.decrypt(a,"4:tencent:model:1"));
        assertThrows(ServiceException.class,()->crypto.decrypt(a,"4:local:other-model:1"));
        assertThrows(ServiceException.class,()->crypto.decrypt(a.substring(1),context));
    }
    @Test void absentKeyDoesNotPermitEncryption() {
        assertThrows(ServiceException.class,()->new TemplateCrypto("").encrypt("x","context"));
    }
}
