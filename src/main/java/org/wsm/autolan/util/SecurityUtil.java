package org.wsm.autolan.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Утилитарный класс для обеспечения безопасности чувствительных данных.
 * Предоставляет методы шифрования, дешифрования и обфускации строк.
 */
public class SecurityUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("SecurityUtil");
    
    // Статический секретный ключ для шифрования, генерируется при инициализации класса
    private static final SecretKey SECRET_KEY;
    
    // Соль для создания постоянного ключа
    private static final String SEED_PHRASE = "autolan_secure_seed_7462519";
    
    // XOR ключ для простой обфускации
    private static final byte[] XOR_KEY = {42, 13, 37, 121, 86, 91, 22, 7};
    
    static {
        SecretKey tempKey = null;
        try {
            // Генерируем детерминированный ключ на основе фиксированной фразы
            tempKey = generateFixedKey(SEED_PHRASE);
        } catch (Exception e) {
            LOGGER.error("Ошибка при генерации ключа AES", e);
            // Запасной вариант - создаем случайный ключ
            try {
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(128);
                tempKey = keyGen.generateKey();
            } catch (NoSuchAlgorithmException ex) {
                LOGGER.error("Не удалось создать запасной ключ", ex);
            }
        }
        SECRET_KEY = tempKey;
    }
    
    /**
     * Генерирует секретный ключ на основе заданной строки.
     * Это обеспечивает одинаковый ключ шифрования при каждом запуске.
     * 
     * @param seed строка для генерации ключа
     * @return секретный ключ для AES шифрования
     */
    private static SecretKey generateFixedKey(String seed) throws Exception {
        // Используем SHA-256 для получения 32-байтового хэша из фразы
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
        
        // Создаем ключ нужного размера (128 бит / 16 байт)
        byte[] aesKeyBytes = new byte[16];
        System.arraycopy(keyBytes, 0, aesKeyBytes, 0, 16);
        
        return new SecretKeySpec(aesKeyBytes, "AES");
    }
    
    /**
     * Шифрует строку с использованием AES и кодирует результат в Base64.
     * 
     * @param plainText текст для шифрования
     * @return зашифрованная строка в формате Base64, или null в случае ошибки
     */
    public static String encryptAES(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            LOGGER.error("Ошибка при шифровании строки", e);
            return null;
        }
    }
    
    /**
     * Расшифровывает строку из формата Base64 с использованием AES.
     * 
     * @param encryptedText зашифрованная строка в формате Base64
     * @return расшифрованная строка, или null в случае ошибки
     */
    public static String decryptAES(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY);
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedText);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Ошибка при расшифровке строки", e);
            return null;
        }
    }
    
    /**
     * Простая XOR-обфускация строки с последующим кодированием в Base64.
     * Подходит для базовой защиты, но не для критически важных данных.
     * 
     * @param input строка для обфускации
     * @return обфусцированная строка в формате Base64
     */
    public static String obfuscate(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        try {
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            byte[] outputBytes = new byte[inputBytes.length];
            
            for (int i = 0; i < inputBytes.length; i++) {
                outputBytes[i] = (byte) (inputBytes[i] ^ XOR_KEY[i % XOR_KEY.length]);
            }
            
            return Base64.getEncoder().encodeToString(outputBytes);
        } catch (Exception e) {
            LOGGER.error("Ошибка при обфускации строки", e);
            return input; // Возвращаем исходную строку в случае ошибки
        }
    }
    
    /**
     * Деобфускация строки, закодированной методом obfuscate.
     * 
     * @param obfuscated обфусцированная строка в формате Base64
     * @return исходная строка
     */
    public static String deobfuscate(String obfuscated) {
        if (obfuscated == null || obfuscated.isEmpty()) {
            return obfuscated;
        }
        
        try {
            byte[] inputBytes = Base64.getDecoder().decode(obfuscated);
            byte[] outputBytes = new byte[inputBytes.length];
            
            for (int i = 0; i < inputBytes.length; i++) {
                outputBytes[i] = (byte) (inputBytes[i] ^ XOR_KEY[i % XOR_KEY.length]);
            }
            
            return new String(outputBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Ошибка при деобфускации строки", e);
            return ""; // Возвращаем пустую строку в случае ошибки
        }
    }
    
    /**
     * Улучшенная версия обфускации строк для URL и ключей.
     * Использует более надежный двухэтапный подход:
     * 1. XOR обфускация с уникальным ключом
     * 2. AES шифрование результата
     *
     * @param input строка для обфускации
     * @return обфусцированная строка или null в случае ошибки
     */
    public static String safeObfuscate(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        try {
            // Сначала обфусцируем с помощью XOR
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            byte[] xoredBytes = new byte[inputBytes.length];
            
            for (int i = 0; i < inputBytes.length; i++) {
                xoredBytes[i] = (byte) (inputBytes[i] ^ XOR_KEY[i % XOR_KEY.length]);
            }
            
            // Затем шифруем с помощью AES
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY);
            byte[] encryptedBytes = cipher.doFinal(xoredBytes);
            
            // Кодируем в Base64 для безопасного хранения
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            LOGGER.error("Ошибка при безопасной обфускации строки", e);
            return null;
        }
    }
    
    /**
     * Расшифровывает строку, обфусцированную методом safeObfuscate.
     *
     * @param obfuscated обфусцированная строка
     * @return расшифрованная строка или null в случае ошибки
     */
    public static String safeDeobfuscate(String obfuscated) {
        if (obfuscated == null || obfuscated.isEmpty()) {
            return obfuscated;
        }
        
        try {
            // Декодируем из Base64
            byte[] encryptedBytes = Base64.getDecoder().decode(obfuscated);
            
            // Расшифровываем с помощью AES
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY);
            byte[] xoredBytes = cipher.doFinal(encryptedBytes);
            
            // Применяем XOR для получения исходной строки
            byte[] outputBytes = new byte[xoredBytes.length];
            for (int i = 0; i < xoredBytes.length; i++) {
                outputBytes[i] = (byte) (xoredBytes[i] ^ XOR_KEY[i % XOR_KEY.length]);
            }
            
            return new String(outputBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Ошибка при безопасной деобфускации строки", e);
            return null;
        }
    }
    
    /**
     * Метод для маскирования части строки в логах или UI.
     * Заменяет среднюю часть строки на звездочки.
     * 
     * @param input строка для маскирования
     * @return маскированная строка
     */
    public static String maskSensitiveData(String input) {
        if (input == null || input.isEmpty()) {
            return "***";
        }
        
        int length = input.length();
        if (length <= 8) {
            return "***";
        }
        
        // Оставляем первые и последние 4 символа, остальное маскируем
        return input.substring(0, 4) + "..." + input.substring(length - 4);
    }
    
    /**
     * Утилитарный метод для генерации обфусцированных строк из исходных значений.
     * Использует улучшенный алгоритм обфускации.
     * 
     * @param value исходная строка
     * @return обфусцированная строка для вставки в код
     */
    public static String generateObfuscatedValue(String value) {
        return safeObfuscate(value);
    }
    
    /**
     * Тестовый метод для проверки обфускации/деобфускации строк.
     * Использовать только для разработки, перед коммитом удалить.
     */
} 