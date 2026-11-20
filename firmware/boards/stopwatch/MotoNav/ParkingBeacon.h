#pragma once
#include <Arduino.h>
#include <BLEDevice.h>
#include <Preferences.h>
#include "CompanionMode.h"

// 前向声明（由 esp32Nav.ino / WaitingScreen.h / ArriveFx.h 提供）
void idlePowerOffShutdown();
void drawWaiting(int st);
void stopArriveFx();

/**
 * 阶段二：圆屏超低功耗蓝牙寻车信标模式（Parking Beacon Mode）
 *
 * 触发方式：
 * 1. 手机端主动发送 #PKB,1,<maxMin> -> 标记信标就绪，离车蓝牙断连 3 秒后自动息屏省电；
 * 2. 圆屏 Arrived 结算页一键点击「记住车位 · 寻车信标」-> 上发 T,PK_MARK,360，进入 5 秒可取消倒计时，
 *    倒计时结束直接息屏进入超低功耗信标模式；
 * 3. 手机端靠近重连后可发送 #PKB,PING -> 圆屏瞬间最高亮度闪烁 + 马达震动呼叫，暗光地库一眼找车。
 *
 * 电池保护：
 * - 息屏（CO5300 displayOff）+ CPU 降频 80MHz + BLE 广播拉长至 500~1000ms + 跳过所有 UI/传感器轮询；
 * - 达到设定最长时长（默认 6 小时）或电池电量 <= 15%（Vbat < 3450mV）自动真关机保护电池。
 */

bool pkBeaconArmed = false;
bool pkBeaconSleeping = false;
bool pkCountdownActive = false;
unsigned long pkCountdownStartAt = 0;
unsigned long pkBeaconMaxMs = 360UL * 60000UL;
unsigned long pkBeaconEnteredAt = 0;
unsigned long pkDisconnSince = 0;
unsigned long pkLastBatCheckAt = 0;
unsigned long pkPingUntil = 0;
unsigned long pkLastPingVibAt = 0;
unsigned long pkToastUntil = 0;
char pkToastText[48] = {0};

static constexpr unsigned long PK_COUNTDOWN_MS = 5000UL;

static inline int pkLoadSavedBrightness() {
  Preferences pr;
  pr.begin("motonav", true);
  int b = constrain(pr.getInt("brightness", 200), 20, 255);
  pr.end();
  return b;
}

static inline void pkSetBleAdvLowPower(bool lowPower) {
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  if (!adv) return;
  if (lowPower) {
    // 500ms ~ 1000ms 慢速广播（单位 0.625ms）：射频功耗降 5~8 倍，手机端仍可在 1~2s 内稳定扫到 RSSI
    adv->setMinInterval(0x0320);
    adv->setMaxInterval(0x0640);
  } else {
    // 恢复正常快速重连间隔（40ms ~ 100ms）
    adv->setMinInterval(0x0040);
    adv->setMaxInterval(0x00A0);
  }
  if (!bleConnected) {
    BLEDevice::stopAdvertising();
    BLEDevice::startAdvertising();
  }
}

inline void pkBeaconEnterSleep() {
  if (pkBeaconSleeping) return;
  pkCountdownActive = false;
  pkBeaconSleeping = true;
  pkBeaconEnteredAt = millis();
  pkLastBatCheckAt = pkBeaconEnteredAt;

  Serial.println("[ParkingBeacon] Entering ultra-low-power BLE beacon mode");
  if (flushDone) {
    xSemaphoreTake(flushDone, pdMS_TO_TICKS(500));
    xSemaphoreGive(flushDone);
  }
  panel->setBrightness(0);
  panel->displayOff();
  pkSetBleAdvLowPower(true);
  if (getCpuFrequencyMhz() > 80) {
    setCpuFrequencyMhz(80);
  }
}

inline void pkBeaconWakeUp(bool disarm) {
  if (disarm) {
    pkBeaconArmed = false;
    pkCountdownActive = false;
  }
  pkDisconnSince = 0;
  if (!pkBeaconSleeping) return;

  pkBeaconSleeping = false;
  Serial.printf("[ParkingBeacon] Waking up (disarm=%d)\n", disarm ? 1 : 0);
  if (getCpuFrequencyMhz() < 240) {
    setCpuFrequencyMhz(240);
  }
  pkSetBleAdvLowPower(false);
  panel->displayOn();
  panel->setBrightness(pkLoadSavedBrightness());
  idlePowerOffReset(&idlePowerOffTimer, millis());
  drawWaiting(bleConnected ? WAIT_READY : WAIT_NOLINK);
}

