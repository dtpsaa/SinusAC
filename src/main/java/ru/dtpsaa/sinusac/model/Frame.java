package ru.dtpsaa.sinusac.model;

/**
 * Один кадр движения камеры игрока: абсолютные yaw/pitch.
 * Иммутабельный DTO, сериализуется Gson-ом как есть при отправке на ML-сервер.
 */
public class Frame {

    public final float yaw;
    public final float pitch;

    public Frame(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
