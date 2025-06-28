package org.wsm.autolan.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wsm.autolan.util.SecurityUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class NgrokStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NgrokStateManager");
    private static final String STATE_FILE_NAME = "agent_state.json";
    // Директория для хранения, аналогично PersistentClientIdManager
    private static final String SYSTEM_FOLDER_PATH = "Microsoft/Industry";
    
    private final Path statePath;
    private final Gson gson;
    private NgrokState state;

    public NgrokStateManager() {
        // Получаем путь к системной директории %APPDATA% (Windows)
        String appDataPath = System.getenv("APPDATA");
        if (appDataPath == null) {
            // Резервный путь для других систем
            appDataPath = System.getProperty("user.home");
            LOGGER.info("APPDATA не найден, используется user.home: {}", appDataPath);
        }
        
        // Формируем полный путь к файлу
        Path systemFolderPath = Paths.get(appDataPath, SYSTEM_FOLDER_PATH);
        this.statePath = systemFolderPath.resolve(STATE_FILE_NAME);
        
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadState();
    }

    private void loadState() {
        try {
            if (Files.exists(statePath)) {
                String json = Files.readString(statePath);
                state = gson.fromJson(json, NgrokState.class);
                
                // Проверка на корректность загруженных данных
                if (state == null) {
                    LOGGER.warn("Загружено некорректное состояние ngrok, создаем новое");
                    state = new NgrokState();
                    saveState();
                    return;
                }
                
                // Если ключ зашифрован, расшифровываем его для использования в памяти
                if (state.lastTemporaryKey != null && !state.lastTemporaryKey.isEmpty()) {
                    try {
                        // Проверяем, есть ли признаки того, что ключ зашифрован
                        if (looksLikeBase64(state.lastTemporaryKey)) {
                            String decryptedKey = SecurityUtil.decryptAES(state.lastTemporaryKey);
                            if (decryptedKey != null) {
                                // Заменяем зашифрованное значение на расшифрованное в памяти
                                state.lastTemporaryKey = decryptedKey;
                                LOGGER.info("Загружено состояние ngrok: ключ успешно расшифрован");
                            } else {
                                // Если не удалось расшифровать, но похоже на Base64, предполагаем что это прямой ключ
                                LOGGER.info("Загружено состояние ngrok: ключ используется как есть");
                            }
                        } else {
                            LOGGER.info("Загружено состояние ngrok: ключ не зашифрован, используется как есть");
                        }
                    } catch (Exception e) {
                        LOGGER.error("Ошибка при расшифровке ключа, используем как есть", e);
                    }
                } else {
                    LOGGER.info("Загружено состояние ngrok: ключ отсутствует");
                }
            } else {
                state = new NgrokState();
                LOGGER.info("Создано новое состояние ngrok (файл не существовал)");
                saveState();
            }
        } catch (IOException e) {
            LOGGER.error("Ошибка при загрузке состояния ngrok", e);
            state = new NgrokState();
        }
    }

    private void saveState() {
        try {
            // Создаем директории при необходимости
            Files.createDirectories(statePath.getParent());
            
            // Создаем копию объекта состояния для сохранения на диск
            NgrokState stateToPersist = new NgrokState();
            
            // Если есть ключ, шифруем его перед сохранением
            if (state.lastTemporaryKey != null && !state.lastTemporaryKey.isEmpty()) {
                String encryptedKey = SecurityUtil.encryptAES(state.lastTemporaryKey);
                if (encryptedKey != null) {
                    stateToPersist.lastTemporaryKey = encryptedKey;
                    LOGGER.info("Ключ успешно зашифрован для сохранения");
                } else {
                    LOGGER.error("Не удалось зашифровать ключ для сохранения");
                    // В случае ошибки шифрования не сохраняем ключ вообще
                    stateToPersist.lastTemporaryKey = null;
                }
            } else {
                stateToPersist.lastTemporaryKey = null;
            }
            
            String json = gson.toJson(stateToPersist);
            Files.writeString(statePath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOGGER.info("Сохранено состояние ngrok: {}", 
                    state.lastTemporaryKey != null ? "имеется зашифрованный временный ключ" : "нет временного ключа");
        } catch (IOException e) {
            LOGGER.error("Не удалось сохранить состояние ngrok", e);
        }
    }
    
    /**
     * Проверяет, похоже ли строка на Base64-закодированную
     * @param s строка для проверки
     * @return true если строка похожа на Base64
     */
    private boolean looksLikeBase64(String s) {
        // Проверка на наличие символов, которые не могут быть в Base64
        return s.matches("^[A-Za-z0-9+/=]+$") && s.length() % 4 == 0;
    }

    public String getLastTemporaryKey() {
        return state.lastTemporaryKey;
    }

    public void setLastTemporaryKey(String key) {
        // Сохраняем ключ в памяти в незашифрованном виде для использования
        state.lastTemporaryKey = key;
        // При сохранении ключ будет автоматически зашифрован
        saveState();
    }

    public void clearLastTemporaryKey() {
        state.lastTemporaryKey = null;
        saveState();
    }

    public static class NgrokState {
        @SerializedName("last_temporary_key")
        private String lastTemporaryKey;

        public NgrokState() {
            this.lastTemporaryKey = null;
        }
    }
} 