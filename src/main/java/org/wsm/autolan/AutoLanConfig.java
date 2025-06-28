package org.wsm.autolan;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.wsm.autolan.util.SecurityUtil;

@Config(name = AutoLan.MODID)
public class AutoLanConfig implements ConfigData {
    // Хранит зашифрованную версию ключа Ngrok в файле конфигурации
    private String encrypted_ngrok_key = "";
    
    // Временное незашифрованное значение ключа в памяти
    @ConfigEntry.Gui.Excluded
    private transient String decryptedKey = "";
    
    // Геттер, который возвращает расшифрованную версию ключа для использования в коде
    public String getNgrok_key() {
        // Если в памяти уже есть расшифрованный ключ, возвращаем его
        if (decryptedKey != null && !decryptedKey.isEmpty()) {
            return decryptedKey;
        }
        
        // Если зашифрованный ключ пустой, вернем пустую строку
        if (encrypted_ngrok_key == null || encrypted_ngrok_key.isEmpty()) {
            return "";
        }
        
        // Пытаемся расшифровать ключ
        try {
            decryptedKey = SecurityUtil.decryptAES(encrypted_ngrok_key);
            return decryptedKey != null ? decryptedKey : "";
        } catch (Exception e) {
            // В случае ошибки возвращаем пустую строку
            return "";
        }
    }
    
    // Сеттер, который шифрует ключ перед сохранением
    public void setNgrok_key(String ngrok_key) {
        // Сохраняем расшифрованную версию в памяти для использования
        this.decryptedKey = ngrok_key;
        
        // Если ключ пустой, не шифруем его
        if (ngrok_key == null || ngrok_key.isEmpty()) {
            this.encrypted_ngrok_key = "";
            return;
        }
        
        // Шифруем ключ для хранения в файле
        try {
            this.encrypted_ngrok_key = SecurityUtil.encryptAES(ngrok_key);
            if (this.encrypted_ngrok_key == null) {
                this.encrypted_ngrok_key = "";
            }
        } catch (Exception e) {
            // В случае ошибки шифрования, сохраняем пустую строку
            this.encrypted_ngrok_key = "";
        }
    }
    
    // Для сериализации JSON - позволяет правильно обрабатывать get/set
    public String getEncrypted_ngrok_key() {
        return encrypted_ngrok_key;
    }
    
    // Для десериализации JSON - позволяет правильно обрабатывать get/set
    public void setEncrypted_ngrok_key(String encryptedKey) {
        this.encrypted_ngrok_key = encryptedKey;
    }
    
    @Override
    public void validatePostLoad() {
        // После загрузки из файла расшифровываем ключ сразу
        if (encrypted_ngrok_key != null && !encrypted_ngrok_key.isEmpty()) {
            try {
                this.decryptedKey = SecurityUtil.decryptAES(encrypted_ngrok_key);
            } catch (Exception e) {
                // В случае ошибки инициализируем пустым значением
                this.decryptedKey = "";
            }
        } else {
            this.decryptedKey = "";
        }
    }
}
