package com.example.models;

public class TemplateToken {

    public static final int TOKEN_BYTE_SIZE = 16;
    public static final int MODE_PHONETIC_TOKEN = 0x01;
    public static final int MODE_LOSSLESS_IMAGE_HEADER = 0x02;

    // Categories
    public static final int CATEGORY_TACTICAL_MAP = 0x01;
    public static final int CATEGORY_EMERGENCY_FORM = 0x02;
    public static final int CATEGORY_MEDICAL_TRIAGE = 0x03;
    public static final int CATEGORY_LOGISTICS = 0x04;
    public static final int CATEGORY_CUSTOM_VECTOR = 0x05;
    public static final int CATEGORY_LOSSLESS_IMAGE = 0x06;
    public static final int CATEGORY_DISASTER_HAZARD = 0x07;

    // Icons / Stamps
    public static final int ICON_NONE = 0x00;
    public static final int ICON_FLOOD = 0x01;
    public static final int ICON_FIRE = 0x02;
    public static final int ICON_ROADBLOCK = 0x03;
    public static final int ICON_MEDICAL = 0x04;
    public static final int ICON_SHELTER = 0x05;
    public static final int ICON_HAZARD = 0x06;
    public static final int ICON_IMAGE_CONTAINER = 0x07;

    // Severity Levels
    public static final int SEVERITY_LOW = 0x01;
    public static final int SEVERITY_MEDIUM = 0x02;
    public static final int SEVERITY_HIGH = 0x03;
    public static final int SEVERITY_CRITICAL = 0x04;

    private int mode;          // 1 Byte
    private int categoryId;    // 1 Byte
    private int templateId;    // 2 Bytes (0 - 65535)
    private int paramX;        // 2 Bytes (0 - 65535 Canvas X or Image Width)
    private int paramY;        // 2 Bytes (0 - 65535 Canvas Y or Image Height)
    private int stampIcon;     // 1 Byte
    private int severity;      // 1 Byte
    private int paramValue;    // 2 Bytes (e.g. depth in cm, triage score, or chunk count)
    private int reserved;      // 2 Bytes
    private int crc16;         // 2 Bytes

    public TemplateToken() {
        this.mode = MODE_PHONETIC_TOKEN;
        this.categoryId = CATEGORY_TACTICAL_MAP;
        this.templateId = 0;
        this.paramX = 0;
        this.paramY = 0;
        this.stampIcon = ICON_NONE;
        this.severity = SEVERITY_LOW;
        this.paramValue = 0;
        this.reserved = 0;
        this.crc16 = 0;
    }

    public TemplateToken(int mode, int categoryId, int templateId, int paramX, int paramY,
                         int stampIcon, int severity, int paramValue, int reserved) {
        this.mode = mode & 0xFF;
        this.categoryId = categoryId & 0xFF;
        this.templateId = templateId & 0xFFFF;
        this.paramX = paramX & 0xFFFF;
        this.paramY = paramY & 0xFFFF;
        this.stampIcon = stampIcon & 0xFF;
        this.severity = severity & 0xFF;
        this.paramValue = paramValue & 0xFFFF;
        this.reserved = reserved & 0xFFFF;
        this.crc16 = calculateCrc16(this.toByteArrayWithoutCrc());
    }

    public byte[] toByteArrayWithoutCrc() {
        byte[] bytes = new byte[14];
        bytes[0] = (byte) (mode & 0xFF);
        bytes[1] = (byte) (categoryId & 0xFF);
        bytes[2] = (byte) ((templateId >> 8) & 0xFF);
        bytes[3] = (byte) (templateId & 0xFF);
        bytes[4] = (byte) ((paramX >> 8) & 0xFF);
        bytes[5] = (byte) (paramX & 0xFF);
        bytes[6] = (byte) ((paramY >> 8) & 0xFF);
        bytes[7] = (byte) (paramY & 0xFF);
        bytes[8] = (byte) (stampIcon & 0xFF);
        bytes[9] = (byte) (severity & 0xFF);
        bytes[10] = (byte) ((paramValue >> 8) & 0xFF);
        bytes[11] = (byte) (paramValue & 0xFF);
        bytes[12] = (byte) ((reserved >> 8) & 0xFF);
        bytes[13] = (byte) (reserved & 0xFF);
        return bytes;
    }

    public byte[] toByteArray() {
        byte[] bytes = new byte[TOKEN_BYTE_SIZE];
        byte[] withoutCrc = toByteArrayWithoutCrc();
        System.arraycopy(withoutCrc, 0, bytes, 0, 14);

        int computedCrc = calculateCrc16(withoutCrc);
        this.crc16 = computedCrc;

        bytes[14] = (byte) ((computedCrc >> 8) & 0xFF);
        bytes[15] = (byte) (computedCrc & 0xFF);
        return bytes;
    }

    public static TemplateToken fromByteArray(byte[] data) {
        if (data == null || data.length < TOKEN_BYTE_SIZE) {
            return null;
        }

        TemplateToken token = new TemplateToken();
        token.mode = data[0] & 0xFF;
        token.categoryId = data[1] & 0xFF;
        token.templateId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        token.paramX = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        token.paramY = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        token.stampIcon = data[8] & 0xFF;
        token.severity = data[9] & 0xFF;
        token.paramValue = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
        token.reserved = ((data[12] & 0xFF) << 8) | (data[13] & 0xFF);
        token.crc16 = ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);

        // Validate CRC16
        byte[] withoutCrc = new byte[14];
        System.arraycopy(data, 0, withoutCrc, 0, 14);
        int computedCrc = calculateCrc16(withoutCrc);

        if (computedCrc != token.crc16) {
            return null; // Corrupted packet detected
        }

        return token;
    }

    public static int calculateCrc16(byte[] data) {
        int crc = 0xFFFF; // CCITT-16 initial value
        for (byte b : data) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xFFFF;
            crc ^= (b & 0xFF);
            crc ^= (crc & 0xFF) >> 4;
            crc ^= (crc << 12) & 0xFFFF;
            crc ^= ((crc & 0xFF) << 5) & 0xFFFF;
        }
        return crc & 0xFFFF;
    }

    public boolean isValid() {
        return this.crc16 == calculateCrc16(toByteArrayWithoutCrc());
    }

    // Getters and Setters
    public int getMode() { return mode; }
    public void setMode(int mode) { this.mode = mode & 0xFF; }

    public int getCategoryId() { return categoryId; }
    public void setCategoryId(int categoryId) { this.categoryId = categoryId & 0xFF; }

    public int getTemplateId() { return templateId; }
    public void setTemplateId(int templateId) { this.templateId = templateId & 0xFFFF; }

    public int getParamX() { return paramX; }
    public void setParamX(int paramX) { this.paramX = paramX & 0xFFFF; }

    public int getParamY() { return paramY; }
    public void setParamY(int paramY) { this.paramY = paramY & 0xFFFF; }

    public int getStampIcon() { return stampIcon; }
    public void setStampIcon(int stampIcon) { this.stampIcon = stampIcon & 0xFF; }

    public int getSeverity() { return severity; }
    public void setSeverity(int severity) { this.severity = severity & 0xFF; }

    public int getParamValue() { return paramValue; }
    public void setParamValue(int paramValue) { this.paramValue = paramValue & 0xFFFF; }

    public int getReserved() { return reserved; }
    public void setReserved(int reserved) { this.reserved = reserved & 0xFFFF; }

    public int getCrc16() { return crc16; }
    public void setCrc16(int crc16) { this.crc16 = crc16 & 0xFFFF; }
}