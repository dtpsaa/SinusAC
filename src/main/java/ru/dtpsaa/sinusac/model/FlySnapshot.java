package ru.dtpsaa.sinusac.model;

import com.google.gson.annotations.SerializedName;

/** One server-tick movement snapshot for the server-side Fly rule engine. */
public final class FlySnapshot {

    public final double x;
    public final double y;
    public final double z;

    @SerializedName("on_ground") public final boolean onGround;
    @SerializedName("in_water") public final boolean inWater;
    @SerializedName("in_lava") public final boolean inLava;
    public final boolean climbing;
    public final boolean gliding;
    public final boolean levitation;
    @SerializedName("slow_falling") public final boolean slowFalling;
    public final boolean riptiding;
    @SerializedName("in_vehicle") public final boolean inVehicle;
    @SerializedName("on_slime") public final boolean onSlime;
    @SerializedName("move_speed") public final double moveSpeed;
    @SerializedName("jump_boost") public final double jumpBoost;
    public final boolean grace;

    public FlySnapshot(double x, double y, double z, boolean onGround,
                       boolean inWater, boolean inLava, boolean climbing,
                       boolean gliding, boolean levitation, boolean slowFalling,
                       boolean riptiding, boolean inVehicle, boolean onSlime,
                       double moveSpeed, double jumpBoost, boolean grace) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.onGround = onGround;
        this.inWater = inWater;
        this.inLava = inLava;
        this.climbing = climbing;
        this.gliding = gliding;
        this.levitation = levitation;
        this.slowFalling = slowFalling;
        this.riptiding = riptiding;
        this.inVehicle = inVehicle;
        this.onSlime = onSlime;
        this.moveSpeed = moveSpeed;
        this.jumpBoost = jumpBoost;
        this.grace = grace;
    }
}

