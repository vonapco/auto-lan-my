package org.wsm.autolan.tools;

import org.wsm.autolan.util.SecurityUtil;

/**
 * Утилитарный класс для генерации обфусцированных строк для вставки в исходный код.
 */
public class GenerateObfuscatedValues {
    
    /**
     * Генерирует обфусцированные версии строк для использования в коде.
     * 
     * @param args аргументы командной строки, не используются
     */
    public static void main(String[] args) {
        // Для безопасности не храним реальный URL в репозитории.
        // Передайте реальный URL через аргумент командной строки или переменную окружения GENERATE_URL.
        String serverUrl = args.length > 0 ? args[0] : System.getenv().getOrDefault("GENERATE_URL", "http://example.com:5000");
        String apiKey = "13722952";
        
        // Генерируем обфусцированные строки
        String obfuscatedServerUrl = SecurityUtil.safeObfuscate(serverUrl);
        String obfuscatedApiKey = SecurityUtil.safeObfuscate(apiKey);
        
        // Проверяем правильность обфускации и дешифрования
        String decryptedServerUrl = SecurityUtil.safeDeobfuscate(obfuscatedServerUrl);
        String decryptedApiKey = SecurityUtil.safeDeobfuscate(obfuscatedApiKey);
        
        // Выводим результаты
        System.out.println("=== Оригинальные значения ===");
        System.out.println("SERVER_URL: " + serverUrl);
        System.out.println("API_KEY: " + apiKey);
        
        System.out.println("\n=== Обфусцированные значения ===");
        System.out.println("SERVER_URL_OBFUSCATED: " + obfuscatedServerUrl);
        System.out.println("API_KEY_OBFUSCATED: " + obfuscatedApiKey);
        
        System.out.println("\n=== Проверка дешифрования ===");
        System.out.println("SERVER_URL (расшифрованный): " + decryptedServerUrl);
        System.out.println("API_KEY (расшифрованный): " + decryptedApiKey);
        System.out.println("SERVER_URL совпадает: " + serverUrl.equals(decryptedServerUrl));
        System.out.println("API_KEY совпадает: " + apiKey.equals(decryptedApiKey));
        
        System.out.println("\n=== Готовый код для вставки ===");
        System.out.println("private static final String SERVER_URL_OBFUSCATED = \"" + obfuscatedServerUrl + "\";");
        System.out.println("private static final String API_KEY_OBFUSCATED = \"" + obfuscatedApiKey + "\";");
    }
} 