inline void pkBeaconArm(int maxMinutes, bool showToast) {
  int clamped = constrain(maxMinutes, 30, 1440);
  pkBeaconMaxMs = (unsigned long)clamped * 60000UL;
  pkBeaconArmed = true;
  pkDisconnSince = 0;
  if (showToast) {
    snprintf(pkToastText, sizeof(pkToastText), isChineseLang ? "寻车信标已开 · 离车自动息屏" : "BEACON ARMED (%dh)", clamped / 60);
    pkToastUntil = millis() + 2500UL;
  }
  Serial.printf("[ParkingBeacon] Armed maxMinutes=%d\n", clamped);
}

inline void pkBeaconDisarm() {
  pkBeaconArmed = false;
  pkCountdownActive = false;
  pkPingUntil = 0;
  pkToastUntil = 0;
  if (pkBeaconSleeping) {
    pkBeaconWakeUp(true);
  } else {
    pkSetBleAdvLowPower(false);
  }
  Serial.println("[ParkingBeacon] Disarmed");
}

// 从 Arrived 到达结算界面一键触发
inline void pkBeaconTriggerFromArrived() {
  pkBeaconArm(360, false);
  pkCountdownActive = true;
  pkCountdownStartAt = millis();
  CompanionMode::startVibration(90, 40);
  if (bleConnected && txChar) {
    const char *msg = "T,PK_MARK,360";
    txChar->setValue((uint8_t *)msg, strlen(msg));
    txChar->notify();
  }
  Serial.println("[ParkingBeacon] Triggered from Arrived screen -> 5s countdown");
}

inline void pkBeaconCancelCountdown() {
  if (!pkCountdownActive) return;
  pkCountdownActive = false;
  pkBeaconArmed = false;
  CompanionMode::startVibration(50, 25);
  if (bleConnected && txChar) {
    const char *msg = "T,PK_CANCEL";
    txChar->setValue((uint8_t *)msg, strlen(msg));
    txChar->notify();
  }
  stopArriveFx();
  drawWaiting(bleConnected ? WAIT_READY : WAIT_NOLINK);
  Serial.println("[ParkingBeacon] Countdown cancelled by user");
}

inline void pkBeaconStartPing() {
  if (pkBeaconSleeping) {
    pkBeaconWakeUp(false);
  }
  pkPingUntil = millis() + 5000UL;
  pkLastPingVibAt = 0;
  panel->setBrightness(255);
  Serial.println("[ParkingBeacon] PING received -> flash & vibrate");
}

// 绘制 Arrived 触发后的 5 秒倒计时息屏界面
inline void drawParkingCountdown(unsigned long now) {
  unsigned long elapsed = now - pkCountdownStartAt;
  unsigned long remainMs = (elapsed < PK_COUNTDOWN_MS) ? (PK_COUNTDOWN_MS - elapsed) : 0;
  int secLeft = (int)((remainMs + 999UL) / 1000UL);
  if (secLeft < 1) secLeft = 1;

  gfx->fillScreen(0x0000);

  // 顶部 P 徽章
  aaDisc((float)CX, 98.0f, 30.0f, 0x03BF);
  gfx->drawCircle(CX, 98, 31, 0x3E7F);
  drawAaString((float)CX, 98.0f, "P", 2.8f, 1.5f, 18.0f, C_WHITE, true);

  // 中央倒计时数字与进度环
  char numBuf[8];
  snprintf(numBuf, sizeof(numBuf), "%d", secLeft);
  float frac = (float)remainMs / (float)PK_COUNTDOWN_MS;
  int rRing = 56;
  gfx->drawCircle(CX, 214, rRing, 0x18E3);
  int steps = (int)(frac * 72.0f);
  for (int i = 0; i < steps; i++) {
    float a = -1.5708f + (i / 72.0f) * 6.28318f;
    int px = CX + (int)lroundf(cosf(a) * rRing);
    int py = 214 + (int)lroundf(sinf(a) * rRing);
    gfx->fillCircle(px, py, 2, 0x3E7F);
  }
  drawAaString((float)CX, 214.0f, numBuf, 3.6f, 1.8f, 24.0f, C_WHITE, true);

  // 状态提示文案
  if (isChineseLang) {
    drawTextUTF8Center(CX, 304, bleConnected ? "已通知手机记住当前车位" : "离线信标 · 蓝牙将保持开启", 0x3E7F);
    drawTextUTF8Center(CX, 330, "倒计时后自动息屏省电辅助寻车", 0x9CD3);
  } else {
    drawAaString((float)CX, 302.0f, "PARKING SPOT SAVED", 1.5f, 0.9f, 10.0f, 0x3E7F, true);
    drawAaString((float)CX, 326.0f, "SLEEPING TO BLE BEACON", 1.2f, 0.8f, 8.5f, 0x9CD3, true);
  }

  // 底部取消按钮 (CX-80..CX+80, y=362..408)
  const int bw = 160, bh = 46;
  const int bx = CX - bw / 2, by = 362;
  drawTranslucentRoundRect(bx, by, bw, bh, 23, 0x2104, 220);
  gfx->drawRoundRect(bx, by, bw, bh, 23, 0x7BEF);
  if (isChineseLang) {
    drawTextUTF8Center(CX, by + 30, "取 消", C_WHITE);
  } else {
    drawAaString((float)CX, (float)(by + 23), "CANCEL", 1.5f, 0.9f, 10.0f, C_WHITE, true);
  }
  needFlush = true;
}

