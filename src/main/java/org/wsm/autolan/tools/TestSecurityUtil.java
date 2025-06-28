package org.wsm.autolan.tools;

import org.wsm.autolan.util.SecurityUtil;

/**
 * Класс для тестирования методов шифрования и обфускации.
 * Используется только во время разработки для генерации корректных значений.
 */
public class TestSecurityUtil {
    public static void main(String[] args) {
        System.out.println("==== Тест методов безопасности ====");
        
        // URL-адреса и ключи для тестирования
        String[] testValues = {
            // Реальный URL можно передать через переменную окружения или аргумент, здесь только пример.
            System.getenv().getOrDefault("TEST_URL", "http://example.com:5000"),
            "13722952",
            "http://example.com/api",
            "some_secret_key_123"
        };
        
        System.out.println("\n== Простая обфускация ==");
        for (String value : testValues) {
            String obfuscated = SecurityUtil.obfuscate(value);
            String deobfuscated = SecurityUtil.deobfuscate(obfuscated);
            
            System.out.println("Исходное значение: " + value);
            System.out.println("Обфусцированное:   " + obfuscated);
            System.out.println("Расшифрованное:    " + deobfuscated);
            System.out.println("Совпадает:         " + value.equals(deobfuscated));
            System.out.println();
        }
        
        System.out.println("\n== Улучшенная обфускация ==");
        for (String value : testValues) {
            String safeObfuscated = SecurityUtil.safeObfuscate(value);
            String safeDeobfuscated = SecurityUtil.safeDeobfuscate(safeObfuscated);
            
            System.out.println("Исходное значение: " + value);
            System.out.println("Обфусцированное:   " + safeObfuscated);
            System.out.println("Расшифрованное:    " + safeDeobfuscated);
            System.out.println("Совпадает:         " + value.equals(safeDeobfuscated));
            System.out.println();
        }
        
        System.out.println("\n== AES шифрование ==");
        for (String value : testValues) {
            String encrypted = SecurityUtil.encryptAES(value);
            String decrypted = SecurityUtil.decryptAES(encrypted);
            
            System.out.println("Исходное значение: " + value);
            System.out.println("Зашифрованное:     " + encrypted);
            System.out.println("Расшифрованное:    " + decrypted);
            System.out.println("Совпадает:         " + value.equals(decrypted));
            System.out.println();
        }
        
        // Генерация значений для вставки в код
        System.out.println("\n== Значения для использования в коде ==");
        String realUrl = System.getenv().getOrDefault("TEST_URL", "http://example.com:5000");
        System.out.println("// Обфусцированный URL (safe): ");
        System.out.println("private static final String SERVER_URL_OBFUSCATED = \"" + 
                           SecurityUtil.safeObfuscate(realUrl) + "\";");
        
        System.out.println("\n// Обфусцированный API ключ (safe): ");
        System.out.println("private static final String API_KEY_OBFUSCATED = \"" + 
                           SecurityUtil.safeObfuscate("13722952") + "\";");
    }
} 