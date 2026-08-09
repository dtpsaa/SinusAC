package ru.dtpsaa.sinusac.model;

public record BedrockCombatSnapshot(
        double x,
        double y,
        double z,
        double eye_x,
        double eye_y,
        double eye_z,
        float yaw,
        float pitch,
        long server_tick,
        long sequence,
        int ping,
        boolean on_ground,
        boolean sprinting,
        boolean sneaking,
        boolean in_vehicle,
        boolean attack,
        boolean teleport_grace,
        boolean velocity_grace,
        Target target) {

    public BedrockCombatSnapshot {
        ping = Math.max(0, ping);
    }

    public record TargetBox(
            double min_x,
            double min_y,
            double min_z,
            double max_x,
            double max_y,
            double max_z) {}

    public record Target(
            String uuid,
            TargetBox bounding_box,
            boolean visible,
            double distance) {}
}
