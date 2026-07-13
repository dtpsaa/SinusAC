package ru.dtpsaa.sinusac.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Сессия одного игрока: скользящее окно фреймов (yaw/pitch),
 * GCD-статистика по дельтам поворота, счётчик VL и метка обучения.
 * <p>
 * Поле markedAs — часть тренировочного API: сами команды train/learn
 * живут в SinusOP, но данные сессии хранятся здесь, в движке.
 * Логика перенесена из SinusAI 1-в-1, изменён только пакет.
 */
public class PlayerSession {

    public final UUID uuid;
    public final String name;

    private final List<Frame> frames = new ArrayList<>();
    private final int maxFrames;

    private float prevYaw = Float.NaN;
    private float prevPitch = Float.NaN;

    private final Deque<Double> gcdYawHistory;
    private final Deque<Double> gcdPitchHistory;
    private final int gcdHistorySize;

    private double gcdYaw = 0.0D;
    private double gcdPitch = 0.0D;

    private int vl = 0;
    private int combatTicks = 0;

    /** null — обычный мониторинг; true/false — игрок записывается как CHEATER/LEGIT (управляется из SinusOP). */
    private Boolean markedAs = null;

    public PlayerSession(UUID uuid, String name, int maxFrames, int gcdHistorySize) {
        this.uuid = uuid;
        this.name = name;
        this.maxFrames = maxFrames;
        this.gcdHistorySize = gcdHistorySize;
        this.gcdYawHistory = new ArrayDeque<>(gcdHistorySize);
        this.gcdPitchHistory = new ArrayDeque<>(gcdHistorySize);
    }

    /** Добавляет кадр движения. Первый вызов только инициализирует prev-значения. */
    public void addMovement(float yaw, float pitch) {
        if (Float.isNaN(this.prevYaw)) {
            this.prevYaw = yaw;
            this.prevPitch = pitch;
            return;
        }
        updateGcd((yaw - this.prevYaw), (pitch - this.prevPitch));
        this.frames.add(new Frame(yaw, pitch));
        if (this.frames.size() > this.maxFrames)
            this.frames.remove(0);
        this.prevYaw = yaw;
        this.prevPitch = pitch;
    }

    private void updateGcd(double deltaYaw, double deltaPitch) {
        double absYaw = Math.abs(deltaYaw);
        double absPitch = Math.abs(deltaPitch);
        if (absYaw > 0.001D) {
            if (this.gcdYawHistory.size() >= this.gcdHistorySize)
                this.gcdYawHistory.pollFirst();
            this.gcdYawHistory.addLast(absYaw);
        }
        if (absPitch > 0.001D) {
            if (this.gcdPitchHistory.size() >= this.gcdHistorySize)
                this.gcdPitchHistory.pollFirst();
            this.gcdPitchHistory.addLast(absPitch);
        }
        if (this.gcdYawHistory.size() >= 5)
            this.gcdYaw = computeGcd(this.gcdYawHistory);
        if (this.gcdPitchHistory.size() >= 5)
            this.gcdPitch = computeGcd(this.gcdPitchHistory);
    }

    private double computeGcd(Deque<Double> history) {
        double result = 0.0D;
        for (Iterator<Double> iterator = history.iterator(); iterator.hasNext(); ) {
            double v = iterator.next();
            result = gcd(result, v);
            if (result < 1.0E-4D)
                return 0.0D;
        }
        return result;
    }

    private double gcd(double a, double b) {
        return (b < 1.0E-4D) ? a : gcd(b, a % b);
    }

    public void tickCombat()        { this.combatTicks++; }
    public void resetCombatTicks()  { this.combatTicks = 0; }
    public int getCombatTicks()     { return this.combatTicks; }

    /** Возвращает КОПИЮ списка фреймов — безопасно для асинхронной обработки. */
    public List<Frame> getFrames()  { return new ArrayList<>(this.frames); }
    public int getFrameCount()      { return this.frames.size(); }

    /** Полный сброс сессии (используется при начале записи в SinusOP). */
    public void clearFrames() {
        this.frames.clear();
        this.prevYaw = Float.NaN;
        this.prevPitch = Float.NaN;
        this.gcdYawHistory.clear();
        this.gcdPitchHistory.clear();
        this.gcdYaw = 0.0D;
        this.gcdPitch = 0.0D;
        this.combatTicks = 0;
    }

    public int addVl()              { return ++this.vl; }
    public void setVl(int vl)       { this.vl = Math.max(0, vl); }
    public int getVl()              { return this.vl; }

    public void setMarkedAs(Boolean v) { this.markedAs = v; }
    public Boolean getMarkedAs()       { return this.markedAs; }
}