// 绘制呼叫爱车闪烁界面 (#PKB,PING)
inline void drawParkingPingFlash(unsigned long now) {
  bool brightPhase = ((now / 280UL) % 2) == 0;
  uint16_t bg = brightPhase ? 0x05BF : 0x0000;
  uint16_t fg = brightPhase ? C_WHITE : 0x3E7F;
  gfx->fillScreen(bg);
  gfx->drawCircle(CX, CX, 180, fg);
  gfx->drawCircle(CX, CX, 178, fg);
  drawAaString((float)CX, (float)(CX - 20), "HERE!", 3.2f, 1.7f, 22.0f, C_WHITE, true);
  if (isChineseLang) {
    drawTextUTF8Center(CX, CX + 38, "爱车在这里", C_WHITE);
  }
  needFlush = true;
}

// 主循环最前端调度：返回 true 表示本次循环由信标模式接管（跳过常规渲染与传感器轮询）
inline bool pkBeaconTick() {
  const unsigned long now = millis();

  // 1. 寻车呼叫闪烁 + 震动 (#PKB,PING)
  if (pkPingUntil > 0) {
    if ((long)(pkPingUntil - now) > 0) {
      if (pkLastPingVibAt == 0 || now - pkLastPingVibAt >= 1200UL) {
        pkLastPingVibAt = now;
        CompanionMode::startVibration(180, 80);
      }
      static unsigned long lastFlashDraw = 0;
      if (now - lastFlashDraw >= 140UL) {
        lastFlashDraw = now;
        drawParkingPingFlash(now);
        flushSync();
        needFlush = false;
      }
      delay(20);
      return true;
    } else {
      pkPingUntil = 0;
      panel->setBrightness(pkLoadSavedBrightness());
      drawWaiting(bleConnected ? WAIT_READY : WAIT_NOLINK);
    }
  }

  // 2. Arrived 一键记住车位后的 5 秒倒计时
  if (pkCountdownActive) {
    if (now - pkCountdownStartAt >= PK_COUNTDOWN_MS) {
      pkCountdownActive = false;
      stopArriveFx();
      pkBeaconEnterSleep();
      return true;
    }
    static unsigned long lastCdDraw = 0;
    if (now - lastCdDraw >= 120UL) {
      lastCdDraw = now;
      drawParkingCountdown(now);
      flushSync();
      needFlush = false;
    }
    return false; // 保持触控轮询以允许点击“取消”
  }

  // 3. 超低功耗蓝牙寻车信标睡眠态
  if (pkBeaconSleeping) {
    // 手机重新连上蓝牙（且已开始交互）或接通外部 USB 供电 -> 自动唤醒
    if (bleConnected) {
      pkBeaconWakeUp(false);
      return false;
    }
    // 最长信标时长超时保护 -> 自动真关机
    if (now - pkBeaconEnteredAt >= pkBeaconMaxMs) {
      Serial.println("[ParkingBeacon] Max duration reached -> Power off");
      idlePowerOffShutdown();
      return true;
    }
    // 每 60 秒轮询一次 M5PM1 电源芯片，电量 <= 15% 或 Vbat < 3450mV 硬保护关机
    if (now - pkLastBatCheckAt >= 60000UL) {
      pkLastBatCheckAt = now;
      if (pmuOK) {
        uint16_t vin = 0, vbat = 0;
        bool extPower = (StopWatchBoard::pm1.readVin(&vin) == M5PM1_OK && vin > 4000);
        if (!extPower && StopWatchBoard::pm1.readVbat(&vbat) == M5PM1_OK && vbat > 2500 && vbat < 3450) {
          Serial.printf("[ParkingBeacon] Low battery (%umV) -> Power off to protect cell\n", vbat);
          idlePowerOffShutdown();
          return true;
        }
      }
    }
    // 睡眠期间极低频轮询，省电且保持 BLE 协议栈响应
    delay(200);
    return true;
  }

  // 4. 手机端已开启信标，检测到离车蓝牙断开满 3 秒 -> 自动切入超低功耗息屏信标态
  if (pkBeaconArmed && !bleConnected) {
    if (pkDisconnSince == 0) pkDisconnSince = now;
    if (now - pkDisconnSince >= 3000UL) {
      pkBeaconEnterSleep();
      return true;
    }
  } else {
    pkDisconnSince = 0;
  }

  return false;
}
