package org.wsm.autolan.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.util.SecurityUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Менеджер для хранения постоянного идентификатора клиента.
 * Сохраняет client_id в системной директории для обеспечения стабильной
 * идентификации пользователя независимо от версии Minecraft или расположения папок.
 * Шифрует данные перед сохранением на диск.
 */
public class PersistentClientIdManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoLanPersistentId");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Директория и файл для хранения идентификатора
    private static final String SYSTEM_FOLDER_PATH = "Microsoft/Industry";
    private static final String CLIENT_ID_FILENAME = "client.id";
    
    private ClientIdData clientIdData;
    private final Path clientIdFilePath;
    private String decryptedClientId; // Расшифрованное значение в памяти

    public PersistentClientIdManager() {
        // Получаем путь к системной директории %APPDATA% (Windows)
        String appDataPath = System.getenv("APPDATA");
        if (appDataPath == null) {
            // Резервный путь для других систем
            appDataPath = System.getProperty("user.home");
            LOGGER.info("APPDATA не найден, используется user.home: {}", appDataPath);
        }
        
        // Формируем полный путь к файлу
        Path systemFolderPath = Paths.get(appDataPath, SYSTEM_FOLDER_PATH);
        clientIdFilePath = systemFolderPath.resolve(CLIENT_ID_FILENAME);
    }
    
    /**
     * Загружает или создает client_id.
     * @return Идентификатор клиента
     */
    public String getOrCreateClientId() {
        loadOrCreateClientId();
        return decryptedClientId;
    }
    
    /**
     * Загружает существующий client_id или создает новый.
     */
    private void loadOrCreateClientId() {
        if (Files.exists(clientIdFilePath)) {
            try {
                loadClientId();
            } catch (Exception e) {
                LOGGER.warn("Проблема при загрузке client_id, создаем новый", e);
                createClientId();
            }
        } else {
            createClientId();
        }
    }
    
    /**
     * Загружает существующий client_id из файла.
     */
    private void loadClientId() {
        try {
            String jsonContent = Files.readString(clientIdFilePath);
            clientIdData = GSON.fromJson(jsonContent, ClientIdData.class);
            
            // Проверка на корректность загруженных данных
            if (clientIdData == null) {
                LOGGER.warn("Загружен NULL объект client_id, создаем новый");
                createClientId();
                return;
            }
            
            // Проверяем, является ли client_id зашифрованным
            String clientId = clientIdData.getClientId();
            if (clientId == null || clientId.isEmpty()) {
                LOGGER.warn("Загружен пустой client_id, создаем новый");
                createClientId();
                return;
            }

            boolean needsReEncryption = false; // Флаг, что файл хранит ID в открытом виде и его надо зашифровать

            if (isLikelyBase64(clientId)) {
                try {
                    // Пытаемся расшифровать
                    decryptedClientId = SecurityUtil.decryptAES(clientId);
                    if (decryptedClientId == null) {
                        LOGGER.warn("Не удалось расшифровать client_id, возможно он в старом формате");
                        // Используем как есть, если похоже на UUID
                        if (looksLikeUuid(clientId)) {
                            decryptedClientId = clientId;
                            LOGGER.info("Используем нерасшифрованный client_id: {}", SecurityUtil.maskSensitiveData(decryptedClientId));
                            needsReEncryption = true; // Был нешифрованный UUID
                        } else {
                            LOGGER.warn("ID не похож на UUID, создаем новый");
                            createClientId();
                            return;
                        }
                    } else {
                        LOGGER.info("Загружен и расшифрован существующий client_id: {}", 
                                SecurityUtil.maskSensitiveData(decryptedClientId));
                    }
                } catch (Exception e) {
                    LOGGER.warn("Ошибка при расшифровке client_id", e);
                    // Если не удалось расшифровать, но похоже на UUID, используем как есть
                    if (looksLikeUuid(clientId)) {
                        decryptedClientId = clientId;
                        LOGGER.info("Используем нерасшифрованный client_id: {}", SecurityUtil.maskSensitiveData(decryptedClientId));
                        needsReEncryption = true;
                    } else {
                        createClientId();
                    }
                }
            } else if (looksLikeUuid(clientId)) {
                // Если не похоже на Base64, но похоже на UUID, используем как есть
                decryptedClientId = clientId;
                LOGGER.info("Загружен существующий client_id в нешифрованном виде: {}", 
                        SecurityUtil.maskSensitiveData(decryptedClientId));
                needsReEncryption = true;
            } else {
                // Если не похоже ни на Base64, ни на UUID, создаем новый
                LOGGER.warn("Загруженный client_id имеет недопустимый формат, создаем новый");
                createClientId();
            }

            // Если необходимо, сохраняем ID в зашифрованном виде для будущих запусков
            if (needsReEncryption) {
                try {
                    String encrypted = SecurityUtil.encryptAES(decryptedClientId);
                    if (encrypted != null) {
                        clientIdData = new ClientIdData(encrypted, clientIdData.getPcName(), clientIdData.getGeneratedAt());
                        String json = GSON.toJson(clientIdData);
                        Files.writeString(clientIdFilePath, json);
                        LOGGER.info("client_id был сохранён в зашифрованном виде");
                    }
                } catch (Exception e) {
                    LOGGER.warn("Не удалось пересохранить client_id в зашифрованном виде", e);
                }
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            LOGGER.warn("Ошибка при чтении существующего client_id, будет создан новый", e);
            createClientId();
        }
    }
    
    /**
     * Создает новый client_id и сохраняет его в файл.
     */
    private void createClientId() {
        try {
            // Создаем директорию, если она не существует
            Files.createDirectories(clientIdFilePath.getParent());
            
            // Генерируем новый UUID
            decryptedClientId = UUID.randomUUID().toString();
            String computerName = getComputerName();
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            
            // Шифруем ID для хранения
            String encryptedId = SecurityUtil.encryptAES(decryptedClientId);
            if (encryptedId == null) {
                LOGGER.error("Ошибка при шифровании client_id");
                encryptedId = decryptedClientId; // Сохраняем без шифрования в случае ошибки
            }
            
            clientIdData = new ClientIdData(encryptedId, computerName, timestamp);
            
            // Сохраняем данные в файл
            String jsonContent = GSON.toJson(clientIdData);
            Files.writeString(clientIdFilePath, jsonContent);
            
            LOGGER.info("Создан и сохранен новый client_id: {} для компьютера {}", 
                    SecurityUtil.maskSensitiveData(decryptedClientId), computerName);
        } catch (IOException e) {
            LOGGER.error("Ошибка при создании client_id", e);
            // Создаем объект в памяти даже при ошибке записи
            decryptedClientId = UUID.randomUUID().toString();
            clientIdData = new ClientIdData(
                    decryptedClientId, // Сохраняем ID без шифрования для отладки
                    "unknown-pc",
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            );
        }
    }
    
    /**
     * Проверяет, похоже ли строка на Base64-закодированную
     * @param s строка для проверки
     * @return true если строка похожа на Base64
     */
    private boolean isLikelyBase64(String s) {
        // Проверка на наличие символов, которые не могут быть в Base64
        return s.matches("^[A-Za-z0-9+/=]+$") && s.length() % 4 == 0;
    }
    
    /**
     * Проверяет, похожа ли строка на UUID
     * @param s строка для проверки
     * @return true если строка похожа на UUID
     */
    private boolean looksLikeUuid(String s) {
        return s.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }
    
    /**
     * Получает имя компьютера.
     * @return Имя компьютера или "unknown-pc", если имя не удалось получить
     */
    private String getComputerName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            LOGGER.warn("Не удалось определить имя компьютера", e);
            return "unknown-pc";
        }
    }
    
    /**
     * Внутренний класс для хранения данных client_id.
     */
    public static class ClientIdData {
        @SerializedName("client_id")
        private final String clientId; // Хранит ID (зашифрованный или нет)
        
        @SerializedName("pc_name")
        private final String pcName;
        
        @SerializedName("generated_at")
        private final String generatedAt;
        
        public ClientIdData(String clientId, String pcName, String generatedAt) {
            this.clientId = clientId;
            this.pcName = pcName;
            this.generatedAt = generatedAt;
        }
        
        public String getClientId() {
            return clientId;
        }
        
        public String getPcName() {
            return pcName;
        }
        
        public String getGeneratedAt() {
            return generatedAt;
        }
    }
} 