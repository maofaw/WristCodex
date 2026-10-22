package com.n.watchremote;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** User-supplied speech key, sealed with an app-scoped Android Keystore AES key. */
final class CloudVoiceKey {
  private final SharedPreferences prefs;private final String alias;
  CloudVoiceKey(Context context){this(context,"personal");}
  CloudVoiceKey(Context context,String scope){
    if(!scope.matches("[a-z_]+"))throw new IllegalArgumentException();
    prefs=context.getSharedPreferences("cloud_voice_"+scope,Context.MODE_PRIVATE);
    alias="wristcodex.voice."+scope;
  }
  boolean saved(){return prefs.contains("sealed");}
  private KeyStore store()throws Exception{KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);return s;}
  private SecretKey secret(boolean create)throws Exception{
    KeyStore s=store();
    if(s.containsAlias(alias))return (SecretKey)s.getKey(alias,null);
    if(!create)throw new IllegalStateException("Key unavailable");
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }
  void save(String value)throws Exception{
    if(value.length()<16||!CloudProtocol.validKey(value))throw new IllegalArgumentException("Invalid speech key");
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret(true));
    String sealed=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    if(!prefs.edit().putString("sealed",sealed).commit())throw new IllegalStateException("Save failed");
  }
  String load()throws Exception{
    String sealed=prefs.getString("sealed","");if(sealed.isEmpty())return "";
    String[] parts=sealed.split("\\.",-1);if(parts.length!=2)throw new IllegalStateException("Invalid saved key");
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE,secret(false),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
    return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
  }
  void clear()throws Exception{if(!prefs.edit().remove("sealed").commit())throw new IllegalStateException("Clear failed");store().deleteEntry(alias);}
}
